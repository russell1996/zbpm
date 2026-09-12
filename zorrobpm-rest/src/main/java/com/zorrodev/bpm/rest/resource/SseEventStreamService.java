package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE bridge: subscribes to zorrobpm.events (exclusive queue per instance)
 * and pushes events to connected SSE clients with AuthZ filtering (ADR-7, WO-EVT-4).
 */
@Slf4j
@Service
public class SseEventStreamService implements SmartLifecycle {

    private final DomainEventRepository domainEventRepository;
    private final EventAuthzResolver eventAuthzResolver;
    private final RabbitAdmin rabbitAdmin;
    // WO-PERF-1 N4: single thread-safe Jackson 3 ObjectMapper instance (replaces per-message new)
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    public SseEventStreamService(DomainEventRepository domainEventRepository,
                                  EventAuthzResolver eventAuthzResolver,
                                  @Lazy @Autowired(required = false) RabbitAdmin rabbitAdmin,
                                  tools.jackson.databind.ObjectMapper objectMapper) {
        this.domainEventRepository = domainEventRepository;
        this.eventAuthzResolver = eventAuthzResolver;
        this.rabbitAdmin = rabbitAdmin;
        this.objectMapper = objectMapper;
    }

    /** Connected SSE clients: emitterId → client info */
    private final Map<String, SseClientInfo> clients = new ConcurrentHashMap<>();

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
     */
    public String registerClient(SseEmitter emitter, Principal principal, String typeFilter,
                                  String processInstanceIdFilter, String processDefinitionKeyFilter) {
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

    /**
     * Removes an SSE client.
     */
    public void removeClient(String clientId) {
        clients.remove(clientId);
        log.info("SSE client {} removed", clientId);
        stopRabbitMqListenerIfNoClients();
    }

    /**
     * Called when a domain event arrives from RabbitMQ.
     * Pushes to all connected clients that match the filter and AuthZ.
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

        for (SseClientInfo client : clients.values()) {
            try {
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
                    if (processDefinitionId == null || !client.allowedPdIds.contains(UUID.fromString(processDefinitionId))) {
                        continue;
                    }
                }

                // Push event to client
                SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .id(String.valueOf(sequence))
                    .name(eventType)
                    .data(envelope)
                    .reconnectTime(3000);

                // Notify listeners (test/observability hook)
                for (EventDispatchListener listener : eventListeners) {
                    try {
                        listener.onEventSent(client.clientId, envelope);
                    } catch (Exception ex) {
                        log.warn("Event listener error", ex);
                    }
                }

                client.emitter.send(event);
            } catch (IOException e) {
                log.warn("Failed to send event to client {}: {}", client.clientId, e.getMessage());
                clients.remove(client.clientId);
            } catch (Exception e) {
                log.error("Error sending event to client {}", client.clientId, e);
            }
        }
    }

    /**
     * Sends catchup events from the database for reconnect (Last-Event-ID).
     */
    public void sendCatchupEvents(SseEmitter emitter, long sinceSequence, Principal principal,
                                   String processDefinitionKeyFilter) {
        Collection<UUID> allowedPdIds = eventAuthzResolver.readableRuntimePdIds(principal, processDefinitionKeyFilter);

        int limit = 100;
        List<DomainEventEntity> events;
        if (allowedPdIds == null) {
            events = domainEventRepository.findSince(sinceSequence, limit);
        } else {
            events = domainEventRepository.findSinceForPrincipal(sinceSequence, allowedPdIds, limit);
        }

        for (DomainEventEntity event : events) {
            try {
                Map<String, Object> envelope = Map.of(
                    "sequence", event.getSequence(),
                    "id", event.getId().toString(),
                    "type", event.getType(),
                    "version", event.getVersion(),
                    "occurredAt", event.getOccurredAt().toString(),
                    "processDefinitionId", event.getProcessDefinitionId() != null ? event.getProcessDefinitionId().toString() : null,
                    "processInstanceId", event.getProcessInstanceId() != null ? event.getProcessInstanceId().toString() : null,
                    "elementId", event.getElementId() != null ? event.getElementId() : null,
                    "ownerScope", event.getOwnerScope() != null ? event.getOwnerScope() : null,
                    "data", event.getData() != null ? event.getData() : Map.of()
                );

                SseEmitter.SseEventBuilder sseEvent = SseEmitter.event()
                    .id(String.valueOf(event.getSequence()))
                    .name(event.getType())
                    .data(envelope)
                    .reconnectTime(3000);

                emitter.send(sseEvent);
            } catch (Exception e) {
                log.error("Error sending catchup event {}", event.getSequence(), e);
                break;
            }
        }
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
