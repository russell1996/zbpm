package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/**
 * WO-AUDIT-9 (шаг 6): AMQP-мост и жизненный цикл пулов — бывшие
 * {@code ensureBridgeStarted} / {@code spawnStarterIfNeededLocked} /
 * {@code startBridgeLoop} / {@code startBridgeNow} /
 * {@code stopRabbitMqListenerIfNoClients} / {@code stopAndDestroy} /
 * {@code deleteQueueQuietly} {@code SseEventStreamService}
 * (WO-REL-20, WO-REL-23, WO-REL-52 NEW-03 A4, verifier round 2),
 * перенесённые построчно, без смены семантики.
 *
 * <p>Зависимости — constructor-injected: {@link RabbitAdmin} (nullable —
 * static wiring без брокера, не transient failure), наличие ждущих клиентов
 * и интервал ретрая (suppliers фасада), приём событий (consumer → сиквенсор).
 * Блокирующие broker-RPC идут ВНЕ {@code bridgeLock}: зависший брокер не
 * должен сериализовать регистрации (WO-REL-20, поймано SseBridgeStartupTest).
 */
@Slf4j
public final class SseBridgeManager {

    private final RabbitAdmin rabbitAdmin;
    private final IntSupplier waitingClientCount;
    private final java.util.function.LongSupplier retryIntervalMs;
    private final BiConsumer<String, Map<String, ?>> eventSink;

    public SseBridgeManager(RabbitAdmin rabbitAdmin,
            IntSupplier waitingClientCount,
            java.util.function.LongSupplier retryIntervalMs,
            BiConsumer<String, Map<String, ?>> eventSink) {
        this.rabbitAdmin = rabbitAdmin;
        this.waitingClientCount = waitingClientCount;
        this.retryIntervalMs = retryIntervalMs;
        this.eventSink = eventSink;
    }

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

    /**
     * Triggers a bridge start unless one is already running or starting.
     * Returns immediately — the start itself runs on a background thread.
     * Safe to call from every registration (not just the first client): the
     * flag makes concurrent/duplicate starts impossible, and a failed start
     * simply leaves the bridge DOWN so the next registration retries.
     */
    public void ensureBridgeStarted() {
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
        if (rabbitAdmin == null || waitingClientCount.getAsInt() == 0
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
            int waiting;
            synchronized (bridgeLock) {
                waiting = waitingClientCount.getAsInt();
                if (waiting == 0
                        || (listenerContainer != null && listenerContainer.isRunning())) {
                    return;
                }
            }
            if (!first) {
                log.warn("SSE bridge: retrying start ({} waiting clients)", waiting);
            }
            first = false;
            try {
                startBridgeNow();
                return;
            } catch (Exception e) {
                log.error("SSE bridge: failed to start RabbitMQ listener, retrying", e);
            }
            try {
                Thread.sleep(retryIntervalMs.getAsLong());
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
            eventSink.accept(body, message.getMessageProperties().getHeaders());
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
            if (waitingClientCount.getAsInt() > 0 && (listenerContainer == null || !listenerContainer.isRunning())) {
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

    /** Остановка моста, когда клиентов не осталось (вызывает фасад при снятии). */
    public void stopIfNoClients() {
        SimpleMessageListenerContainer doomed;
        synchronized (bridgeLock) {
            if (waitingClientCount.getAsInt() > 0) {
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

    /** Снятие моста при shutdown фасада (контейнер возвращается вызывающему для stop вне лока). */
    public SimpleMessageListenerContainer takeForShutdown() {
        synchronized (bridgeLock) {
            SimpleMessageListenerContainer doomed = listenerContainer;
            listenerContainer = null;
            return doomed;
        }
    }

    /** Stops + destroys a container. Call outside {@link #bridgeLock}. */
    public void stopAndDestroy(SimpleMessageListenerContainer container) {
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
}
