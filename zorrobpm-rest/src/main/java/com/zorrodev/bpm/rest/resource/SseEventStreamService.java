package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.EventQueryService;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SSE bridge: subscribes to zorrobpm.events (exclusive queue per instance)
 * and pushes events to connected SSE clients with AuthZ filtering (ADR-7, WO-EVT-4).
 *
 * WO-PERF-6 (P-3): per-client executor, timeout+drop, maxClients→429,
 * cursor+limit catchup, UUID.fromString hoisted.
 */
@Slf4j
@Service
public class SseEventStreamService implements SmartLifecycle {

    private final EventQueryService eventQueryService;
    private final EventAuthzResolver eventAuthzResolver;
    private final RabbitAdmin rabbitAdmin;
    // WO-PERF-1 N4: single thread-safe Jackson 3 ObjectMapper instance (replaces per-message new)
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    public SseEventStreamService(EventQueryService eventQueryService,
                                    EventAuthzResolver eventAuthzResolver,
                                    @Lazy @Autowired(required = false) RabbitAdmin rabbitAdmin,
                                    tools.jackson.databind.ObjectMapper objectMapper) {
        this.eventQueryService = eventQueryService;
        this.eventAuthzResolver = eventAuthzResolver;
        this.rabbitAdmin = rabbitAdmin;
        this.objectMapper = objectMapper;
    }

    /** Connected SSE clients: emitterId → client info */
    private final Map<String, SseClientInfo> clients = new ConcurrentHashMap<>();

    /** Per-client fan-out executor — not the RabbitMQ consumer thread (WO-PERF-6 head-of-line) */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setName("sse-send-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    @Value("${zorrobpm.sse.max-clients:1000}")
    private int maxClients = 1000;

    @Value("${zorrobpm.sse.send-timeout-ms:5000}")
    private long sendTimeoutMs = 5000;

    /** RabbitMQ listener container for this instance */
    private volatile SimpleMessageListenerContainer listenerContainer;

    /**
     * Guards bridge lifecycle transitions (flag checks, field assign/null-out,
     * client-map snapshot). Only ever held for fast local work — never for
     * blocking broker RPCs ({@code declare*} run lock-free in the starter;
     * {@code container.start()/stop()/destroy()} run outside it). A stalled
     * broker must not serialize registrations or disconnects behind the lock.
     */
    private final Object bridgeLock = new Object();

    /** A bridge start is in flight on a background thread (at most one). */
    private final java.util.concurrent.atomic.AtomicBoolean bridgeStarting =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Delay between bridge start retries while clients wait (test-shrinkable). */
    private volatile long retryIntervalMs = 10_000;

    /** Functional interface for test event capture: receives clientId + envelope. */
    @FunctionalInterface
    public interface EventDispatchListener {
        void onEventSent(String clientId, Map<String, Object> envelope);
    }

    /** Test/observability hook: called when an event is sent to a client */
    private final List<EventDispatchListener> eventListeners = new CopyOnWriteArrayList<>();

    /** Register a listener that receives each event dispatch (clientId + envelope). */
    public void addEventListener(EventDispatchListener listener) {
        eventListeners.add(listener);
    }

    /** Remove all event listeners (for test cleanup). */
    public void clearEventListeners() {
        eventListeners.clear();
    }

    /**
     * Registers an SSE client and starts RabbitMQ subscription if this is the first client.
     * Throws 429 if maxClients exceeded (WO-PERF-6: FD exhaustion guard).
     */
    public String registerClient(SseEmitter emitter, Principal principal, String typeFilter,
                                   String processInstanceIdFilter, String processDefinitionKeyFilter) {
        synchronized (clients) {
            if (clients.size() >= maxClients) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many SSE clients");
            }
            String clientId = UUID.randomUUID().toString();
            Collection<UUID> allowedPdIds = eventAuthzResolver.readableRuntimePdIds(principal, processDefinitionKeyFilter);

            SseClientInfo info = new SseClientInfo(clientId, emitter, principal, allowedPdIds,
                typeFilter, processInstanceIdFilter, processDefinitionKeyFilter);
            clients.put(clientId, info);

            emitter.onCompletion(() -> {
                clients.remove(clientId);
                log.info("SSE client {} disconnected (completion)", clientId);
                stopRabbitMqListenerIfNoClients();
            });
            emitter.onTimeout(() -> {
                clients.remove(clientId);
                log.info("SSE client {} disconnected (timeout)", clientId);
                stopRabbitMqListenerIfNoClients();
            });
            emitter.onError(e -> {
                clients.remove(clientId);
                log.info("SSE client {} disconnected (error: {})", clientId, e.getMessage());
                stopRabbitMqListenerIfNoClients();
            });

            // If this is the first client, start RabbitMQ subscription.
            // Never on the HTTP thread: declares + container.start() are blocking
            // broker RPCs (consumer start waits up to 60s on a sick broker), and a
            // stalled broker once hung GET /events/stream with zero response
            // (WO-REL-20). The stream opens immediately; events flow once ready.
            ensureBridgeStarted();

            log.info("SSE client {} registered: type={}, processInstanceId={}, processDefinitionKey={}",
                clientId, typeFilter, processInstanceIdFilter, processDefinitionKeyFilter);
            return clientId;
        }
    }

    /**
     * Removes an SSE client.
     */
    public void removeClient(String clientId) {
        clients.remove(clientId);
        bufferedEvents.remove(clientId);
        log.info("SSE client {} removed", clientId);
        stopRabbitMqListenerIfNoClients();
    }

    /**
     * WO-REL-37 (F14): регистрация live-подписки ДО чтения catchup.
     * Клиент сразу виден live-рассылке, но его события буферизуются (не шлются),
     * пока контроллер не вызовет {@link #drainBufferedClient} с границей catchup:
     * события с sequence <= границы отбрасываются (дубль catchup), новее —
     * доставляются. Окно потери между catchup-чтением и подпиской закрыто.
     *
     * @return clientId для {@link #drainBufferedClient}
     */
    public String registerBufferedClient(SseEmitter emitter, Principal principal, String typeFilter,
                                    String processInstanceIdFilter, String processDefinitionKeyFilter) {
        String clientId = registerClient(emitter, principal, typeFilter,
            processInstanceIdFilter, processDefinitionKeyFilter);
        bufferingClients.add(clientId);
        return clientId;
    }

    /**
     * WO-REL-37 (F14): слить буфер пересечения: отбросить дубль (sequence <=
     * границы catchup), доставить новое (sequence > границы), перевести клиента
     * в обычный live-режим.
     */
    public void drainBufferedClient(String clientId, long catchupBoundary) {
        SseClientInfo client = clients.get(clientId);
        if (client == null) {
            bufferedEvents.remove(clientId);
            bufferingClients.remove(clientId);
            return;
        }
        List<Map<String, Object>> buffered = bufferedEvents.remove(clientId);
        bufferingClients.remove(clientId);
        if (buffered == null || buffered.isEmpty()) {
            return;
        }
        for (Map<String, Object> envelope : buffered) {
            Object seqObj = envelope.get("sequence");
            long seq = seqObj instanceof Number n ? n.longValue() : 0;
            if (seq <= catchupBoundary) {
                continue;
            }
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(String.valueOf(seq))
                .name((String) envelope.get("type"))
                .data(envelope)
                .reconnectTime(3000);
            sseExecutor.execute(() -> {
                try {
                    client.emitter().send(event);
                } catch (IOException e) {
                    log.warn("Failed to send drained event to client {}", clientId);
                    clients.remove(clientId);
                }
            });
        }
    }

    /** Буфер пересечения catchup→live: clientId → события, пришедшие до drain. */
    private final Map<String, List<Map<String, Object>>> bufferedEvents = new ConcurrentHashMap<>();

    /** Клиенты в режиме буферизации (зарегистрированы, но ещё не drained). */
    private final Set<String> bufferingClients = ConcurrentHashMap.newKeySet();

    /**
     * Called when a domain event arrives from RabbitMQ.
     * Pushes to all connected clients that match the filter and AuthZ.
     * Runs on RabbitMQ consumer thread — fan-out is offloaded to sseExecutor
     * so one slow client never blocks the others (WO-PERF-6).
     */
    public void onDomainEvent(String messageBody) {
        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(messageBody, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse domain event envelope", e);
            return;
        }

        String eventType = (String) envelope.get("type");
        String processInstanceId = (String) envelope.get("processInstanceId");
        String processDefinitionId = (String) envelope.get("processDefinitionId");
        Object sequenceObj = envelope.get("sequence");
        long sequence = sequenceObj instanceof Number n ? n.longValue() : 0;

        // WO-PERF-6: hoist UUID.fromString outside the per-client loop
        UUID pdUuid = null;
        if (processDefinitionId != null) {
            try {
                pdUuid = UUID.fromString(processDefinitionId);
            } catch (IllegalArgumentException ex) {
                log.warn("Invalid processDefinitionId UUID {}", processDefinitionId);
                // fail-closed: no client matches an unparsable pdId
                return;
            }
        }

        for (SseClientInfo client : clients.values()) {
            // Check type filter
            if (client.typeFilter != null && !client.typeFilter.isBlank()
                && !client.typeFilter.equals(eventType)) {
                continue;
            }

            // Check processInstanceId filter
            if (client.processInstanceIdFilter != null && !client.processInstanceIdFilter.isBlank()
                && !client.processInstanceIdFilter.equals(processInstanceId)) {
                continue;
            }

            // Check AuthZ: processDefinitionId must be in allowed set (fail-closed: G-L)
            if (client.allowedPdIds != null) {
                if (pdUuid == null || !client.allowedPdIds.contains(pdUuid)) {
                    continue;
                }
            }

            // WO-REL-37 (F14): клиент в режиме буферизации — событие в буфер
            // пересечения, не на emitter (drain решит: дубль или новое).
            if (bufferingClients.contains(client.clientId)) {
                bufferedEvents.computeIfAbsent(client.clientId,
                    k -> new CopyOnWriteArrayList<>()).add(envelope);
                continue;
            }

            // Per-client async send — not on the RabbitMQ thread (WO-PERF-6 head-of-line)
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(String.valueOf(sequence))
                .name(eventType)
                .data(envelope)
                .reconnectTime(3000);

            try {
                sseExecutor.execute(() -> {
                    // Notify listeners (test hook — one call per matching client)
                    for (EventDispatchListener listener : eventListeners) {
                        try {
                            listener.onEventSent(client.clientId, envelope);
                        } catch (Exception ex) {
                            log.warn("Event listener error", ex);
                        }
                    }

                    // Send with 5s timeout — slow client is dropped, not blocked.
                    // WO-PERF-6: cancel(true) does NOT interrupt a blocking network write
                    // (Java IO without InterruptibleChannel) — the thread frees only when
                    // emitter.complete()/IOException fires, non-deterministically. We at
                    // least isolate the block to sseExecutor (not ForkJoinPool.commonPool).
                    CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> {
                        try {
                            client.emitter.send(event);
                        } catch (IOException e) {
                            throw new java.util.concurrent.CompletionException(e);
                        }
                    }, sseExecutor);
                    try {
                        cf.get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException te) {
                        log.warn("Slow SSE client {} timed out ({}ms), dropping", client.clientId, sendTimeoutMs);
                        cf.cancel(true);
                        clients.remove(client.clientId);
                        try { client.emitter.complete(); } catch (Exception ignore) {}
                    } catch (java.util.concurrent.ExecutionException ee) {
                        Throwable cause = ee.getCause();
                        if (cause != null && cause.getCause() instanceof IOException) {
                            log.warn("Failed to send event to client {}: {}", client.clientId, cause.getCause().getMessage());
                        } else {
                            log.warn("Failed to send event to client {}: {}", client.clientId, cause != null ? cause.getMessage() : ee.getMessage());
                        }
                        clients.remove(client.clientId);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.error("Error sending event to client {}", client.clientId, e);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException re) {
                // After shutdown executor is terminated — fallback direct dispatch so tests still fire
                for (EventDispatchListener listener : eventListeners) {
                    try {
                        listener.onEventSent(client.clientId, envelope);
                    } catch (Exception ex) {
                        log.warn("Event listener error", ex);
                    }
                }
            }
        }
    }

    /**
     * Sends catchup events from the database for reconnect (Last-Event-ID).
     *
     * <p>WO-REL-37 (F12/F14): единый путь с live и REST — тот же
     * {@code EventQueryService.findEventEnvelopes} (тот же фильтр type/piId +
     * гранты, та же пагинация maxResults+1/hasMore, фильтрация в SQL до окна).
     * Envelope строит сервис (null-safe LinkedHashMap, не Map.of) — штатный
     * null elementId больше не роняет весь backlog (F12). Возвращает границу
     * (max sequence выданного, или since — если ничего не выдано) для дедупа
     * пересечения catchup→live.
     *
     * @return max выданного sequence (exclusive-граница live-буфера)
     */
    public long sendCatchupEvents(SseEmitter emitter, long sinceSequence, Principal principal,
                                    String processDefinitionKeyFilter) {
        return sendCatchupEvents(emitter, sinceSequence, principal, processDefinitionKeyFilter,
            null, null);
    }

    /**
     * Полная форма с теми же фильтрами, что live-подписка (F14: один и тот же
     * фильтр для catchup и live — type + processInstanceId).
     */
    public long sendCatchupEvents(SseEmitter emitter, long sinceSequence, Principal principal,
                                    String processDefinitionKeyFilter,
                                    String typeFilter, String processInstanceIdFilter) {
        Collection<UUID> allowedPdIds = eventAuthzResolver.readableRuntimePdIds(principal, null);
        List<UUID> keyPdIds = eventQueryService.resolveKeyPdIds(processDefinitionKeyFilter);
        Collection<UUID> pdFilter = intersect(allowedPdIds, keyPdIds);
        if (pdFilter != null && pdFilter.isEmpty()) {
            return sinceSequence;
        }

        UUID piId = null;
        if (processInstanceIdFilter != null && !processInstanceIdFilter.isBlank()) {
            try {
                piId = UUID.fromString(processInstanceIdFilter);
            } catch (IllegalArgumentException e) {
                return sinceSequence;
            }
        }

        // Пагинация за пределами 100: страницами по 100, пока есть hasMore (F14:
        // "100 на страницу", не "100 на весь backlog").
        long boundary = sinceSequence;
        while (true) {
            List<Map<String, Object>> envelopes =
                eventQueryService.findEventEnvelopes(boundary, pdFilter, piId, typeFilter, 100);
            boolean hasMore = envelopes.size() > 100;
            List<Map<String, Object>> page = hasMore ? envelopes.subList(0, 100) : envelopes;
            for (Map<String, Object> envelope : page) {
                try {
                    Object seqObj = envelope.get("sequence");
                    long seq = seqObj instanceof Number n ? n.longValue() : boundary;
                    SseEmitter.SseEventBuilder sseEvent = SseEmitter.event()
                        .id(String.valueOf(seq))
                        .name((String) envelope.get("type"))
                        .data(envelope)
                        .reconnectTime(3000);
                    emitter.send(sseEvent);
                    if (seq > boundary) {
                        boundary = seq;
                    }
                } catch (Exception e) {
                    // F12: одна битая запись не обрывает остаток backlog (было break).
                    log.error("Error sending catchup event, continuing with the rest", e);
                }
            }
            if (!hasMore) {
                break;
            }
        }
        return boundary;
    }

    /** null = unrestricted; пересечение "see all" с key-фильтром даёт key-фильтр. */
    private static Collection<UUID> intersect(Collection<UUID> allowed, Collection<UUID> extra) {
        if (allowed == null) return extra;
        if (extra == null) return allowed;
        Set<UUID> result = new java.util.LinkedHashSet<>(allowed);
        result.retainAll(new java.util.LinkedHashSet<>(extra));
        return new java.util.ArrayList<>(result);
    }

    /**
     * Triggers a bridge start unless one is already running or starting.
     * Returns immediately — the start itself runs on a background thread.
     * Safe to call from every registration (not just the first client): the
     * flag makes concurrent/duplicate starts impossible, and a failed start
     * simply leaves the bridge DOWN so the next registration retries.
     */
    private void ensureBridgeStarted() {
        synchronized (bridgeLock) {
            spawnStarterIfNeededLocked();
        }
    }

    /**
     * Must hold {@link #bridgeLock}. Spawns at most one background starter:
     * only with waiting clients, a DOWN bridge, no attempt in flight — and
     * never when there is no broker to talk to ({@code rabbitAdmin == null}
     * is static wiring, not a transient failure; spinning attempts on it
     * would burn a thread forever, while a later registration re-arms
     * anyway if wiring ever changes).
     */
    private void spawnStarterIfNeededLocked() {
        if (rabbitAdmin == null || clients.isEmpty()
                || (listenerContainer != null && listenerContainer.isRunning())
                || !bridgeStarting.compareAndSet(false, true)) {
            return;
        }
        // WO-REL-23: propagate MDC traceId to async starter (plain clear() loses it)
        var parentMdc = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofVirtual().name("sse-bridge-starter").start(() -> {
            if (parentMdc != null) org.slf4j.MDC.setContextMap(parentMdc);
            try {
                startBridgeLoop();
            } finally {
                org.slf4j.MDC.clear();
                synchronized (bridgeLock) {
                    bridgeStarting.set(false);
                    spawnStarterIfNeededLocked();
                }
            }
        });
    }

    /**
     * Starts the bridge, retrying while unserved clients remain. A failed
     * start NEVER removes or fails clients (register/remove/disconnect own
     * the map exclusively — a slow background failure must not wipe streams
     * registered later, which broke SseEventStreamIntegrationTest): waiting
     * clients simply stay until the bridge comes up or they leave, and every
     * failure is ERROR-logged. The loop is single-flight (flag held for its
     * whole lifetime) and self-terminating (empty map or bridge up).
     */
    private void startBridgeLoop() {
        boolean first = true;
        // NOTE: no try/finally flag clear here — the single clear + re-arm
        // lives in the spawner's finally (single-flight must be atomic).
        while (true) {
            synchronized (bridgeLock) {
                if (clients.isEmpty()
                        || (listenerContainer != null && listenerContainer.isRunning())) {
                    return;
                }
            }
            if (!first) {
                log.warn("SSE bridge: retrying start ({} waiting clients)", clients.size());
            }
            first = false;
            try {
                startBridgeNow();
                return;
            } catch (Exception e) {
                log.error("SSE bridge: failed to start RabbitMQ listener, retrying", e);
            }
            try {
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void startBridgeNow() {
        if (rabbitAdmin == null) {
            log.warn("RabbitAdmin not available — SSE bridge not started (no RabbitMQ)");
            return;
        }

        String queueName = "zorrobpm.sse-bridge." + UUID.randomUUID();
        Queue queue = new Queue(queueName, false, true, true); // exclusive, auto-delete
        // Blocking broker RPCs run WITHOUT holding bridgeLock: a stalled
        // broker must not serialize registrations (or stop()) behind them —
        // holding the lock across declares reintroduced the very
        // request pile-up WO-REL-20 removes (caught by SseBridgeStartupTest).
        // Same for stop()/destroy() below (container shutdown is a broker
        // RPC too): null-out under the lock, stop outside it.
        try {
            rabbitAdmin.declareQueue(queue);
            Binding binding = BindingBuilder.bind(queue)
                .to(new TopicExchange(RabbitConfiguration.EVENTS_EXCHANGE, true, false))
                .with("#");
            rabbitAdmin.declareBinding(binding);
        } catch (RuntimeException e) {
            // Partial declare must not leave an orphan queue behind.
            deleteQueueQuietly(queueName);
            throw e;
        }
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(rabbitAdmin.getRabbitTemplate().getConnectionFactory());
        container.setQueueNames(queueName);
        container.setMessageListener((message) -> {
            String body = new String(message.getBody());
            onDomainEvent(body);
        });
        // Outside the lock as well: may block up to the consumer-start
        // timeout on a sick broker.
        try {
            container.start();
        } catch (RuntimeException e) {
            stopAndDestroy(container);
            deleteQueueQuietly(queueName);
            throw e;
        }
        boolean assigned = false;
        SimpleMessageListenerContainer previous = null;
        synchronized (bridgeLock) {
            if (!clients.isEmpty() && (listenerContainer == null || !listenerContainer.isRunning())) {
                // Null-out under the lock, stop outside it: even a stale
                // stopped container's shutdown path must never run under
                // bridgeLock (verifier round 2 — stop()/destroy() are
                // broker RPCs and would re-serialize registrations).
                previous = listenerContainer;
                listenerContainer = container;
                assigned = true;
            }
        }
        if (assigned) {
            // `previous` here is never running (checked above under the lock),
            // so this is fast local teardown, not a broker stall.
            stopAndDestroy(previous);
            log.info("SSE bridge: started RabbitMQ listener on queue {}", queueName);
            return;
        }
        // Nobody to serve (all left while starting), or a newer container won
        // the race: take ours down, keep exactly one.
        stopAndDestroy(container);
        log.info("SSE bridge: starter finished with no live bridge to publish — stopped immediately");
    }

    private void stopRabbitMqListenerIfNoClients() {
        SimpleMessageListenerContainer doomed;
        synchronized (bridgeLock) {
            if (!clients.isEmpty()) {
                return;
            }
            doomed = listenerContainer;
            listenerContainer = null;
        }
        // Outside the lock: container shutdown is a broker RPC and must not
        // serialize registrations behind it (WO-REL-20 verifier finding).
        if (doomed != null) {
            stopAndDestroy(doomed);
            log.info("SSE bridge: stopped RabbitMQ listener (no clients)");
        }
    }

    /** Stops + destroys a container. Call outside {@link #bridgeLock}. */
    private void stopAndDestroy(SimpleMessageListenerContainer container) {
        if (container == null) {
            return;
        }
        try {
            if (container.isRunning()) {
                container.stop();
            }
        } catch (Exception e) {
            log.warn("SSE bridge: error stopping listener container", e);
        } finally {
            try {
                container.destroy();
            } catch (Exception e) {
                log.warn("SSE bridge: error destroying listener container", e);
            }
        }
    }

    private void deleteQueueQuietly(String queueName) {
        try {
            rabbitAdmin.deleteQueue(queueName);
        } catch (Exception cleanupEx) {
            log.warn("SSE bridge: failed to clean up queue {} after failed start", queueName, cleanupEx);
        }
    }

    // ---- SmartLifecycle for graceful shutdown (WO-REL-23) ----
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        if (!running.compareAndSet(true, false)) {
            callback.run();
            return;
        }
        log.info("SSE shutdown: completing {} active emitters", clients.size());
        // Snapshot to avoid concurrent modification; complete outside lock where possible
        var snapshot = new java.util.ArrayList<>(clients.values());
        clients.clear();
        for (var c : snapshot) {
            try {
                c.emitter().complete();
            } catch (Exception e) {
                log.warn("SSE shutdown: emitter complete failed for {}", c.clientId(), e);
            }
        }
        SimpleMessageListenerContainer doomed;
        synchronized (bridgeLock) {
            doomed = listenerContainer;
            listenerContainer = null;
        }
        if (doomed != null) {
            stopAndDestroy(doomed);
            log.info("SSE shutdown: listener stopped");
        }
        // WO-PERF-6: do not kill in-flight emits — shutdown() would reject them (RejectedExecution)
        sseExecutor.shutdown();
        try { sseExecutor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // Stop early in shutdown order (high phase stops first) — drain SSE before web layer fully closes
        return Integer.MAX_VALUE - 100;
    }

    private record SseClientInfo(
        String clientId,
        SseEmitter emitter,
        Principal principal,
        Collection<UUID> allowedPdIds,
        String typeFilter,
        String processInstanceIdFilter,
        String processDefinitionKeyFilter
    ) {}
}
