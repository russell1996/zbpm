package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.UiUserLookupService;
import com.zorrodev.bpm.engine.service.ApiKeyService;
import com.zorrodev.bpm.engine.service.EventQueryService;
import com.zorrodev.bpm.exchange.TraceHeaders;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import com.github.benmanes.caffeine.cache.Cache;
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
import org.springframework.stereotype.Service;
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
public class SseEventStreamService implements SmartLifecycle, SseClientSession.Host {

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
    // WO-REL-56 (part B): foreign-sequence drop counter (may be null in
    // unit-scope harnesses — same nullable-collaborator shape as the SEC-67
    // lookups above; the drop itself never depends on the meter).
    private final BpmMetrics bpmMetrics;
    // WO-PERF-1 N4: single thread-safe Jackson 3 ObjectMapper instance (replaces per-message new)
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    public SseEventStreamService(EventQueryService eventQueryService,
                                    EventAuthzResolver eventAuthzResolver,
                                    @Lazy @Autowired(required = false) RabbitAdmin rabbitAdmin,
                                    tools.jackson.databind.ObjectMapper objectMapper,
                                    UiUserLookupService uiUserLookupService,
                                    ApiKeyService apiKeyService,
                                    @Lazy @Autowired(required = false) BpmMetrics bpmMetrics) {
        this.eventQueryService = eventQueryService;
        this.eventAuthzResolver = eventAuthzResolver;
        this.rabbitAdmin = rabbitAdmin;
        this.objectMapper = objectMapper;
        this.uiUserLookupService = uiUserLookupService;
        this.apiKeyService = apiKeyService;
        this.bpmMetrics = bpmMetrics;
        this.sessionRegistry = new SseSessionRegistry(
            () -> maxClients, () -> maxClientsPerSubject, () -> heartbeatIntervalMs,
            this::scheduleHeartbeat);
        this.authzGate = new SseAuthzGate(eventAuthzResolver, uiUserLookupService, apiKeyService,
            (session, reason) -> closeRevokedClient(session.clientId(), reason));
        this.deliveryDispatcher = new SseDeliveryDispatcher(sessionRegistry, authzGate,
            uiUserLookupService, this::closeRevokedClient);
        this.cursorSequencer = new SseCursorSequencer(eventQueryService, deliveryDispatcher,
            this::closeLiveClientsForGap, objectMapper, bpmMetrics,
            this::retryLane, () -> deferredCursorDelayMs, () -> pumpRetryDelayMs);
    }

    /**
     * Unit-scope overload (existing harnesses pass six args; the meter is
     * absent there — drops still happen, only the counter stays silent).
     */
    public SseEventStreamService(EventQueryService eventQueryService,
                                    EventAuthzResolver eventAuthzResolver,
                                    RabbitAdmin rabbitAdmin,
                                    tools.jackson.databind.ObjectMapper objectMapper,
                                    UiUserLookupService uiUserLookupService,
                                    ApiKeyService apiKeyService) {
        this(eventQueryService, eventAuthzResolver, rabbitAdmin, objectMapper,
            uiUserLookupService, apiKeyService, null);
    }

    /** Connected SSE clients: registry of sessions (WO-AUDIT-9 шаг 3). */
    private final SseSessionRegistry sessionRegistry;
    /** Authz-гейт сессий: liveness/rights/revoke (WO-AUDIT-9 шаг 4). */
    private final SseAuthzGate authzGate;
    /** Диспетчер доставки (WO-AUDIT-9 шаг 5a). */
    private final SseDeliveryDispatcher deliveryDispatcher;
    /** Курсор-сиквенсор (WO-AUDIT-9 шаг 5b). */
    private final SseCursorSequencer cursorSequencer;

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
     * One shared daemon scheduler (single thread) retries {@link SseClientSession#pump}
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

    /** Defer/gap budget base delay (test-shrinkable, WO-REL-52 A3). */
    private static final long DEFERRED_CURSOR_DELAY_MS = 100;
    private volatile long deferredCursorDelayMs = DEFERRED_CURSOR_DELAY_MS;

    /** Max retry attempts per arming (100ms × 50 = ~5s, one send-timeout window). */
    private static final int PUMP_RETRY_MAX_ATTEMPTS = 50;

    public synchronized java.util.concurrent.ScheduledExecutorService retryLane() {
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

    public synchronized ExecutorService dispatchLane() {
        ExecutorService lane = dispatchExecutor;
        if (lane == null || lane.isShutdown()) {
            lane = boundedPool("sse-dispatch-", Math.max(8,
                Runtime.getRuntime().availableProcessors()),
                new java.util.concurrent.LinkedBlockingQueue<>(1024));
            dispatchExecutor = lane;
        }
        return lane;
    }

    public synchronized ExecutorService sendLane() {
        ExecutorService lane = sendExecutor;
        if (lane == null || lane.isShutdown()) {
            lane = boundedPool("sse-send-", 128,
                new java.util.concurrent.SynchronousQueue<>());
            sendExecutor = lane;
        }
        return lane;
    }

    /**
     * WO-AUDIT-9 (шаг 2): {@link SseClientSession.Host} — сервис отдаёт сессии
     * свои lanes/конфиг/реестр/notify. Public lanes выше ({@link #dispatchLane},
     * {@link #sendLane}, {@link #retryLane}) закрывают три метода интерфейса
     * напрямую; ниже — остальное.
     */
    @Override
    public long pumpRetryDelayMs() {
        return pumpRetryDelayMs;
    }

    @Override
    public long sendTimeoutMs() {
        return sendTimeoutMs;
    }

    @Override
    public int perClientQueueEvents() {
        return perClientQueueEvents;
    }

    @Override
    public void terminalClose(SseClientSession session) {
        removeClientState(session.clientId());
    }

    @Override
    public void notifySent(SseClientSession session, Map<String, Object> envelope) {
        for (EventDispatchListener listener : eventListeners) {
            try {
                listener.onEventSent(session.clientId(), envelope);
            } catch (Exception listenerEx) {
                log.warn("Event listener error", listenerEx);
            }
        }
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

    /** Live registrations per subject key — owned by SseSessionRegistry (шаг 3). */

    /** Rights re-resolution cache — owned by SseAuthzGate (шаг 4). */

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

    /**
     * WO-REL-57: SSE heartbeat interval (test-shrinkable, default 15s).
     * A periodic SSE comment keeps idle streams alive across middleboxes
     * with an idle timeout (the external edge proxy killed silent chunked
     * connections with {@code ERR_INCOMPLETE_CHUNKED_ENCODING} on prod).
     * The comment carries no id/name/data — per the SSE spec a bare
     * comment line never dispatches a MessageEvent, so the frontend needs
     * no change (no listener can catch it, see REALTIME_EVENT_TYPES).
     * Heartbeats travel the SAME per-client writer queue as events (never a
     * parallel direct {@code emitter.send} — the writer protocol stays the
     * single send path), but they never trigger close-on-overflow (a full
     * queue skips the tick for that client) and never fire
     * {@link EventDispatchListener} (delivery hook is for domain events).
     */
    private volatile long heartbeatIntervalMs = 15_000;

    /** Armed heartbeat future — owned by SseSessionRegistry (шаг 3). */

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
        SseClientSession live = sessionRegistry.get(clientId);
        if (live != null) {
            live.drainToLive(0L);
        }
        return clientId;
    }

    private String registerClientInternal(SseEmitter emitter, Principal principal, String typeFilter,
                                   String processInstanceIdFilter, String processDefinitionKeyFilter) {
        // WO-SEC-67: per-subject cap — checked AND incremented under the
        // same lock as the global cap so the count cannot race (inside
        // SseSessionRegistry.add — the registry owns both structures now).
        String subjectKey = subjectKey(principal);
        String clientId = UUID.randomUUID().toString();
        Collection<UUID> allowedPdIds = authzGateInitialPdIds(principal, processDefinitionKeyFilter);

        SseClientSession info = new SseClientSession(new SseClientDescriptor(clientId, emitter, principal,
            authzGate.currentTokenVersion(principal), allowedPdIds,
            typeFilter, processInstanceIdFilter, processDefinitionKeyFilter), this);
        sessionRegistry.add(info, subjectKey);

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
        // WO-REL-57: arm the heartbeat with the first client (lazy — no
        // clients, no ticks; cancelled by stop(), re-armed on resume).
        sessionRegistry.ensureHeartbeat();

        log.info("SSE client {} registered: type={}, processInstanceId={}, processDefinitionKey={}",
            clientId, typeFilter, processInstanceIdFilter, processDefinitionKeyFilter);
        return clientId;
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
        SseClientSession known = sessionRegistry.get(clientId);
        if (known == null) {
            return;
        }
        sessionRegistry.remove(clientId, subjectKey(known.principal()));
    }

    /** Initial pdId snapshot at registration — delegates to the gate's resolver (шаг 4). */
    private Collection<UUID> authzGateInitialPdIds(Principal principal, String processDefinitionKeyFilter) {
        return eventAuthzResolver.readableRuntimePdIds(principal, processDefinitionKeyFilter);
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
     * {@link SseClientSession}): окно потери между catchup-чтением и подпиской
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
        SseClientSession client = sessionRegistry.get(clientId);
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
        SseClientSession client = sessionRegistry.get(clientId);
        if (client == null) {
            return;
        }
        client.drainToLive(catchupBoundary);
    }

    /**
     * WO-REL-57: arm the periodic heartbeat (idempotent, lazy) — delegate to
     * the session registry (шаг 3). Package-visible: heartbeat tests tick it directly.
     */
    void ensureHeartbeat() {
        sessionRegistry.ensureHeartbeat();
    }

    /**
     * WO-REL-57: one heartbeat tick — delegate to the registry. Package-visible
     * for tests (the interval itself is wall-clock).
     */
    void sendHeartbeatToAll() {
        sessionRegistry.sendHeartbeatToAll();
    }

    /** Scheduler back-call for the registry heartbeat (bounded retry-lane, no new pool). */
    private java.util.concurrent.ScheduledFuture<?> scheduleHeartbeat(Runnable task, long intervalMs) {
        return retryLane().scheduleWithFixedDelay(task, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
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
        // WO-AUDIT-9 шаг 5b: приём события живёт в сиквенсоре (парсинг,
        // pdUuid-резолвинг, MDC-окно, live-курсор → stage/defer/отброс).
        cursorSequencer.accept(messageBody, amqpHeaders);
    }

    /** Сиквенсорные приватные методы переехали в SseCursorSequencer (шаг 5b). */

    /**
     * WO-REL-55 (NEW2-09, часть B): drop-head больше не тихий.
     *
     * <p>Контекст: очередь моста (`x-max-length=10000, drop-head`) при
     * сильном stall'е отбрасывает СТАРЫЕ сообщения в брокере — live-клиенты
     * получают разрыв курсоров (например `[10, 12]`) и никогда о нём не
     * узнают: детекции ни на сервере, ни в `useRealtimeEvents.ts` не было.
     * Выбран вариант «закрывать LIVE-клиентов» (а не клиентская детекция):
     * закрытый браузерный EventSource переподключается сам (reconnectTime
     * 3s в каждом событии), шлёт последний РЕАЛЬНО доставленный
     * Last-Event-ID, и штатный catchup (WO-REL-37) читает дыру из БД честно —
     * тот же механизм, что уже лечит overflow-закрытия (REL-52 part B) и
     * пропуск resolveLiveCursor. Клиентский вариант требовал бы нового
     * протокола поверх SSE id + правок фронта ради события, которое
     * наступает при stall'е > 10000 сообщений, — серверный переиспользует
     * существующий путь целиком.
     *
     * <p>Закрытие — ДО рассылки события-детектора: закрытые клиенты его не
     * получают, их Last-Event-ID остаётся на последнем доставленном, и
     * catchup на reconnect забирает И пропущенное, И сам детектор. Иначе
     * (рассылка затем закрытие) клиент ушёл бы с Last-Event-ID=12, а
     * catchup стартовал бы ПОСЛЕ дыры — потеря 11 навсегда. BUFFERING-клиенты
     * не трогаем: их поток не стартовал, пересечение с catchup решит drain.
     */
    private void closeLiveClientsForGap(long fromCursor, long toCursor) {
        log.warn("SSE cursor gap detected (dispatched up to {}, next is {}) — "
            + "closing LIVE clients so reconnect heals via catchup (drop-head suspected)",
            fromCursor, toCursor);
        for (SseClientSession client : sessionRegistry.snapshot()) {
            if (client.isLive()) {
                closeRevokedClient(client.clientId(),
                    "cursor-gap " + fromCursor + "→" + toCursor + " (drop-head suspected)");
            }
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
     * WO-SEC-67 narrowing — delegates to the gate (шаг 4); kept package-visible:
     * unit harnesses may call it directly.
     */
    static boolean isNarrowed(Collection<UUID> snapshot, Collection<UUID> fresh) {
        return SseAuthzGate.isNarrowed(snapshot, fresh);
    }

    /**
     * WO-SEC-67 red-team #1/#2 — live view и key-sweep живут в гейте (шаг 4).
     */
    public void invalidateStreamsForKey(UUID apiKeyId) {
        authzGate.invalidateStreamsForKey(sessionRegistry.snapshot(), apiKeyId);
    }

    /**
     * WO-SEC-67 (F13): close one session's stream now (revocation path).
     * Removes state (releases the per-subject slot) and completes the
     * emitter; also evicts the session's rights-cache entry so a later
     * stream starts from a cold read.
     *
     * <p>WO-REL-47 HOLD finding 4: {@code removeClientState} already closes
     * the writer (see above) — the {@code complete()} below therefore races
     * only against a pump/send that now observes {@code Mode.CLOSED} and
     * becomes a no-op (never {@code emitter.send()} on a completed emitter).
     */
    private void closeRevokedClient(String clientId, String reason) {
        SseClientSession client = sessionRegistry.get(clientId);
        removeClientState(clientId);
        if (client != null) {
            authzGate.evict(client);
            try {
                client.emitter().complete();
            } catch (Exception e) {
                log.warn("SSE close of revoked client {} failed", clientId, e);
            }
        }
        log.info("SSE client {} closed (revoked: {})", clientId, reason);
    }

    /**
     * WO-SEC-67 (F13): event-driven invalidation entry point (called by the
     * revoke/logout/membership paths). The sweep itself lives in the gate
     * (шаг 4); kept here so external callers are untouched.
     */
    public void invalidateStreams() {
        authzGate.invalidateStreams(sessionRegistry.snapshot());
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
        if (rabbitAdmin == null || sessionRegistry.isEmpty()
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
                if (sessionRegistry.isEmpty()
                        || (listenerContainer != null && listenerContainer.isRunning())) {
                    return;
                }
            }
            if (!first) {
                log.warn("SSE bridge: retrying start ({} waiting clients)", sessionRegistry.size());
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
            if (!sessionRegistry.isEmpty() && (listenerContainer == null || !listenerContainer.isRunning())) {
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
            if (!sessionRegistry.isEmpty()) {
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
        log.info("SSE shutdown: completing {} active emitters", sessionRegistry.size());
        // WO-REL-47 HOLD finding 4: the writer of every live client closes
        // here (not just the map entry) — an already-scheduled pump/send
        // observes Mode.CLOSED and stops instead of sending into a completed
        // emitter. Snapshot to avoid concurrent modification; complete outside
        // lock where possible.
        var snapshot = new java.util.ArrayList<>(sessionRegistry.snapshot());
        sessionRegistry.clear();
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
            // WO-REL-56 (verifier-находка 1): взведённый gap-таймер гасится
            // здесь же (таймер живёт в сиквенсоре; cancel(false) не ждёт
            // выполнения — взаимного ожидания с монитором this нет).
            cursorSequencer.cancelGapTimer();
            // WO-REL-57: взведённый heartbeat гасится здесь же (тиковый
            // future; сама retry-lane глушится ниже вместе с остальными).
            sessionRegistry.cancelHeartbeat();
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

    // WO-AUDIT-9 (шаг 2, часть 2/2): конец перенесённого тела (см. SseClientSession.java).
}
