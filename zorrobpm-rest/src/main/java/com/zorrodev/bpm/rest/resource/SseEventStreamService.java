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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SSE bridge: subscribes to zorrobpm.events (exclusive queue per instance)
 * and pushes events to connected SSE clients with AuthZ filtering (ADR-7, WO-EVT-4).
 *
 * WO-PERF-6 (P-3): per-client executor, timeout+drop, maxClients→429,
 * cursor+limit catchup, UUID.fromString hoisted.
 *
 * WO-REL-47 (N07+N08): single writer protocol per client. One bounded queue
 * and one serialized pump per client (mode + queue inside the client value,
 * transitions under the client's own lock — no split flag/map that can
 * diverge); two bounded pools (dispatch micro-tasks + blocking sends, the
 * send wait is a non-blocking orTimeout callback, never a pooled thread);
 * per-client queue cap with drop-newest overflow; lagging clients are closed
 * on send timeout/failure with cursor catchup on reconnect.
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

    /**
     * WO-REL-47 (N07): BOUNDED pools — never a cached pool on this path.
     *
     * <p>Two lanes because slow clients block for real (socket write until
     * timeout/close) while scheduling must stay cheap:
     * <ul>
     *   <li>{@code dispatchExecutor} — micro-tasks only (enqueue check, pump
     *       poll + async-send submit, completion callbacks). Never blocks: the
     *       send wait below is an {@code orTimeout} callback, not a pooled
     *       thread. Small and fixed.</li>
     *   <li>{@code sendExecutor} — the blocking {@code emitter.send()} calls,
     *       at most one in flight per client (the per-client pump submits the
     *       next send only after the previous completes). Caps the number of
     *       threads a slow-client fleet can pin; excess submits reject and the
     *       event stays queued for a later pump kick (preserved, not dropped).
     *       </li>
     * </ul>
     * Both use a handoff (zero-capacity) queue + AbortPolicy: under overload
     * submissions reject instead of queueing unboundedly (the pre-REL-47 shape
     * — an outer task per event waiting on an inner future in the SAME cached
     * pool — let threads grow as events × clients × send duration).
     *
     * <p>A rejected pump kick / send submit never strands a client silently
     * (WO-REL-47 HOLD finding 1): both rejection sites re-arm through a single
     * bounded retry lane — a shared daemon {@code ScheduledExecutorService}
     * (one thread, 60s idle eviction like the lanes) that retries the pump at
     * fixed 100ms intervals, at most {@value #PUMP_RETRY_MAX_ATTEMPTS} times
     * per arming. Bounded retries (not an unbounded timer per rejection) keep
     * the retry state at O(live clients), and the retry is a no-op for a
     * client whose queue already drained or whose writer closed.
     *
     * <p>Lanes are (re)created lazily and never assumed live (fields below):
     * Spring 7 pauses an idle test context on switch
     * ({@code DefaultContextCache.pauseOnContextSwitchIfNecessary} →
     * {@code SmartLifecycle.stop()}) and resumes it later via
     * {@code start()} — a stop that kills the pools FOREVER leaves a
     * resumed context deaf (events queue, pump rejects, silence). So
     * {@link #start()} re-creates terminated lanes, and every submit path
     * goes through these getters (self-heal even if {@code start()} was
     * missed). Daemon threads + 60s keep-alive: a recreated-but-unused
     * pool evaporates on its own (core threads included — see
     * {@link #boundedPool}, which enables core-thread timeout).
     */

    private static ExecutorService boundedPool(String prefix, int maxThreads,
            java.util.concurrent.BlockingQueue<Runnable> workQueue) {
        java.util.concurrent.ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName(prefix + t.getId());
            t.setDaemon(true);
            return t;
        };
        java.util.concurrent.ThreadPoolExecutor pool =
            new java.util.concurrent.ThreadPoolExecutor(
                maxThreads, maxThreads, 60L, TimeUnit.SECONDS,
                workQueue, factory,
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        // WO-REL-47 HOLD finding 3: core == max makes the 60s keepAlive dead
        // by construction (it only reaps threads ABOVE core) — without this
        // call the javadoc's "evaporates on its own" is false and a leaked
        // pool (finding 2 race) pins up to 128 threads for the JVM lifetime.
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * WO-REL-47: lanes are (re)created lazily and never assumed live.
     * Spring 7 pauses an idle test context on switch
     * ({@code DefaultContextCache.pauseOnContextSwitchIfNecessary} →
     * {@code SmartLifecycle.stop()}) and resumes it later via
     * {@code start()} — a stop that kills the pools FOREVER leaves a
     * resumed context deaf (events queue, pump rejects, silence). So
     * {@link #start()} re-creates terminated lanes, and every submit path
     * goes through these getters (self-heal even if {@code start()} was
     * missed). Daemon threads + 60s keep-alive: a recreated-but-unused
     * pool evaporates on its own (core threads included — see
     * {@link #boundedPool}, which enables core-thread timeout).
     */
    private volatile ExecutorService dispatchExecutor;
    private volatile ExecutorService sendExecutor;

    /**
     * WO-REL-47 HOLD finding 1: bounded retry lane for rejected pump kicks.
     * One shared daemon scheduler (single thread) retries {@link SseClientInfo#pump}
     * at fixed 100ms intervals, at most {@value #PUMP_RETRY_MAX_ATTEMPTS}
     * attempts per arming. Bounded per-arming retries keep retry state at
     * O(live clients) even under sustained saturation; each attempt re-checks
     * mode/queue under the client lock, so a drained or closed client costs
     * one no-op. Created lazily under the same lifecycle protocol as the
     * lanes (see {@link #retryLane}); shut down under the same lock by
     * {@link #stop}.
     */
    private volatile java.util.concurrent.ScheduledExecutorService retryScheduler;

    /** Fixed delay between pump-retry attempts (test-shrinkable). */
    private volatile long pumpRetryDelayMs = 100L;

    /** Max retry attempts per arming (100ms × 50 = ~5s, one send-timeout window). */
    private static final int PUMP_RETRY_MAX_ATTEMPTS = 50;

    private synchronized java.util.concurrent.ScheduledExecutorService retryLane() {
        java.util.concurrent.ScheduledExecutorService lane = retryScheduler;
        if (lane == null || lane.isShutdown()) {
            java.util.concurrent.ThreadFactory factory = r -> {
                Thread t = new Thread(r);
                t.setName("sse-retry-" + t.getId());
                t.setDaemon(true);
                return t;
            };
            java.util.concurrent.ScheduledThreadPoolExecutor fresh =
                new java.util.concurrent.ScheduledThreadPoolExecutor(1, factory);
            fresh.setRemoveOnCancelPolicy(true);
            fresh.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            fresh.allowCoreThreadTimeOut(true);
            fresh.setKeepAliveTime(60L, TimeUnit.SECONDS);
            retryScheduler = fresh;
            lane = fresh;
        }
        return lane;
    }

    private synchronized ExecutorService dispatchLane() {
        ExecutorService lane = dispatchExecutor;
        if (lane == null || lane.isShutdown()) {
            lane = boundedPool("sse-dispatch-", Math.max(8,
                Runtime.getRuntime().availableProcessors()),
                new java.util.concurrent.LinkedBlockingQueue<>(1024));
            dispatchExecutor = lane;
        }
        return lane;
    }

    private synchronized ExecutorService sendLane() {
        ExecutorService lane = sendExecutor;
        if (lane == null || lane.isShutdown()) {
            lane = boundedPool("sse-send-", 128,
                new java.util.concurrent.SynchronousQueue<>());
            sendExecutor = lane;
        }
        return lane;
    }

    @Value("${zorrobpm.sse.max-clients:1000}")
    private int maxClients = 1000;

    @Value("${zorrobpm.sse.send-timeout-ms:5000}")
    private long sendTimeoutMs = 5000;

    /**
     * WO-REL-47 (N07): per-client bounded queue cap (events). The writer
     * protocol drops the NEWEST event on overflow (survivors keep cursor
     * order) and counts it — memory per client is capped no matter how slow
     * the socket is or how fast the event flow runs.
     */
    @Value("${zorrobpm.sse.per-client-queue-events:1000}")
    private int perClientQueueEvents = 1000;

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
        String clientId = registerClientInternal(emitter, principal, typeFilter,
            processInstanceIdFilter, processDefinitionKeyFilter);
        // WO-REL-47: plain registration has no catchup window — the client
        // goes LIVE immediately (boundary 0 drops nothing: real cursors
        // are positive feed positions). Born BUFFERING + drained here
        // (instead of born LIVE) so an event arriving between the map-put
        // and this line stages in the queue and is picked up by the
        // drain — never sent ahead of the subscription contract.
        SseClientInfo live = clients.get(clientId);
        if (live != null) {
            live.drainToLive(0L);
        }
        return clientId;
    }

    private String registerClientInternal(SseEmitter emitter, Principal principal, String typeFilter,
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
     * WO-REL-37: единая точка снятия клиентского состояния. WO-REL-47: буфер
     * пересечения и режим живут ВНУТРИ значения клиента (его writer), а не в
     * отдельных мапах — снимать нечего, кроме самой записи (disconnect до
     * drain больше не может оставить висячую queue: негде). Без этого
     * disconnect до drain оставлял запись в bufferedEvents навсегда (утечка,
     * поймана тестом WO-PERF-7: вторая Map в классе).
     *
     * <p>WO-REL-47 HOLD finding 4: снятие состояния обязано закрывать и сам
     * writer ({@code closeWriter()} → {@code Mode.CLOSED}): иначе уже
     * запущенный pump/send для отозванного или отключившегося клиента позже
     * зовёт {@code emitter.send()} на уже complete()-нутом emitter →
     * {@code IllegalStateException} в misleading-ветке «send error» вместо
     * тихого no-op.
     */
    private void removeClientState(String clientId) {
        SseClientInfo removed = clients.remove(clientId);
        if (removed != null) {
            removed.closeWriter();
        }
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
     * WO-REL-47: клиент рождается в режиме BUFFERING — его события копятся в
     * его собственной очереди (не шлются), пока контроллер не вызовет
     * {@link #drainBufferedClient} с границей catchup: события с cursor <=
     * границы отбрасываются (дубль catchup), новее — доставляются. Режим и
     * очередь — одно значение под одним локом (см. writer-протокол в
     * {@link SseClientInfo}): окно потери между catchup-чтением и подпиской
     * закрыто конструктивно — событие, пришедшее до drain, физически негде
     * потерять, кроме самой очереди клиента.
     *
     * @return clientId для {@link #drainBufferedClient}
     */
    public String registerBufferedClient(SseEmitter emitter, Principal principal, String typeFilter,
                                    String processInstanceIdFilter, String processDefinitionKeyFilter) {
        // WO-REL-47: buffered registration does NOT drain — the client stays
        // BUFFERING (born that way) until drainBufferedClient. A shared
        // internal with registerClient would go LIVE here and the catchup
        // window (WO-REL-37) would send ahead of the subscription contract.
        String clientId = registerClientInternal(emitter, principal, typeFilter,
            processInstanceIdFilter, processDefinitionKeyFilter);
        SseClientInfo client = clients.get(clientId);
        if (client != null) {
            // Режим уже BUFFERING с конструктора — вызов для явности
            // протокола (idempotent: BUFFERING→BUFFERING — no-op).
            client.setBuffering();
        }
        return clientId;
    }

    /**
     * WO-REL-37 (F14): слить буфер пересечения: отбросить дубль (позиция <=
     * границы catchup), доставить новое (позиция > границы), перевести клиента
     * в обычный live-режим.
     *
     * WO-REL-47: переход BUFFERING→LIVE и решение «дубль/новое» — под локом
     * клиента, одним шагом: producer, пришедший одновременно, либо видит
     * BUFFERING и кладёт событие в очередь ДО перехода (тогда drain его видит
     * и решает), либо видит LIVE и шлёт напрямую — третьего исхода нет, окно
     * потери между «снять queue» и «снять флаг» закрыто (старого двухшагового
     * снятия больше нет — флага нет вовсе).
     */
    public void drainBufferedClient(String clientId, long catchupBoundary) {
        SseClientInfo client = clients.get(clientId);
        if (client == null) {
            return;
        }
        client.drainToLive(catchupBoundary);
    }

    /**
     * Called when a domain event arrives from RabbitMQ.
     * Pushes to all connected clients that match the filter and AuthZ.
     * Runs on RabbitMQ consumer thread — fan-out is offloaded to the bounded
     * dispatch lane so one slow client never blocks the others (WO-PERF-6).
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

        // WO-PERF-6: hoist UUID.fromString outside the per-client loop.
        // WO-REL-52 (verifier HOLD-1): резолвинг ОДИН на оба пути (live и
        // deferred, см. resolveEventPdUuid) — невалидный UUID fail-closed
        // в обоих, валидный доставляется обоим.
        UUID pdUuid = resolveEventPdUuid(processDefinitionId);
        if (processDefinitionId != null && pdUuid == null) {
            // fail-closed: no client matches an unparsable pdId
            return;
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
        // WO-REL-52 (NEW-03, A3): ожидание позиции НЕ спит в consumer-потоке
        // моста (старые 30×sleep(100) сериализовали ВЕСЬ мост за одним
        // медленным событием). Позиция есть сразу — рассылаем; нет, но ряд
        // существует — откладываем событие в retry-lane (тот же bounded
        // retry-механизм REL-47, не новый пул): consumer-поток свободен для
        // следующих событий, отложенное рассылается позже с той же семантикой
        // (позиция/отброс/лог — в dispatchDeferred, построчно та же). Ряда
        // нет вообще — отброс сразу (ждать нечего), как раньше.
        Long cursor = resolveLiveCursor(sequence);
        if (cursor == null) {
            if (eventQueryService.eventSequenceExists(sequence)) {
                deferUnpositioned(messageBody, amqpHeaders, sequence, 1);
            }
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
     * назначена.
     *
     * <p>WO-REL-52 (A3): этот метод НЕ ждёт — один неблокирующий read. Ожидание
     * переехало в {@link #deferUnpositioned} (retry-lane, вне consumer-потока):
     * спать 30×100мс в единственном consumer'е моста означало сериализовать
     * весь live-поток за одним медленным событием. Неизвестный sequence
     * (чужой id, не наша строка) — null сразу, без ожидания: ждать нечего.
     * Пропуск здесь — не потеря навсегда: catchup читает то же fp-окно от
     * курсора клиента, и событие доберётся при следующем reconnect (а для
     * существующего ряда — через отложенную рассылку, см. ниже).
     *
     * @return позиция курсора или null (позиции пока нет / ряда нет)
     */
    private Long resolveLiveCursor(long sequence) {
        return eventQueryService.resolveFeedPositionBySequence(sequence).orElse(null);
    }

    /**
     * WO-REL-52 (A3): отложенная рассылка события, чья позиция ещё не
     * назначена. Тот же retry-lane REL-47 (один daemon-поток, bounded):
     * каждая попытка — один неблокирующий read позиции; позиция появилась —
     * рассылка тем же путём, что live (envelope уже разобран? нет — тело
     * хранится сырым и разбирается заново в dispatchDeferred, чтобы MDC и
     * envelope-путь были теми же, построчно); бюджет попыток исчерпан —
     * тот же warn + пропуск, что раньше после 30×100мс (catchup вылечит при
     * reconnect). Отложенное событие НЕ блокирует consumer-поток: он вернулся
     * сразу после schedule.
     */
    private static final int DEFERRED_CURSOR_ATTEMPTS = 30;
    private static final long DEFERRED_CURSOR_DELAY_MS = 100;

    /** Тест-шринка задержки (как pumpRetryDelayMs — volatile, не финал). */
    private volatile long deferredCursorDelayMs = DEFERRED_CURSOR_DELAY_MS;

    private void deferUnpositioned(String messageBody, Map<String, ?> amqpHeaders,
            long sequence, int attempt) {
        java.util.concurrent.ScheduledExecutorService lane;
        try {
            lane = retryLane();
        } catch (java.util.concurrent.RejectedExecutionException re) {
            log.warn("SSE deferred dispatch saturated, dropping unpositioned sequence {} (catchup will heal)", sequence);
            return;
        }
        // Копия заголовков: исходный map принадлежит listener-контейнеру и
        // может быть переиспользован; MDC ставится заново в dispatchDeferred.
        Map<String, Object> headersCopy = amqpHeaders == null ? null
            : new java.util.LinkedHashMap<>(amqpHeaders);
        lane.schedule(() -> {
            Long cursor = resolveLiveCursor(sequence);
            if (cursor != null) {
                dispatchDeferred(messageBody, headersCopy, sequence, cursor);
                return;
            }
            if (attempt >= DEFERRED_CURSOR_ATTEMPTS) {
                log.warn("SSE live event with sequence {} still has no feed position after ~{}ms, skipped (catchup will heal on reconnect)",
                    sequence, (long) DEFERRED_CURSOR_ATTEMPTS * deferredCursorDelayMs);
                return;
            }
            if (!eventQueryService.eventSequenceExists(sequence)) {
                log.warn("SSE live event with unknown sequence {} skipped (no such row)", sequence);
                return;
            }
            deferUnpositioned(messageBody, headersCopy, sequence, attempt + 1);
        }, deferredCursorDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * WO-REL-52 (verifier HOLD-1): pdUuid-резолвинг, общий для live-пути
     * ({@code onDomainEvent}) и deferred-пути ({@code dispatchDeferred}).
     * До фикса deferred-путь передавал pdUuid=null всегда — restricted-клиенты
     * (effective != null) тихо пропускали ВСЕ отложенные события, хотя
     * live-эквивалент доставлялся. Невалидный UUID — null + warn, вызывающий
     * роняет событие целиком (fail-closed, как раньше в live-пути).
     */
    private UUID resolveEventPdUuid(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        try {
            return UUID.fromString(processDefinitionId);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid processDefinitionId UUID {}", processDefinitionId);
            return null;
        }
    }

    /**
     * WO-REL-52: рассылка отложенного события — тот же путь, что live
     * (MDC-окно + envelope + cursor + dispatchToClientsTraced, построчно из
     * {@link #onDomainEvent}), только вызывается из retry-lane, а не из
     * consumer-потока. Logger-строка та же (SSE dispatch), чтобы грепы
     * наблюдаемости не различали пути.
     */
    private void dispatchDeferred(String messageBody, Map<String, ?> amqpHeaders,
            long sequence, long cursor) {
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
        // WO-REL-52 (verifier HOLD-1): тот же pdUuid-резолвинг, что в live-пути —
        // restricted-клиенты получают отложенные события, а не тихий пропуск.
        UUID pdUuid = resolveEventPdUuid(processDefinitionId);
        if (processDefinitionId != null && pdUuid == null) {
            // fail-closed: same as the live path
            return;
        }
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
        try {
            envelope.put("feedPosition", cursor);
            dispatchToClientsTraced(envelope, eventType, processInstanceId, pdUuid, cursor);
            log.info("SSE dispatch: type={}, processInstanceId={}, sequence={}, feedPosition={}",
                eventType, processInstanceId, sequence, cursor);
        } finally {
            restoreSseMdc(TraceHeaders.MDC_TRACE_ID, priorTraceId);
            restoreSseMdc(TraceHeaders.MDC_PROCESS_INSTANCE_ID, priorPi);
        }
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

    /**
     * WO-REL-38: SSE id — позиция курсора (параметр метода уже курсор).
     * WO-REL-47: построение builder'а — чистая функция без I/O (builder
     * отправляется writer'ом клиента позже, последовательно).
     */
    private static SseEmitter.SseEventBuilder buildLiveEvent(
            long cursor, String eventType, Map<String, Object> envelope) {
        return SseEmitter.event()
            .id(String.valueOf(cursor))
            .name(eventType)
            .data(envelope)
            .reconnectTime(3000);
    }

    private void dispatchToClientsTraced(Map<String, Object> envelope, String eventType,
            String processInstanceId, UUID pdUuid, long cursor) {

        // WO-REL-52 (NEW-03, part A): liveness — РАЗ на пользователя за
        // событие, а не раз на клиента. Клиенты одного principal (10 вкладок
        // одного юзера = 10 findById на каждое событие) делят один lookup:
        // первый проход группирует подходящих под фильтры клиентов по
        // subject, второй — один row-read на группу, мёртвые клиенты
        // закрываются точечно. Число SQL liveness на событие = числу
        // РАЗЛИЧНЫХ пользователей (обычно единицы), а не числу клиентов
        // (до max-clients=1000). Отзыв между событиями по-прежнему закрывает
        // поток на следующем событии (проверка на каждое событие, не кэш —
        // окно валидности отозванных прав не расширено ни на секунду сверх
        // принятого; отдельный TTL-кэш не заводился осознанно — см. отчёт).
        java.util.Map<String, java.util.List<SseClientInfo>> bySubject = null;
        if (uiUserLookupService != null && apiKeyService != null) {
            bySubject = new java.util.LinkedHashMap<>();
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

            if (bySubject != null) {
                bySubject.computeIfAbsent(subjectKey(client.principal()),
                    k -> new java.util.ArrayList<>()).add(client);
                continue;
            }

            // Unit-scope harness (null collaborators): прежний прямой путь
            // без группировки — семантика та же, делить нечего.
            if (!isCredentialLive(client)) {
                closeRevokedClient(client.clientId, "credential dead");
                continue;
            }
            deliverToClient(client, envelope, eventType, cursor, pdUuid);
        }

        if (bySubject != null) {
            for (java.util.List<SseClientInfo> group : bySubject.values()) {
                // Один row-read на группу + per-client вердикты без SQL.
                if (!closeDeadInGroup(group)) {
                    continue;
                }
                for (SseClientInfo client : group) {
                    if (clients.containsKey(client.clientId)) {
                        deliverToClient(client, envelope, eventType, cursor, pdUuid);
                    }
                }
            }
        }
    }

    /**
     * WO-REL-52: живость ГРУППЫ клиентов одного subject за один row-read.
     * JWT: строка читается ОДИН раз, затем сверяется с tokenVersion/role
     * КАЖДОГО клиента группы (версии у вкладок одного юзера могут
     * различаться — login/logout между регистрациями; общий row-read не
     * смешивает вердикты: клиент со stale-версией закрывается точечно, даже
     * если сосед свеж — один stale-token не роняет все вкладки юзера).
     * API-key: один isKeyLive на группу (ключ один — subject и есть key id;
     * версии нет). Ошибка lookup → вся группа закрывается (fail-closed,
     * как раньше каждый клиент по отдельности).
     *
     * @return true — есть кому доставлять (группа не вся мертва)
     */
    private boolean closeDeadInGroup(java.util.List<SseClientInfo> group) {
        SseClientInfo first = group.get(0);
        Principal principal = first.principal();
        try {
            if (principal instanceof Principal.UserPrincipal up) {
                var state = uiUserLookupService.securityState(up.userId()).orElse(null);
                if (state == null || !state.active()) {
                    for (SseClientInfo client : group) {
                        closeRevokedClient(client.clientId, "credential dead");
                    }
                    return false;
                }
                boolean anyLive = false;
                for (SseClientInfo client : group) {
                    Principal p = client.principal();
                    if (p instanceof Principal.UserPrincipal cpu
                        && state.tokenVersion() == client.tokenVersion()
                        && Objects.equals(state.role(), cpu.globalRole())) {
                        anyLive = true;
                    } else {
                        closeRevokedClient(client.clientId, "credential dead");
                    }
                }
                return anyLive;
            }
            if (principal instanceof Principal.ServicePrincipal) {
                if (!isPrincipalLive(principal, first.tokenVersion())) {
                    for (SseClientInfo client : group) {
                        closeRevokedClient(client.clientId, "credential dead");
                    }
                    return false;
                }
                return true;
            }
            for (SseClientInfo client : group) {
                closeRevokedClient(client.clientId, "credential dead");
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("SSE group liveness check failed for subject {} — failing closed",
                subjectKey(principal), e);
            for (SseClientInfo client : group) {
                try {
                    closeRevokedClient(client.clientId, "credential dead");
                } catch (RuntimeException ce) {
                    log.warn("SSE close of revoked client {} failed", client.clientId, ce);
                }
            }
            return false;
        }
    }

    /**
     * WO-REL-52: доставка одному клиенту (выделено из
     * {@code dispatchToClientsTraced} без смены семантики — rights
     * re-resolution, narrowing-check, authz-гейт и enqueue те же,
     * построчно).
     */
    private void deliverToClient(SseClientInfo client, Map<String, Object> envelope,
            String eventType, long cursor, UUID pdUuid) {
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
                return;
            }
            if (isNarrowed(client.allowedPdIds, fresh)) {
                closeRevokedClient(client.clientId, "rights narrowed");
                return;
            }

            // Check AuthZ: processDefinitionId must be in allowed set (fail-closed: G-L).
            // Uses the FRESH set when non-null, else the client snapshot
            // (SUPER_ADMIN null, or a still-valid cached view).
            Collection<UUID> effective = fresh != null ? fresh : client.allowedPdIds;
            if (effective != null) {
                if (pdUuid == null || !effective.contains(pdUuid)) {
                    return;
                }
            }

            // WO-REL-37 (F14) + WO-REL-47: событие — в writer клиента (в
            // BUFFERING — в очередь, в LIVE — в очередь pump'а на отправку;
            // решение под локом клиента, одним шагом — окно потери закрыто).
            // AuthZ-гейты выше (credential/rights) уже пройдены.
            client.enqueueLive(buildLiveEvent(cursor, eventType, envelope), envelope, cursor);
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
        Principal principal = client.principal();
        // WO-REL-52 (NEW-03, part A): liveness РАЗ на пользователя за
        // событие, а не раз на клиента — клиенты одного principal делят один
        // lookup (группировка перед проверкой, см. dispatchToClientsTraced).
        // Однопользовательский путь (reconnect-порог без толпы) идёт сюда
        // напрямую — семантика та же, кэширования нет (событийно-точный
        // revoke: logout между двумя событиями закрывает поток на втором).
        return isPrincipalLive(principal, client.tokenVersion());
    }

    /**
     * WO-REL-52: проверка живости ОДНОГО principal (вынесено из
     * {@code isCredentialLive} без смены семантики — те же lookups, то же
     * fail-closed направление). Пакетный путь вызывает это один раз на
     * пользователя и раздаёт результат его клиентам.
     */
    private boolean isPrincipalLive(Principal principal, int tokenVersion) {
        try {
            if (principal instanceof Principal.UserPrincipal up) {
                // The JWT claims are frozen at registration; the row is live.
                // Same comparison as JwtAuthFilter: version + active + role.
                var state = uiUserLookupService.securityState(up.userId()).orElse(null);
                if (state == null || !state.active()
                    || state.tokenVersion() != tokenVersion
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
            log.warn("SSE credential liveness check failed for principal {} — failing closed",
                subjectKey(principal), e);
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
     *
     * <p>WO-REL-47 HOLD finding 4: {@code removeClientState} already closes
     * the writer (see above) — the {@code complete()} below therefore races
     * only against a pump/send that now observes {@code Mode.CLOSED} and
     * becomes a no-op (never {@code emitter.send()} on a completed emitter).
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
        // WO-REL-52 (NEW-03, A4): явный лимит очереди моста + overflow-политика.
        // Без лимита очередь росла в брокере неограниченно при затянувшемся
        // отставании моста (per-client очереди уже bounded с REL-47, мостовая —
        // нет). x-max-length=10000 (на два порядка выше нормы: мост обычно
        // держит единицы сообщений; лимит — страховка от убегания, не рабочий
        // режим) + overflow drop-head: при переполнении брокер отбрасывает
        // СТАРЫЕ сообщения, свежие доставляются. Потеря старых — не потеря
        // навсегда: catchup при reconnect читает fp-окно из БД, а live-клиент
        // с пропуском увидит дыру и переподключится (тот же механизм, что
        // пропуск resolveLiveCursor — catchup heals). Limit должен быть
        // выше пикового burst'а живого моста, иначе нормальный пик будет
        // ронять старые события почём зря — 10000 с запасом ×1000 от нормы.
        Map<String, Object> queueArgs = Map.of(
            "x-max-length", 10_000,
            "x-overflow", "drop-head");
        Queue queue = new Queue(queueName, false, true, true, queueArgs); // exclusive, auto-delete
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
        // WO-REL-47: re-create lanes stopped by a previous stop() — Spring 7
        // pauses idle test contexts on switch (stop) and resumes them later
        // (start); without this a resumed context stays deaf. Production
        // shutdown never calls start() again, so this is resume-only.
        dispatchLane();
        sendLane();
        retryLane();
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
        // WO-REL-47 HOLD finding 4: the writer of every live client closes
        // here (not just the map entry) — an already-scheduled pump/send
        // observes Mode.CLOSED and stops instead of sending into a completed
        // emitter. Snapshot to avoid concurrent modification; complete outside
        // lock where possible.
        var snapshot = new java.util.ArrayList<>(clients.values());
        clients.clear();
        for (var c : snapshot) {
            c.closeWriter();
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
        // WO-PERF-6: do not kill in-flight emits — shutdown() would reject them (RejectedExecution).
        // WO-REL-47: all lanes down (dispatch first so no new send-submit starts,
        // then the send lane, then the retry lane; in-flight sends get the same
        // 5s grace as before).
        // Lanes are re-created on demand by dispatchLane()/sendLane()/retryLane() (see
        // start()) — a Spring-7 pause/resume cycle must not leave the service
        // deaf, so stop() nulls the fields after shutdown (no dangling
        // terminated pool for a submit path to grab).
        //
        // WO-REL-47 HOLD finding 2: the null-out AND the shutdown run under
        // ONE monitor (this), atomically — a concurrent dispatchLane()/
        // sendLane()/retryLane() racing stop() either grabs the pre-stop pool
        // (then stop() shuts it down: no leak) or blocks until stop() is done
        // (then it re-creates a fresh pool, owned by start()/submit path —
        // and start() re-arms it on resume). The pre-HOLD shape (null-out
        // inside the lock, shutdown() outside it) let a racer create a new
        // pool AFTER the null-out that stop() never saw → leaked threads on
        // every Spring pause/resume race. shutdown() under this lock cannot
        // deadlock: none of the pool threads ever synchronizes on the service
        // monitor (pump/send synchronize on the CLIENT monitor), and
        // awaitTermination below runs OUTSIDE the lock. shutdown() itself
        // never blocks on pool threads (it only flips the state; the wait is
        // awaitTermination, outside the lock), so holding the monitor across
        // it cannot stall a racing lane getter beyond a fast state flip.
        // Latched outside the lock request: the awaitTermination targets are
        // published through these locals (stop() runs once — compareAndSet
        // above — so no publication race).
        ExecutorService stopLatchDispatch = null;
        ExecutorService stopLatchSend = null;
        java.util.concurrent.ScheduledExecutorService stopLatchRetry = null;
        synchronized (this) {
            ExecutorService dispatchDoomed = dispatchExecutor;
            ExecutorService sendDoomed = sendExecutor;
            java.util.concurrent.ScheduledExecutorService retryDoomed = retryScheduler;
            dispatchExecutor = null;
            sendExecutor = null;
            retryScheduler = null;
            if (dispatchDoomed != null) {
                dispatchDoomed.shutdown();
            }
            if (sendDoomed != null) {
                sendDoomed.shutdown();
            }
            if (retryDoomed != null) {
                retryDoomed.shutdown();
            }
            stopLatchDispatch = dispatchDoomed;
            stopLatchSend = sendDoomed;
            stopLatchRetry = retryDoomed;
        }
        try { if (stopLatchDispatch != null) stopLatchDispatch.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { if (stopLatchSend != null) stopLatchSend.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { if (stopLatchRetry != null) stopLatchRetry.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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

    /**
     * WO-REL-47 (N07+N08): per-client single-writer state machine.
     *
     * <p>One value, one lock, three modes:
     * <ul>
     *   <li>BUFFERING — events accumulate in {@code queue} (catchup window,
     *       WO-REL-37); nothing is sent.</li>
     *   <li>LIVE — the pump sends strictly head-first, at most one send in
     *       flight per client (the pump submits the next send only after the
     *       previous settles — no arbitrary async tasks, no order inversion).</li>
     *   <li>CLOSED — terminal (overflow → close + reconnect catchup,
     *       timeout / failure / revoke / disconnect); enqueue/pump become
     *       no-ops.</li>
     * </ul>
     *
     * <p>The mode switch and the queue live under the SAME
     * {@code synchronized} protocol (never split flag+map like the pre-REL-47
     * {@code bufferingClients}+{@code bufferedEvents}, which could diverge
     * mid-drain and lose an event). Consequently a concurrent producer either
     * observes BUFFERING (its event lands in the queue BEFORE the drain
     * decision sees it) or LIVE (its event is queued for the pump) — the
     * lost-event interleave has no third outcome.
     *
     * <p>Record components stay the identity (clientId … processDefinitionKey-
     * Filter — untouched by this WO, read by the authz paths); the writer
     * state (mode + queue + pump flag + counters) lives in private mutable
     * fields guarded by the instance monitor.
     */
    private final class SseClientInfo {
        private final String clientId;
        private final SseEmitter emitter;
        private final Principal principal;
        /**
         * WO-SEC-67: JWT token_version frozen at registration. The liveness
         * check compares the row's CURRENT version against this — logout bumps
         * the row (the only version writer in prod), so a mismatch means
         * "issued before the revoke". ServicePrincipal streams carry -1
         * (unused — their liveness is the key row, not a version).
         */
        private final int tokenVersion;
        private final Collection<UUID> allowedPdIds;
        private final String typeFilter;
        private final String processInstanceIdFilter;
        private final String processDefinitionKeyFilter;

        private enum Mode { BUFFERING, LIVE, CLOSED }

        private Mode mode = Mode.BUFFERING;
        /**
         * WO-REL-47: the queued unit is builder + cursor + envelope TOGETHER.
         * The envelope is already retained by the builder via
         * {@code .data(envelope)} (wire payload) — the record only adds the
         * cursor (drain dedup without re-parsing) and a direct envelope
         * handle (post-send notify without re-extracting). No double
         * retention worth mentioning: two references to the same map.
         */
        private record QueuedSend(SseEmitter.SseEventBuilder event, long cursor,
                Map<String, Object> envelope) {}
        private final java.util.ArrayDeque<QueuedSend> queue = new java.util.ArrayDeque<>();
        /**
         * True while a send is in flight OR a pump run is scheduled: exactly
         * one pump continuation is ever outstanding per client (single
         * writer), so sends never overlap and never reorder.
         */
        private boolean pumpActive;
        /** WO-REL-47: overflow drops counted here (drop-newest, survivors ordered).
         * WO-REL-52: поле оставлено намеренно (не удалено): REL-47-тест
         * {@code slowClient_backpressureIsBounded} читает его white-box пробой
         * как границу очереди; после перехода на close-on-overflow оно всегда
         * 0 — это тоже утверждение (счётчик тихих потерь молчит, потому что
         * тихих потерь больше нет). Удаление поля сломало бы пробой без пользы.
         */
        private long droppedOverflow;
        SseClientInfo(String clientId, SseEmitter emitter, Principal principal,
                int tokenVersion, Collection<UUID> allowedPdIds,
                String typeFilter, String processInstanceIdFilter,
                String processDefinitionKeyFilter) {
            this.clientId = clientId;
            this.emitter = emitter;
            this.principal = principal;
            this.tokenVersion = tokenVersion;
            this.allowedPdIds = allowedPdIds;
            this.typeFilter = typeFilter;
            this.processInstanceIdFilter = processInstanceIdFilter;
            this.processDefinitionKeyFilter = processDefinitionKeyFilter;
        }

        // Identity accessors (same names as the pre-REL-47 record components).
        String clientId() { return clientId; }
        SseEmitter emitter() { return emitter; }
        Principal principal() { return principal; }
        int tokenVersion() { return tokenVersion; }
        Collection<UUID> allowedPdIds() { return allowedPdIds; }
        String typeFilter() { return typeFilter; }
        String processInstanceIdFilter() { return processInstanceIdFilter; }
        String processDefinitionKeyFilter() { return processDefinitionKeyFilter; }

        /** BUFFERING→BUFFERING idempotent (explicit protocol step, see registerBufferedClient). */
        synchronized void setBuffering() {
            // Fresh clients are born BUFFERING; only BUFFERING accepts this.
        }

        /**
         * WO-REL-47: live enqueue — single protocol step. BUFFERING: stage for
         * the drain decision. LIVE: queue for the pump + kick. CLOSED: drop.
         * Overflow (beyond {@code perClientQueueEvents}): CLOSE the client
         * (WO-REL-52 part B — see below), do not drop silently.
         *
         * <p>WO-REL-52 (NEW-04, part B): close-on-overflow, not silent drop.
         * The client is slower than the event flow (but inside the 5s send
         * timeout — otherwise the timeout path already closed it): events
         * 1…1000 arrive, 1001…N would be silently skipped, then N+1… would
         * resume — the client's Last-Event-ID jumps the hole and catchup on
         * the next reconnect starts AFTER it (the event is lost for this
         * client forever). Closing the stream instead makes the browser
         * reconnect with the last REALLY delivered id and heal the hole
         * through the regular catchup path (WO-REL-37), which already
         * exists. The close is reasoned ("overflow") and counted.
         */
        void enqueueLive(SseEmitter.SseEventBuilder event, Map<String, Object> envelope, long cursor) {
            boolean kick = false;
            boolean overflowed = false;
            synchronized (this) {
                if (mode == Mode.CLOSED) {
                    return;
                }
                if (queue.size() >= perClientQueueEvents) {
                    // WO-REL-52: terminal for this writer — but the close
                    // itself (failClient → removeClientState + emitter) runs
                    // OUTSIDE the client lock (see below): failClient takes
                    // other locks/orderings and must never run under it.
                    mode = Mode.CLOSED;
                    queue.clear();
                    overflowed = true;
                } else {
                    queue.addLast(new QueuedSend(event, cursor, envelope));
                    if (mode == Mode.LIVE && !pumpActive) {
                        pumpActive = true;
                        kick = true;
                    }
                }
            }
            if (overflowed) {
                log.warn("SSE client {} queue full ({} events) — closing (overflow), reconnect heals via catchup",
                    clientId, perClientQueueEvents);
                failClient("overflow");
                return;
            }
            if (kick) {
                kickPump();
            }
        }

        /**
         * WO-REL-37 (F14) + WO-REL-47: BUFFERING→LIVE under the client lock.
         * The dedup decision (cursor <= boundary → drop) runs on the drained
         * snapshot in queue order (cursor 100 before 101 — criterion 4), then
         * the survivors are re-queued head-first and the pump is kicked if
         * anything remains. After this call returns, no producer can observe
         * BUFFERING anymore: anything enqueued later takes the LIVE branch of
         * {@link #enqueueLive} — the lost-event window is closed.
         */
        void drainToLive(long catchupBoundary) {
            boolean kick = false;
            synchronized (this) {
                if (mode != Mode.BUFFERING) {
                    return;
                }
                mode = Mode.LIVE;
                if (!queue.isEmpty()) {
                    java.util.ArrayDeque<QueuedSend> survivors = new java.util.ArrayDeque<>();
                    for (QueuedSend queued : queue) {
                        // WO-REL-38: дедуп по позиции курсора (feedPosition;
                        // fallback — sequence, см. cursorOf выше).
                        if (queued.cursor <= catchupBoundary) {
                            continue;
                        }
                        survivors.addLast(queued);
                    }
                    queue.clear();
                    queue.addAll(survivors);
                }
                if (!queue.isEmpty() && !pumpActive) {
                    pumpActive = true;
                    kick = true;
                }
            }
            if (kick) {
                kickPump();
            }
        }

        /** Close the writer: terminal, idempotent; the pump drains to no-op. */
        synchronized void closeWriter() {
            mode = Mode.CLOSED;
            queue.clear();
        }

        /**
         * Single-writer pump: poll head, send it with timeout, repeat while
         * the queue is non-empty — strictly head-first (order = enqueue
         * order). At most one send in flight per client: the continuation
         * (next poll) is scheduled only after the current send settles, via
         * the orTimeout callback — no pooled thread ever waits on a send.
         */
        void pump() {
            QueuedSend head;
            synchronized (this) {
                if (mode == Mode.CLOSED) {
                    pumpActive = false;
                    return;
                }
                head = queue.pollFirst();
                if (head == null) {
                    pumpActive = false;
                    return;
                }
            }
            sendOne(head);
        }

        /**
         * One send on the send lane with a non-blocking timeout: the caller
         * (dispatch lane) returns immediately after submit; settle/timeout
         * handling runs as an orTimeout callback. Slow clients pin at most
         * one send-lane thread each (bounded pool caps the total); the queue
         * behind them stays bounded by {@code perClientQueueEvents}.
         */
        private void sendOne(QueuedSend queued) {
            CompletableFuture<Void> send;
            // WO-URGENT-1 (NEW2-03): the CLOSED-drop below returns WITHOUT
            // sending, but the continuation used to treat "no exception" as
            // "delivered" and called notifySent anyway — a closed writer
            // reported sends that never happened (phantom delivery,
            // notified 1 != delivered 0). The flag carries the fact of a
            // REAL send across the async boundary; notifySent fires only
            // when an actual emitter.send() happened.
            java.util.concurrent.atomic.AtomicBoolean actuallySent =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                send = CompletableFuture.runAsync(() -> {
                    try {
                        // WO-REL-47 HOLD finding 4: the writer may have closed
                        // while this send was queued (revoke / disconnect /
                        // stop) — the emitter below may already be completed.
                        // Never send into a closed writer: drop silently
                        // instead of emitting into IllegalStateException and a
                        // misleading "send error" log line.
                        synchronized (SseClientInfo.this) {
                            if (mode == Mode.CLOSED) {
                                return;
                            }
                        }
                        emitter.send(queued.event);
                        actuallySent.set(true);
                    } catch (IOException e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                }, sendLane());
            } catch (java.util.concurrent.RejectedExecutionException re) {
                // WO-REL-47 HOLD finding 1: send lane saturated (all threads
                // pinned by slow clients). Re-queue at HEAD (order preserved)
                // and re-arm the pump through the bounded retry lane — the
                // pre-HOLD shape only cleared pumpActive ("reactivates on the
                // next kick"), which stranded narrow-filter clients whose
                // next matching event might never come.
                synchronized (this) {
                    if (mode != Mode.CLOSED) {
                        queue.addFirst(queued);
                    }
                    pumpActive = false;
                }
                schedulePumpRetry("send-lane saturated");
                return;
            }
            send.orTimeout(sendTimeoutMs, TimeUnit.MILLISECONDS).whenCompleteAsync((v, ex) -> {
                if (ex == null) {
                    // WO-URGENT-1 (NEW2-03): report a delivery ONLY when the
                    // send above really ran. A CLOSED-drop (flag unset) still
                    // pumps so pumpActive resets through the CLOSED no-op —
                    // but never notifies.
                    if (actuallySent.get()) {
                        notifySent(queued.envelope);
                    }
                    pump();
                    return;
                }
                Throwable cause = ex instanceof java.util.concurrent.CompletionException ce
                    ? ce.getCause() : ex;
                if (cause instanceof TimeoutException
                        || ex instanceof java.util.concurrent.TimeoutException) {
                    // WO-PERF-6: timeout — lagging client is dropped (its
                    // cursor catchup on reconnect heals the gap, WO-REL-47
                    // criterion 3 path), never blocking the rest.
                    // WO-PERF-6: cancel(true) does NOT interrupt a blocking
                    // network write (Java IO without InterruptibleChannel) —
                    // the thread frees only when emitter.complete()/
                    // IOException fires, non-deterministically. We at least
                    // isolate the block to ONE send-lane slot per client.
                    log.warn("Slow SSE client {} timed out ({}ms), dropping", clientId, sendTimeoutMs);
                    send.cancel(true);
                    failClient("send timeout");
                } else if (isSendIoFailure(cause)) {
                    log.warn("Failed to send event to client {}: {}", clientId, cause.getMessage());
                    failClient("send failure");
                } else if (cause instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    failClient("send interrupted");
                } else {
                    log.error("Error sending event to client {}", clientId, cause != null ? cause : ex);
                    failClient("send error");
                }
            }, dispatchLane());
        }

        /**
         * WO-REL-47 HOLD finding 1: bounded re-arm of the pump after a lane
         * rejection. Schedules up to {@value #PUMP_RETRY_MAX_ATTEMPTS}
         * attempts at {@code pumpRetryDelayMs} intervals; each attempt takes
         * the pump slot (pumpActive) under the client lock and runs
         * {@link #pump} on the dispatch lane. Attempts stop early when the
         * queue drains, the writer closes, or the budget runs out — whichever
         * comes first. A later enqueue/drain kick re-arms independently, so a
         * retry budget exhausted under SUSTAINED saturation resumes as soon as
         * new work arrives or the lanes free up.
         *
         * @param reason log context (which lane rejected)
         */
        private void schedulePumpRetry(String reason) {
            java.util.concurrent.ScheduledExecutorService lane;
            try {
                lane = retryLane();
            } catch (java.util.concurrent.RejectedExecutionException re) {
                log.warn("SSE client {} pump stalled ({}; retry lane saturated)", clientId, reason);
                return;
            }
            final java.util.concurrent.atomic.AtomicInteger attemptsLeft =
                new java.util.concurrent.atomic.AtomicInteger(PUMP_RETRY_MAX_ATTEMPTS);
            final java.util.concurrent.ScheduledFuture<?>[] holder =
                new java.util.concurrent.ScheduledFuture<?>[1];
            holder[0] = lane.scheduleWithFixedDelay(() -> {
                // WO-REL-47 HOLD finding 4: never pump a closed writer —
                // the retry is a no-op (and self-cancels) once CLOSED.
                boolean run = false;
                synchronized (SseClientInfo.this) {
                    if (mode == Mode.CLOSED) {
                        run = false;
                    } else if (!queue.isEmpty() && !pumpActive) {
                        pumpActive = true;
                        run = true;
                    } else if (queue.isEmpty()) {
                        run = false;
                    } else {
                        // Pump already active (a kick got through meanwhile):
                        // this arming is redundant — cancel it.
                        run = false;
                    }
                }
                if (run) {
                    try {
                        dispatchLane().execute(this::pump);
                    } catch (java.util.concurrent.RejectedExecutionException re) {
                        synchronized (SseClientInfo.this) {
                            pumpActive = false;
                        }
                        // Stay armed: the next tick retries again (budget
                        // still applies below).
                    }
                    if (attemptsLeft.decrementAndGet() <= 0) {
                        holder[0].cancel(false);
                        log.warn("SSE client {} pump retry budget exhausted ({}); "
                            + "next enqueue/drain kick re-arms", clientId, reason);
                    }
                    return;
                }
                // Terminal states for this arming: closed, drained, or
                // superseded by a live pump — cancel, do not spin.
                if (mode == Mode.CLOSED || queue.isEmpty() || attemptsLeft.get() <= 0) {
                    holder[0].cancel(false);
                } else if (pumpActive) {
                    holder[0].cancel(false);
                }
            }, pumpRetryDelayMs, pumpRetryDelayMs, TimeUnit.MILLISECONDS);
        }

        /**
         * WO-REL-47 HOLD finding 4: the writer also closes HERE (mode=CLOSED,
         * queue dropped): removeClientState() already closes it — this is the
         * second, belt-and-braces close for the one path that never goes
         * through removeClientState (failClient is the terminal path; the
         * emitter is completed below and no pump/send may touch it after).
         */
        private void failClient(String reason) {
            closeWriter();
            // WO-SEC-67 red-team #3: full state removal (per-subject slot),
            // not a bare map drop — the slot would leak.
            removeClientState(clientId);
            try {
                emitter.complete();
            } catch (Exception ignore) {
            }
            log.info("SSE client {} closed ({})", clientId, reason);
        }

        /** Test/observability hook — fires only after a REAL send (WO-REL-47, §7.2 audit). */
        private void notifySent(Map<String, Object> envelope) {
            for (EventDispatchListener listener : eventListeners) {
                try {
                    listener.onEventSent(clientId, envelope);
                } catch (Exception listenerEx) {
                    log.warn("Event listener error", listenerEx);
                }
            }
        }

        /** Kick the pump on the dispatch lane; a reject re-arms via the bounded retry lane. */
        private void kickPump() {
            try {
                dispatchLane().execute(this::pump);
            } catch (java.util.concurrent.RejectedExecutionException re) {
                // WO-REL-47 HOLD finding 1: dispatch lane saturated. The
                // pre-HOLD shape only cleared pumpActive ("self-heals via the
                // next kick") — a client with a narrow filter might never get
                // that kick. Bounded retry lane re-arms the pump instead.
                synchronized (this) {
                    pumpActive = false;
                }
                schedulePumpRetry("dispatch-lane saturated");
            }
        }
        /**
         * WO-REL-47: unwraps CompletionException layers to the send's real
         * cause (the pre-REL-47 code distinguished the IOException cause two
         * levels down for the log line — same classification, kept).
         */
        private static boolean isSendIoFailure(Throwable cause) {
            for (Throwable t = cause; t != null; t = t.getCause()) {
                if (t instanceof IOException) {
                    return true;
                }
            }
            return false;
        }
    }
}
