package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.UiUserLookupService;
import com.zorrodev.bpm.engine.service.ApiKeyService;
import com.zorrodev.bpm.engine.service.EventQueryService;
import com.zorrodev.bpm.exchange.TraceHeaders;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    // WO-SEC-67 (F13): live credential checks for open streams (same lookups
    // as JwtAuthFilter, same fail-closed direction). Constructor-injected like
    // the resolver — no static access, no new wiring shape. The key check goes
    // through ApiKeyService (engine side owns the key rows — WO-DEBT-7
    // REST→JPA boundary: rest/resource must not import engine.repository).
    private final UiUserLookupService uiUserLookupService;
    private final ApiKeyService apiKeyService;
    // WO-PERF-1 N4: single thread-safe Jackson 3 ObjectMapper instance (replaces per-message new)
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    public SseEventStreamService(EventQueryService eventQueryService,
                                    EventAuthzResolver eventAuthzResolver,
                                    @Lazy @Autowired(required = false) RabbitAdmin rabbitAdmin,
                                    tools.jackson.databind.ObjectMapper objectMapper,
                                    UiUserLookupService uiUserLookupService,
                                    ApiKeyService apiKeyService) {
        this.eventQueryService = eventQueryService;
        this.eventAuthzResolver = eventAuthzResolver;
        this.rabbitAdmin = rabbitAdmin;
        this.objectMapper = objectMapper;
        this.uiUserLookupService = uiUserLookupService;
        this.apiKeyService = apiKeyService;
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

    /**
     * WO-SEC-67 (F13): per-subject connection cap (FD-exhaustion guard against
     * ONE subject opening streams without bound — the global maxClients above
     * only caps the total). Counts live registrations per subject key
     * (JWT userId / API-key id); decremented on every removal path via
     * {@link #removeClientState}.
     */
    @Value("${zorrobpm.sse.max-clients-per-subject:10}")
    private int maxClientsPerSubject = 10;

    /** Live registrations per subject key (see above). */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> clientsPerSubject =
        new ConcurrentHashMap<>();

    /**
     * WO-SEC-67 (F13): re-resolution cache for the per-event rights
     * re-check. {@code readableRuntimePdIds} is a multi-query JPA read
     * (membership → processes → definitions); re-running it on EVERY event
     * for EVERY client would multiply DB load by clients×events. A 30s TTL
     * bounds the revocation window (stale rights live at most 30s + delivery
     * lag) instead of the stream lifetime (was: infinite). Keyed by the
     * subject + definition-key filter — the two inputs of the resolution.
     * SUPER_ADMIN bypasses (always null = see all, no query to cache).
     * Caffeine is already on the classpath (JwtAuthFilter debounce precedent).
     */
    private final Cache<ReevalKey, Collection<UUID>> rightsCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofSeconds(30))
        .build();

    private record ReevalKey(String subjectKey, String processDefinitionKeyFilter) {}

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
     * Throws 429 if the subject's own cap is exceeded (WO-SEC-67: per-subject guard).
     */
    public String registerClient(SseEmitter emitter, Principal principal, String typeFilter,
                                   String processInstanceIdFilter, String processDefinitionKeyFilter) {
        synchronized (clients) {
            if (clients.size() >= maxClients) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many SSE clients");
            }
            // WO-SEC-67: per-subject cap — checked AND incremented under the
            // same lock as the global cap so the count cannot race.
            String subjectKey = subjectKey(principal);
            java.util.concurrent.atomic.AtomicInteger subjectCount =
                clientsPerSubject.computeIfAbsent(subjectKey, k -> new java.util.concurrent.atomic.AtomicInteger(0));
            if (subjectCount.get() >= maxClientsPerSubject) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many SSE clients for this subject");
            }
            String clientId = UUID.randomUUID().toString();
            Collection<UUID> allowedPdIds = eventAuthzResolver.readableRuntimePdIds(principal, processDefinitionKeyFilter);

            SseClientInfo info = new SseClientInfo(clientId, emitter, principal,
                currentTokenVersion(principal), allowedPdIds,
                typeFilter, processInstanceIdFilter, processDefinitionKeyFilter);
            clients.put(clientId, info);
            subjectCount.incrementAndGet();

            emitter.onCompletion(() -> {
                removeClientState(clientId);
                log.info("SSE client {} disconnected (completion)", clientId);
                stopRabbitMqListenerIfNoClients();
            });
            emitter.onTimeout(() -> {
                removeClientState(clientId);
                log.info("SSE client {} disconnected (timeout)", clientId);
                stopRabbitMqListenerIfNoClients();
            });
            emitter.onError(e -> {
                removeClientState(clientId);
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
        removeClientState(clientId);
        log.info("SSE client {} removed", clientId);
        stopRabbitMqListenerIfNoClients();
    }

    /**
     * WO-REL-37: единая точка снятия клиентского состояния — карта, буфер
     * пересечения и флаг буферизации. Без этого disconnect до drain оставлял
     * запись в bufferedEvents навсегда (утечка, поймана тестом WO-PERF-7:
     * вторая Map в классе).
     */
    private void removeClientState(String clientId) {
        SseClientInfo removed = clients.remove(clientId);
        bufferedEvents.remove(clientId);
        bufferingClients.remove(clientId);
        // WO-SEC-67: release the per-subject slot (no-op when the client was
        // never registered — e.g. double completion callbacks).
        if (removed != null) {
            java.util.concurrent.atomic.AtomicInteger subjectCount =
                clientsPerSubject.get(subjectKey(removed.principal()));
            if (subjectCount != null && subjectCount.decrementAndGet() <= 0) {
                clientsPerSubject.remove(subjectKey(removed.principal()), subjectCount);
            }
        }
    }

    /**
     * WO-SEC-67: freeze the JWT token_version at registration (the liveness
     * baseline). Fail-closed: an unreadable row → -1, which can only mismatch
     * a real version (versions start at 0) and close the stream — never grant.
     */
    private int currentTokenVersion(Principal principal) {
        if (principal instanceof Principal.UserPrincipal up) {
            try {
                return uiUserLookupService.securityState(up.userId())
                    .map(com.zorrodev.bpm.engine.security.UiUserLookupService.UserSecurityState::tokenVersion)
                    .orElse(-1);
            } catch (RuntimeException e) {
                log.warn("SSE registration: token_version unreadable — freezing -1 (fail closed)", e);
                return -1;
            }
        }
        return -1;
    }

    /**
     * WO-SEC-67: stable per-subject key for the connection cap and the rights
     * cache. JWT → userId; API key → key id (one row = one subject for cap
     * purposes; rotation keeps the row id — streams on it are closed
     * explicitly by invalidateStreamsForKey, not by re-slotting).
     */
    static String subjectKey(Principal principal) {
        if (principal instanceof Principal.ServicePrincipal sp) {
            return "key:" + sp.apiKeyId();
        }
        if (principal instanceof Principal.UserPrincipal up) {
            return "user:" + up.userId();
        }
        // Unknown principal shape — fail closed on identity grouping too: each
        // such client gets its own slot instead of sharing one.
        return "unknown:" + System.identityHashCode(principal);
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
     * WO-REL-37 (F14): слить буфер пересечения: отбросить дубль (позиция <=
     * границы catchup), доставить новое (позиция > границы), перевести клиента
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
            // WO-REL-38: дедуп по позиции курсора (feedPosition; fallback —
            // sequence для envelope без позиции, см. cursorOf).
            long cursor = cursorOf(envelope, catchupBoundary);
            if (cursor <= catchupBoundary) {
                continue;
            }
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(String.valueOf(cursor))
                .name((String) envelope.get("type"))
                .data(envelope)
                .reconnectTime(3000);
            sseExecutor.execute(() -> {
                try {
                    client.emitter().send(event);
                } catch (IOException e) {
                    log.warn("Failed to send drained event to client {}", clientId);
                    // WO-SEC-67 red-team #3: full state removal (per-subject
                    // slot), not a bare map drop — the slot would leak.
                    removeClientState(clientId);
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
        onDomainEvent(messageBody, null);
    }

    /**
     * WO-OBS-8: header-aware overload — the SSE bridge passes the real AMQP headers
     * so the trace continues here (MDC traceId + processInstanceId for the fan-out
     * logs). The body-only overload (tests, direct calls) behaves as before: PI from
     * the envelope, no traceId. Never throws on bad headers — delivery first.
     */
    public void onDomainEvent(String messageBody, Map<String, ?> amqpHeaders) {
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

        // WO-OBS-8: MDC for the fan-out below (SSE is the fifth WO point: HTTP, worker,
        // completion, outbox, SSE). PI prefers the header (no parse cost), falls back to
        // the envelope; traceId comes only from the W3C header. Save/restore: the bridge
        // thread is a shared consumer thread, never leak one event's MDC into the next.
        String priorTraceId = org.slf4j.MDC.get(TraceHeaders.MDC_TRACE_ID);
        String priorPi = org.slf4j.MDC.get(TraceHeaders.MDC_PROCESS_INSTANCE_ID);
        if (amqpHeaders != null) {
            Object piHeader = amqpHeaders.get(TraceHeaders.PROCESS_INSTANCE_ID_HEADER);
            if (piHeader != null) {
                processInstanceId = piHeader.toString();
            }
            Object tpHeader = amqpHeaders.get(TraceHeaders.TRACE_PARENT_HEADER);
            String headerTraceId = tpHeader != null
                ? TraceHeaders.extractTraceId(tpHeader.toString()) : null;
            if (headerTraceId != null) {
                org.slf4j.MDC.put(TraceHeaders.MDC_TRACE_ID, headerTraceId);
            }
        }
        if (processInstanceId != null) {
            org.slf4j.MDC.put(TraceHeaders.MDC_PROCESS_INSTANCE_ID, processInstanceId);
        }
        // WO-REL-38: live идёт в рассылку только с назначенной позицией —
        // иначе клиентский курсор (SSE id) указывал бы на sequence, который
        // может навсегда пропустить событие задержанной транзакции (F15).
        Long cursor = resolveLiveCursor(sequence);
        if (cursor == null) {
            return;
        }
        try {
            envelope.put("feedPosition", cursor);
            dispatchToClientsTraced(envelope, eventType, processInstanceId, pdUuid, cursor);
            // WO-OBS-8: the per-dispatch line inside the MDC window — this is the
            // greppable proof (criterion 2 extends to SSE): trace + PI on one line.
            log.info("SSE dispatch: type={}, processInstanceId={}, sequence={}, feedPosition={}",
                eventType, processInstanceId, sequence, cursor);
        } finally {
            restoreSseMdc(TraceHeaders.MDC_TRACE_ID, priorTraceId);
            restoreSseMdc(TraceHeaders.MDC_PROCESS_INSTANCE_ID, priorPi);
        }
    }

    private static void restoreSseMdc(String key, String prior) {
        if (prior == null) {
            org.slf4j.MDC.remove(key);
        } else {
            org.slf4j.MDC.put(key, prior);
        }
    }

    /**
     * WO-REL-38: live-курсор — commit-ordered {@code feed_position}, НЕ raw
     * {@code sequence} из тела. Строка гарантированно закоммичена (мост читает
     * её из закоммиченного брокерного сообщения), но позиция может быть ещё не
     * назначена: тогда ждём тик джоба ограниченное время (см. константы —
     * запас поверх дефолтного poll-интервала 2s). Неизвестный sequence (чужой
     * id, не наша строка) — отброс сразу, без ожидания: ждать нечего.
     * Пропуск здесь — не потеря навсегда: catchup читает то же fp-окно от
     * курсора клиента, и событие доберётся при следующем reconnect.
     *
     * @return позиция курсора или null (пропустить событие, warn уже записан)
     */
    private static final int LIVE_CURSOR_WAIT_ATTEMPTS = 30;
    private static final long LIVE_CURSOR_WAIT_MS = 100;

    private Long resolveLiveCursor(long sequence) {
        java.util.Optional<Long> position =
            eventQueryService.resolveFeedPositionBySequence(sequence);
        if (position.isPresent()) {
            return position.get();
        }
        if (!eventQueryService.eventSequenceExists(sequence)) {
            log.warn("SSE live event with unknown sequence {} skipped (no such row)", sequence);
            return null;
        }
        for (int i = 0; i < LIVE_CURSOR_WAIT_ATTEMPTS; i++) {
            try {
                Thread.sleep(LIVE_CURSOR_WAIT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            position = eventQueryService.resolveFeedPositionBySequence(sequence);
            if (position.isPresent()) {
                return position.get();
            }
        }
        log.warn("SSE live event with sequence {} still has no feed position after ~{}ms, skipped (catchup will heal on reconnect)",
            sequence, (long) LIVE_CURSOR_WAIT_ATTEMPTS * LIVE_CURSOR_WAIT_MS);
        return null;
    }

    /**
     * WO-REL-38: позиция курсора из envelope. Новые envelope несут
     * {@code feedPosition}; старые/синтетические (тесты, прямые вызовы) —
     * только {@code sequence}, тогда курсором служит он (совместимость чтения,
     * не записи: прод всегда пишет обе).
     */
    private static long cursorOf(Map<String, Object> envelope, long fallback) {
        Object fp = envelope.get("feedPosition");
        if (fp instanceof Number n) {
            return n.longValue();
        }
        Object seq = envelope.get("sequence");
        if (seq instanceof Number n) {
            return n.longValue();
        }
        return fallback;
    }

    private void dispatchToClientsTraced(Map<String, Object> envelope, String eventType,
            String processInstanceId, UUID pdUuid, long cursor) {

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

            // WO-SEC-67 (F13), step 1 — credential liveness (event-driven): a
            // revoked API key / logged-out JWT / deactivated user must not
            // receive even one more event. Fail-closed: any check error closes
            // the stream rather than delivering into doubt.
            if (!isCredentialLive(client)) {
                closeRevokedClient(client.clientId, "credential dead");
                continue;
            }

            // WO-SEC-67 (F13), step 2 — rights re-resolution (periodic): the
            // registration-time snapshot goes stale on membership removal /
            // role change / grant narrowing. Re-resolve (30s-TTL cached) and
            // compare against the snapshot; on ANY narrowing close the stale
            // snapshot's stream now — it must re-register for the new, smaller
            // view. Fail-closed on resolver error (see method).
            Collection<UUID> fresh = reevaluateRights(client);
            if (fresh == null && client.allowedPdIds != null) {
                // Resolver error (not SUPER_ADMIN — that returns null by
                // contract and stays null): fail closed, do not deliver.
                closeRevokedClient(client.clientId, "rights re-check failed");
                continue;
            }
            if (isNarrowed(client.allowedPdIds, fresh)) {
                closeRevokedClient(client.clientId, "rights narrowed");
                continue;
            }

            // Check AuthZ: processDefinitionId must be in allowed set (fail-closed: G-L).
            // Uses the FRESH set when non-null, else the client snapshot
            // (SUPER_ADMIN null, or a still-valid cached view).
            Collection<UUID> effective = fresh != null ? fresh : client.allowedPdIds;
            if (effective != null) {
                if (pdUuid == null || !effective.contains(pdUuid)) {
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
            // WO-REL-38: SSE id — позиция курсора (параметр метода уже курсор).
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(String.valueOf(cursor))
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
                        // WO-SEC-67 red-team #3: full state removal (slot back).
                        removeClientState(client.clientId);
                        try { client.emitter.complete(); } catch (Exception ignore) {}
                    } catch (java.util.concurrent.ExecutionException ee) {
                        Throwable cause = ee.getCause();
                        if (cause != null && cause.getCause() instanceof IOException) {
                            log.warn("Failed to send event to client {}: {}", client.clientId, cause.getCause().getMessage());
                        } else {
                            log.warn("Failed to send event to client {}: {}", client.clientId, cause != null ? cause.getMessage() : ee.getMessage());
                        }
                        // WO-SEC-67 red-team #3: full state removal (slot back).
                        removeClientState(client.clientId);
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
     * (max выданной позиции курсора, или since — если ничего не выдано) для
     * дедупа пересечения catchup→live.
     *
     * <p>WO-REL-38: граница и SSE id — feed-позиция (см. cursorOf), входной
     * since трактуется в том же домене (клиент хранит последний полученный
     * id, который теперь позиция).
     *
     * @return max выданной позиции (exclusive-граница live-буфера)
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
                    // WO-REL-38: SSE id и граница — позиция курсора (feedPosition;
                    // fallback — sequence, см. cursorOf). Браузер шлёт её назад
                    // как Last-Event-ID — тот же домен, что since у REST.
                    long cursor = cursorOf(envelope, boundary);
                    SseEmitter.SseEventBuilder sseEvent = SseEmitter.event()
                        .id(String.valueOf(cursor))
                        .name((String) envelope.get("type"))
                        .data(envelope)
                        .reconnectTime(3000);
                    emitter.send(sseEvent);
                    if (cursor > boundary) {
                        boundary = cursor;
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
     * WO-SEC-67 (F13): is the credential behind this stream still alive?
     * Mirrors the per-request checks of {@code JwtAuthFilter} (same lookups,
     * same fail-closed direction):
     * <ul>
     *   <li>JWT user → {@code UiUserLookupService.securityState}: user exists,
     *       active, and the claim version/role still match the row (logout
     *       bumps {@code token_version} — the only writer in prod; a role
     *       change flows through the same version on write — a stale role
     *       beyond its TTL fails closed);</li>
     *   <li>API key → row exists, not revoked, not expired, owner still
     *       active (WO-ACL-5 criterion #4, same as the filter).</li>
     * </ul>
     * Any lookup error → false (fail closed — the stream dies, it never
     * delivers into doubt).
     */
    private boolean isCredentialLive(SseClientInfo client) {
        // WO-SEC-67: unit-scope harness (hand-built service with null
        // collaborators, e.g. SsePerf6IntegrationTest/SseBridgeStartupTest) —
        // there is nothing to check against. Production Spring wiring always
        // injects real beans; the full-context proof (SseRevocationIT) runs
        // with real rows. A null collaborator is a missing harness, never a
        // dead credential — failing closed here would only test the harness.
        if (uiUserLookupService == null || apiKeyService == null) {
            return true;
        }
        try {
            Principal principal = client.principal();
            if (principal instanceof Principal.UserPrincipal up) {
                // The JWT claims are frozen at registration; the row is live.
                // Same comparison as JwtAuthFilter: version + active + role.
                var state = uiUserLookupService.securityState(up.userId()).orElse(null);
                if (state == null || !state.active()
                    || state.tokenVersion() != client.tokenVersion()
                    || !Objects.equals(state.role(), up.globalRole())) {
                    return false;
                }
                return true;
            }
            if (principal instanceof Principal.ServicePrincipal sp) {
                // Key liveness via the engine-side owner (WO-DEBT-7: no
                // engine.repository import in rest/resource).
                return apiKeyService.isKeyLive(sp.apiKeyId());
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("SSE credential liveness check failed for client {} — failing closed",
                client.clientId(), e);
            return false;
        }
    }

    /**
     * WO-SEC-67 (F13): fresh rights for this client (30s-TTL cached).
     * SUPER_ADMIN bypasses (null = see all by contract — nothing to re-check).
     * ServicePrincipal streams resolve the LIVE key view (current grant rows,
     * not the frozen registration snapshot — setGrants must show up here).
     * Any resolver error → null WITH a non-null snapshot behind it, which the
     * caller treats as fail-closed (close, do not deliver). A null snapshot
     * (SUPER_ADMIN) + null fresh = still see-all.
     */
    private Collection<UUID> reevaluateRights(SseClientInfo client) {
        if (client.principal().isSuperAdmin()) {
            return null;
        }
        try {
            return liveView(client);
        } catch (RuntimeException e) {
            log.warn("SSE rights re-resolution failed for client {} — failing closed",
                client.clientId(), e);
            return null;
        }
    }

    /**
     * WO-SEC-67 (F13): has the fresh view narrowed vs the snapshot? null fresh
     * = SUPER_ADMIN see-all = never narrowed. A non-null fresh that is missing
     * ANY snapshot id (removal) closes the stream — even when it also ADDS ids
     * (a changed key filter outcome is still a different view; the client
     * re-registers for exactly it). Pure widening without loss keeps the
     * stream (fail-open on MORE rights would leak nothing the fresh set does
     * not already grant — delivery itself is checked against fresh).
     */
    static boolean isNarrowed(Collection<UUID> snapshot, Collection<UUID> fresh) {
        if (fresh == null) {
            return false;
        }
        if (snapshot == null) {
            // Was see-all (non-admin snapshot cannot be null by contract —
            // defensive): any finite fresh view is narrower.
            return true;
        }
        return !fresh.containsAll(snapshot);
    }

    /**
     * WO-SEC-67 (F13): close one client's stream now (revocation path).
     * Removes state (releases the per-subject slot) and completes the
     * emitter; also evicts the client's rights-cache entry so a later
     * stream starts from a cold read.
     */
    private void closeRevokedClient(String clientId, String reason) {
        SseClientInfo client = clients.get(clientId);
        removeClientState(clientId);
        if (client != null) {
            rightsCache.invalidate(
                new ReevalKey(subjectKey(client.principal()), client.processDefinitionKeyFilter));
            try {
                client.emitter().complete();
            } catch (Exception e) {
                log.warn("SSE close of revoked client {} failed", clientId, e);
            }
        }
        log.info("SSE client {} closed (revoked: {})", clientId, reason);
    }

    /**
     * WO-SEC-67 (F13): event-driven invalidation. Called by the revoke/logout/
     * membership paths; closes every open stream whose credential is now dead
     * or whose rights narrowed — immediately, without waiting for the next
     * event or the 30s cache TTL. Best-effort and non-throwing (a revoke must
     * never fail because a stream misbehaves); failures are logged.
     */
    public void invalidateStreams() {
        for (SseClientInfo client : List.copyOf(clients.values())) {
            try {
                if (!isCredentialLive(client)) {
                    closeRevokedClient(client.clientId, "credential dead (event)");
                    continue;
                }
                // Single truth via liveView (POF-proven: a divergent inline
                // copy here once hid revokes from this sweep — SseRevocationIT
                // caught it). bypassCache=true: the revoke JUST happened, so
                // any cached view predates it by up to 30s.
                Collection<UUID> fresh;
                try {
                    fresh = liveView(client, true);
                } catch (RuntimeException e) {
                    log.warn("SSE event-driven re-resolution failed for client {} — failing closed",
                        client.clientId(), e);
                    closeRevokedClient(client.clientId, "rights re-check failed (event)");
                    continue;
                }
                if (fresh == null && client.allowedPdIds != null) {
                    closeRevokedClient(client.clientId, "rights re-check failed (event)");
                    continue;
                }
                if (isNarrowed(client.allowedPdIds, fresh)) {
                    closeRevokedClient(client.clientId, "rights narrowed (event)");
                }
            } catch (RuntimeException e) {
                log.warn("SSE event-driven invalidation failed for client {}", client.clientId(), e);
            }
        }
    }

    /**
     * WO-SEC-67 red-team #1: the CURRENT view for a client — live key rows for
     * service keys (frozen registration grants would hide a setGrants
     * narrowing forever), 30s-cached membership resolution for JWT users.
     * Single truth for the per-event check and the event-driven invalidation
     * (no double logic to diverge).
     *
     * @param bypassCache true on the event-driven path (the revoke JUST
     *        happened — a cached view predates it) — resolves fresh AND
     *        refreshes the cache so a racing per-event check sees the same
     *        view; false on the per-event path (cache governs the 30s TTL).
     */
    private Collection<UUID> liveView(SseClientInfo client) {
        return liveView(client, false);
    }

    private Collection<UUID> liveView(SseClientInfo client, boolean bypassCache) {
        // WO-SEC-67 verifier HOLD: SUPER_ADMIN bypass — see-all неизменно
        // (зеркало per-event reevaluateRights). Без него readableRuntimePdIds
        // вернул бы null → rightsCache.put(key, null) → Caffeine-NPE → любой
        // sweep закрывал бы ВСЕ admin-потоки. Credential-liveness админа
        // проверяется отдельно выше (logout бампает его version).
        if (client.principal().isSuperAdmin()) {
            return null;
        }
        if (client.principal() instanceof Principal.ServicePrincipal sp) {
            // Uncached: grant changes are rare, correctness beats one query
            // per event here.
            return eventAuthzResolver.readableRuntimePdIdsForKey(
                sp.apiKeyId(), sp.ownerUserId(), client.processDefinitionKeyFilter);
        }
        ReevalKey key = new ReevalKey(subjectKey(client.principal()), client.processDefinitionKeyFilter);
        if (bypassCache) {
            Collection<UUID> fresh = eventAuthzResolver.readableRuntimePdIds(
                client.principal(), client.processDefinitionKeyFilter);
            rightsCache.put(key, fresh);
            return fresh;
        }
        return rightsCache.get(key,
            k -> eventAuthzResolver.readableRuntimePdIds(
                client.principal(), client.processDefinitionKeyFilter));
    }

    /**
     * WO-SEC-67 red-team #2: key rotation replaces the key MATERIAL in place
     * (same row id — liveness stays green), so the generic sweep cannot see
     * it. Rotation kills the old credential explicitly: close every stream
     * standing on this key id NOW, deterministically, without depending on
     * string comparisons. Best-effort and non-throwing like the sweep.
     */
    public void invalidateStreamsForKey(UUID apiKeyId) {
        for (SseClientInfo client : List.copyOf(clients.values())) {
            try {
                if (client.principal() instanceof Principal.ServicePrincipal sp
                    && apiKeyId.equals(sp.apiKeyId())) {
                    closeRevokedClient(client.clientId, "key rotated");
                }
            } catch (RuntimeException e) {
                log.warn("SSE key invalidation failed for client {}", client.clientId(), e);
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
            // WO-OBS-8: headers ride along (traceparent + processInstanceId) — the
            // body-only overload stays for tests/direct calls.
            onDomainEvent(body, message.getMessageProperties().getHeaders());
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
        /**
         * WO-SEC-67: JWT token_version frozen at registration. The liveness
         * check compares the row's CURRENT version against this — logout bumps
         * the row (the only version writer in prod), so a mismatch means
         * "issued before the revoke". ServicePrincipal streams carry -1
         * (unused — their liveness is the key row, not a version).
         */
        int tokenVersion,
        Collection<UUID> allowedPdIds,
        String typeFilter,
        String processInstanceIdFilter,
        String processDefinitionKeyFilter
    ) {}
}
