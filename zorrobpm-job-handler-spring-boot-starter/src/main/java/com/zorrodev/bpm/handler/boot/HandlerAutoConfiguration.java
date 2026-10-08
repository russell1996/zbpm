package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.handler.JobHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HandlerAutoConfiguration {

    private final ApplicationContext applicationContext;
    private final SimpleRabbitListenerContainerFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    /**
     * WO-C8-36 (CR-13): дотянуть фабрику до publisher confirms + returns.
     * Дефолт true — воркер подтверждает вход только после надёжной публикации
     * результата. Выключение — осознанный opt-out (старая семантика «синхронные
     * исключения наружу, confirm не ждём»). Новых required-env нет.
     */
    @Value("${zorrobpm.worker.ensure-publisher-confirms:true}")
    private boolean ensurePublisherConfirms = true;

    /** WO-C8-36 (CR-13): deadline ожидания брокерского confirm на completion. */
    @Value("${zorrobpm.worker.completion-confirm-timeout:5000}")
    private long completionConfirmTimeoutMs = 5_000L;

    /**
     * WO-REL-64: после стольких неудачных публикаций подряд результат паркуется
     * в poison-очередь (см. {@code CompletionPoisonRetryListener}), а вход
     * подтверждается — основная очередь продолжает работать. Дефолт 10: при
     * шкале 1с→…→30с это ~минута горячих попыток на отравленный результат до
     * парковки (1+2+4+8+16+30+30+30+30 = 151с), дальше — тихий цикл повтора из
     * poison с растущей задержкой. Границы зажимает сам слушатель
     * ({@code MIN/MAX_MAX_COMPLETION_ATTEMPTS}, та же дисциплина, что у
     * confirm-таймаута).
     */
    @Value("${zorrobpm.worker.completion-max-attempts:10}")
    private int completionMaxAttempts = 10;

    /**
     * WO-REL-64: выключатель парковки. {@code false} — осознанный opt-out в
     * семантику WO-C8-36 (бесконечный backoff 1с→30с на потоке потребителя,
     * отравленный результат держит очередь вечно). Новых required-env нет.
     */
    @Value("${zorrobpm.worker.completion-poison-enabled:true}")
    private boolean completionPoisonEnabled = true;

    private ObjectMapper objectMapper;  // shared instance (CRIT-5)

    /**
     * WO-REL-66 (A): worker-side known topology — every (handler, queue)
     * pair subscribed by {@link #init()}. Replayed on each new broker
     * connection by {@link #ensureTopologyRedeclareOnReconnect} (the
     * 2026-10-07 incident: recreated broker, poison + work queues gone,
     * {@code declarePoisonTopology} ran once from {@code init()} and never
     * again → {@code not_found} until manual restart).
     */
    private final java.util.List<HandlerQueue> handlerQueues =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * WO-REL-66 (A): work queues pinned as legacy (pre-REL-45, no DLX —
     * broker 406s on redeclare-with-args, can never succeed). Terminal for
     * this JVM: skipped by reconnect-redeclare, exactly like the engine-side
     * {@code legacyDeclared} (criterion 4 — REL-51 does not regress).
     * Poison-queue 406s are pinned under their own queue names in the same set.
     */
    private final java.util.Set<String> workerLegacyPinned =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * WO-REL-66 (A): minimum interval between worker topology redeclare
     * rounds (connection flaps must not storm the broker). First reconnect
     * always runs (fresh instance starts at 0).
     */
    static final long WORKER_REDECLARE_DEBOUNCE_MS = 30_000L;

    private final java.util.concurrent.atomic.AtomicBoolean workerRedeclareInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong lastWorkerRedeclareMs =
        new java.util.concurrent.atomic.AtomicLong(0);
    /**
     * WO-REL-66 (A): первое соединение в жизни воркера — догоняющее
     * (catch-up), а не flap: его раунд НЕ двигает часы дебаунса, иначе flap
     * через секунды после старта скипнется с незалеченной топологией
     * (поймано живым IT). Дебаунс — только между flap-раундами.
     */
    private final java.util.concurrent.atomic.AtomicBoolean workerCatchUpDone =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** WO-REL-66 (A): one subscribed (handler, queue) pair. */
    private record HandlerQueue(JobHandler handler, String queueName) {
    }

    /**
     * WO-REL-62: с какой каденцией контейнер перепроверяет missing queue
     * (ms). Дефолт Spring AMQP — 60с: воркер, поднявшийся раньше своей очереди,
     * ждал бы минуту даже при живом брокере; 2с — достаточно быстро для
     * reconnect-сценария и достаточно редко, чтобы не шуметь в лог.
     */
    static final long RETRY_DECLARATION_INTERVAL_MS = 2_000L;

    /** WO-QW-1 S-7: package-visible for the unit test (same mechanism as RabbitConfiguration). */
    static void setExchangeOnlyTrustedPackages(
            org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper mapper) {
        try {
            java.lang.reflect.Field f =
                org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper.class
                    .getDeclaredField("trustedPackages");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Set<String> trusted = (java.util.Set<String>) f.get(mapper);
            trusted.clear();
            trusted.add("com.zorrodev.bpm.exchange");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WO-QW-1 S-7: cannot narrow AMQP trusted packages", e);
        }
    }

    /**
     * Reader тела входящего задания.
     *
     * <p>WO-C8-36 (H-1): {@code FAIL_ON_UNKNOWN_PROPERTIES} по умолчанию true,
     * то есть СТАРЫЙ воркер роняет десериализацию на любом поле, добавленном
     * движком в новой версии, а {@code onMessage} на этой ошибке делает чистый
     * возврат — то есть AUTO-ack: задание теряется навсегда, без redelivery.
     * Апгрейд движка раньше воркеров (штатный rolling order) превращался в
     * ПОТЕРЮ ВСЕХ заданий. Поэтому reader толерантен к незнакомым полям, а
     * второй слой — {@code @JsonIgnoreProperties(ignoreUnknown=true)} на DTO
     * exchange — спасает даже чужой строгий reader.
     */
    static ObjectMapper createJobBodyReader() {
        return new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @PostConstruct
    public void init() {
        this.objectMapper = createJobBodyReader();
        Map<String, JobHandler> handlersMap = applicationContext.getBeansOfType(JobHandler.class);
        log.info("Found {} handlers", handlersMap.size());

        // One-time converter config (CRIT-3: not in loop, CRIT-4: Jackson2 variant).
        // WO-QW-1 S-7: narrow the deserialization allowlist to our own exchange
        // package (clear-then-add: the setter only adds, the constructor seeds
        // java.util/java.lang — we never pass a literal "*"). Sibling path:
        // RabbitConfiguration.messageConverter().
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        setExchangeOnlyTrustedPackages(
            (org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper)
                converter.getJavaTypeMapper());
        connectionFactory.setMessageConverter(converter);
        rabbitTemplate.setMessageConverter(converter);

        // WO-C8-36 (CR-13): ACK входа завязывается на confirm публикации
        // результата (см. JobCompletionListener.sendCompletion): фабрика — в
        // CORRELATED + returns, шаблон — mandatory + returns-callback движка
        // воркера (unroutable → исключение, не тихий confirm ack=true).
        // Действует только на mandatory-публикации этого стартера (completion);
        // чужие sends через тот же бин семантически не меняются (returns без
        // callback раньше тоже никуда не девались — их просто никто не читал).
        if (ensurePublisherConfirms) {
            // CF — из шаблона (у фабрики контейнеров публичного геттера нет),
            // шаблон и контейнеры делят одну и ту же фабрику соединения.
            org.springframework.amqp.rabbit.connection.ConnectionFactory cf =
                rabbitTemplate.getConnectionFactory();
            if (cf instanceof CachingConnectionFactory cachingCf) {
                cachingCf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
                cachingCf.setPublisherReturns(true);
            } else {
                log.warn("WO-C8-36: publisher confirms requested but connection factory is {} "
                    + "(not caching) — completions fall back to sync-exceptions-only",
                    cf == null ? "null" : cf.getClass().getSimpleName());
            }
            rabbitTemplate.setMandatory(true);
            // ReturnsCallback нужен ТОЛЬКО для наблюдаемости (warn в лог). Решение
            // «считать ли отправку доставленной» принимает сам воркер по
            // CorrelationData.getReturned() (см. JobCompletionListener.sendCompletion)
            // — это не зависит от чужого callback на шаблоне, поэтому составной
            // контекст (чужой callback уже стоял) больше не деградирует проверку.
            // Обратный контур: повторный set на том же шаблоне = IllegalState,
            // init() в составных контекстах вызывается повторно — молча пропускаем.
            try {
                rabbitTemplate.setReturnsCallback(returned -> log.warn(
                    "Completion returned as unroutable: replyCode={}, replyText={}, exchange={}, routingKey={}",
                    returned.getReplyCode(), returned.getReplyText(),
                    returned.getExchange(), returned.getRoutingKey()));
            } catch (IllegalStateException someoneElsesCallback) {
                log.warn("WO-C8-36: RabbitTemplate already has a returns callback "
                    + "(not ours) — keeping it; delivery decision does not depend on it: {}",
                    someoneElsesCallback.getMessage());
            }
        }

        for (Map.Entry<String, JobHandler> entry : handlersMap.entrySet()) {
            JobHandler handler = entry.getValue();
            SimpleMessageListenerContainer container = connectionFactory.createListenerContainer();
            String queueName = "zorrobpm.jobs." + handler.getJob();
            // WO-REL-66 (A): record BEFORE any declare attempt — the
            // reconnect-redeclare below must also cover queues whose startup
            // declare never ran (broker was down at startup).
            handlerQueues.add(new HandlerQueue(handler, queueName));
            container.setQueueNames(queueName);
            // WO-REL-62: очередь может появиться позже, чем стартует контейнер
            // (брокер лежал на старте и declare ушёл на reconnect; движок тоже
            // объявляет очередь лениво на send-пути). Умирать на missing queue
            // нельзя — это и была постоянная глухота; ждём и цепляемся ретраями.
            container.setMissingQueuesFatal(false);
            container.setFailedDeclarationRetryInterval(RETRY_DECLARATION_INTERVAL_MS);
            // WO-REL-62 (ex WO-ENG-31): declare-блок обязан переживать недоступный
            // брокер на старте — но НЕ пропуском подписки (та была постоянной
            // глухотой до ручного рестарта, NEW5-03), а переносом declare на
            // переподключение. Контейнер стартует ВСЕГДА: у него собственный
            // reconnect-цикл, который подхватит брокер, когда тот вернётся.
            try {
                if (amqpAdmin.getQueueInfo(queueName) == null) {
                    declareQueue(handler, queueName);
                }
            } catch (RuntimeException e) {
                log.warn("RabbitMQ unreachable at startup, subscription to {} "
                    + "starts without declare (will declare on reconnect): {}",
                    queueName, e.getMessage());
                scheduleDeclareOnReconnect(handler, queueName, container);
            }
            log.info("Subscribing to {}", queueName);
            // WO-REL-36: вся логика — в JobCompletionListener (разделение ошибок +
            // идемпотентная переотправка); здесь только wiring.
            // WO-C8-36: confirm-настройки listener'а — из тех же пропертей, что выше.
            JobCompletionListener jobListener =
                new JobCompletionListener(handler, rabbitTemplate, objectMapper, queueName);
            jobListener.setEnsurePublisherConfirms(ensurePublisherConfirms);
            jobListener.setConfirmTimeoutMs(completionConfirmTimeoutMs);
            // WO-REL-64: потолок попыток публикации + парковка отравленного
            // результата — из тех же пропертей, что выше.
            jobListener.setMaxCompletionAttempts(completionMaxAttempts);
            jobListener.setPoisonParkingEnabled(completionPoisonEnabled);
            container.setMessageListener(jobListener);
            container.start();
        }

        // WO-REL-64: слушатель poison-очереди — на ОТДЕЛЬНОМ потоке потребителя:
        // его попытки (с растущей задержкой через delay-очередь) не держат
        // основную очередь. Стартует всегда при включённой парковке — в т.ч. при
        // нуле хендлеров: припаркованное чужим воркером забирает любой живой.
        if (completionPoisonEnabled) {
            try {
                declarePoisonTopology(amqpAdmin);
            } catch (RuntimeException e) {
                log.warn("RabbitMQ unreachable at startup, poison topology not declared "
                    + "(will declare on reconnect): {}", e.getMessage());
            }
            SimpleMessageListenerContainer poisonContainer =
                connectionFactory.createListenerContainer();
            poisonContainer.setQueueNames(CompletionPoisonRetryListener.POISON_QUEUE);
            poisonContainer.setMissingQueuesFatal(false);
            poisonContainer.setFailedDeclarationRetryInterval(RETRY_DECLARATION_INTERVAL_MS);
            CompletionPoisonRetryListener retryListener = new CompletionPoisonRetryListener(
                rabbitTemplate, objectMapper, JobCompletionListener.COMPLETE_QUEUE);
            retryListener.setEnsurePublisherConfirms(ensurePublisherConfirms);
            retryListener.setConfirmTimeoutMs(completionConfirmTimeoutMs);
            poisonContainer.setMessageListener(retryListener);
            poisonContainer.start();
            log.info("Subscribing to {}", CompletionPoisonRetryListener.POISON_QUEUE);
        }

        // WO-REL-66 (A): permanent redeclare on every new broker connection —
        // covers BOTH the startup-failure path above (its one-shot listener
        // self-removes after the first success, so a LATER broker flap would
        // otherwise lose the topology again) and the steady-state loss from
        // the 2026-10-07 incident. Declares are idempotent on the broker;
        // the round is debounced and legacy-aware inside.
        ensureTopologyRedeclareOnReconnect();
    }

    /**
     * WO-REL-64: топология парковки отравленных результатов.
     *
     * <p>{@code zorrobpm.completion.poison} — durable, без DLX: каждая копия
     * ACK'ается слушателем явно (успех — доставлена движку, неудача —
     * перепакована в delay, битая оболочка — терминально). DLX здесь означал
     * бы «потеря по истечении», что противоречит «не дропается никогда».
     *
     * <p>{@code zorrobpm.completion.retry-delay} — durable с
     * {@code x-dead-letter-routing-key} на poison через ЯВНЫЙ default exchange
     * ({@code x-dead-letter-exchange: ""} — без него брокер отвергает declare:
     * {@code 406 routing_key_but_no_dlx_defined}, поймано живым IT WO-REL-64):
     * копия с per-message TTL по истечении возвращается в poison — автоповтор
     * с растущей задержкой без единого таймера в коде.
     */
    static void declarePoisonTopology(AmqpAdmin amqpAdmin) {
        amqpAdmin.declareQueue(
            org.springframework.amqp.core.QueueBuilder
                .durable(CompletionPoisonRetryListener.POISON_QUEUE).build());
        amqpAdmin.declareQueue(
            org.springframework.amqp.core.QueueBuilder
                .durable(CompletionPoisonRetryListener.RETRY_DELAY_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key",
                    CompletionPoisonRetryListener.POISON_QUEUE)
                .build());
        log.info("Poison topology declared ({} + {})",
            CompletionPoisonRetryListener.POISON_QUEUE,
            CompletionPoisonRetryListener.RETRY_DELAY_QUEUE);
    }

    /**
     * WO-REL-45: зеркало engine-side {@code JobQueueDeclarer} — DLX/DLQ-аргументы
     * byte-identical, кто первым объявил — выиграл, второй declare — no-op
     * (те же аргументы, нет 406). Воркер, стартовавший раньше любого engine-
     * анонса, всё равно получает DLQ. Вынесено из {@link #init()} без изменений —
     * вызывается и на старте, и на reconnect.
     */
    private void declareQueue(JobHandler handler, String queueName) {
        String dlqName = "zorrobpm.jobs." + handler.getJob() + ".dlq";
        amqpAdmin.declareExchange(
            new org.springframework.amqp.core.DirectExchange("zorrobpm.jobs.dlx", true, false));
        amqpAdmin.declareQueue(
            org.springframework.amqp.core.QueueBuilder.durable(dlqName).build());
        amqpAdmin.declareBinding(new org.springframework.amqp.core.Binding(
            dlqName, org.springframework.amqp.core.Binding.DestinationType.QUEUE,
            "zorrobpm.jobs.dlx", dlqName, null));
        Queue queue = org.springframework.amqp.core.QueueBuilder.durable(queueName)
            .deadLetterExchange("zorrobpm.jobs.dlx")
            .deadLetterRoutingKey(dlqName)
            .build();
        amqpAdmin.declareQueue(queue);
        log.info("Queue {} created (DLQ {})", queueName, dlqName);
    }

    /**
     * WO-REL-62: одноразовый declare на первом переподключении после стартового
     * провала. Стандартный Spring AMQP паттерн ({@code ConnectionListener.onCreate}):
     * брокер вернулся → новое физическое соединение → объявляем очередь, которую
     * не смогли объявить на старте → снимаемся (declare идемпотентен, повторные
     * соединения его не требуют). Неудача и здесь — НЕ снимаемся: следующее
     * переподключение попробует снова (брокер может флапать).
     *
     * <p>Reentrancy-защита: {@code RabbitAdmin} сам открывает соединения на той
     * же фабрике, и чужой {@code onCreate} может прийти рекурсивно/параллельно —
     * {@code inFlight} сериализует попытки, чтобы два declare не шли внахлёст.
     */
    private void scheduleDeclareOnReconnect(JobHandler handler, String queueName,
            SimpleMessageListenerContainer container) {
        ConnectionFactory cf = container.getConnectionFactory();
        if (!(cf instanceof CachingConnectionFactory cachingCf)) {
            log.warn("Cannot schedule redeclare for {}: connection factory is {} "
                + "(not caching) — queue will be declared lazily by the engine "
                + "on the send path", queueName,
                cf == null ? "null" : cf.getClass().getSimpleName());
            return;
        }
        AtomicBoolean inFlight = new AtomicBoolean(false);
        ConnectionListener[] self = new ConnectionListener[1];
        self[0] = new ConnectionListener() {
            @Override
            public void onCreate(Connection connection) {
                if (!inFlight.compareAndSet(false, true)) {
                    return;
                }
                try {
                    declareQueue(handler, queueName);
                    // WO-REL-64: стартовый declare яда тоже мог не пройти
                    // (брокер лежал) — добираем здесь же, идемпотентно.
                    if (completionPoisonEnabled) {
                        declarePoisonTopology(amqpAdmin);
                    }
                    if (cachingCf.removeConnectionListener(self[0])) {
                        log.info("Queue {} declared on reconnect, redeclare listener removed",
                            queueName);
                    }
                } catch (RuntimeException e) {
                    if (isPreconditionFailed(e)) {
                        // WO-REL-51 (зеркало engine-side): очередь существует с
                        // legacy-определением (pre-REL-45, без DLX) — повторный
                        // declare с DLX-аргументами не преуспеет никогда, только
                        // операторская миграция поможет. Терминально для этой JVM:
                        // снимаемся, warn ровно один раз (дальше — как engine:
                        // очередь без DLQ, см. runbook миграции).
                        if (cachingCf.removeConnectionListener(self[0])) {
                            log.warn("Queue {} exists with legacy arguments (no DLX — "
                                + "DLQ inactive for this job type); not retrying. "
                                + "Migrate per docs/runbooks/rabbitmq-legacy-queue-dlx-migration.md",
                                queueName);
                        }
                    } else {
                        log.warn("Declare of {} on reconnect failed (will retry on next "
                            + "reconnect): {}", queueName, e.getMessage());
                    }
                } finally {
                    inFlight.set(false);
                }
            }
        };
        cachingCf.addConnectionListener(self[0]);
        log.info("Scheduled redeclare of {} on broker reconnect", queueName);
    }

    /**
     * WO-REL-66 (A): permanent worker-topology redeclare on every new broker
     * connection. Standard Spring AMQP pattern ({@code ConnectionListener.onCreate},
     * same as the startup one-shot above): a recreated broker means a new
     * physical connection, which is exactly the heal signal.
     *
     * <p>Replays the poison topology (when parking is enabled) plus every
     * recorded work queue, skipping REL-51 legacy-pinned names (terminal for
     * this JVM — criterion 4). Never throws out of {@code onCreate} (a throw
     * there would poison the connection-recovery path itself); failures are
     * warn-logged and retried on the next reconnect. Non-caching factories
     * get a warn (same fallback contract as the one-shot path: the queues
     * then rely on the peer side declaring them).
     */
    private void ensureTopologyRedeclareOnReconnect() {
        org.springframework.amqp.rabbit.connection.ConnectionFactory cf =
            rabbitTemplate.getConnectionFactory();
        if (!(cf instanceof CachingConnectionFactory cachingCf)) {
            log.warn("WO-REL-66: cannot redeclare worker topology on reconnect: "
                + "connection factory is {} (not caching)",
                cf == null ? "null" : cf.getClass().getSimpleName());
            return;
        }
        cachingCf.addConnectionListener(new ConnectionListener() {
            @Override
            public void onCreate(Connection connection) {
                redeclareWorkerTopology();
            }
        });
        log.info("WO-REL-66: worker topology redeclare on broker reconnect registered "
            + "({} work queue(s), poison={})", handlerQueues.size(), completionPoisonEnabled);
    }

    /**
     * WO-REL-66 (A): one redeclare round over the known worker topology.
     * Package-visible for the unit test (fire without a broker).
     */
    void redeclareWorkerTopology() {
        if (!workerRedeclareInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            boolean hasWork = completionPoisonEnabled || !handlerQueues.isEmpty();
            if (!hasWork) {
                // Nothing known yet — NOT a round (same debounce-clock
                // discipline as the engine-side listener: an empty round must
                // not eat the window of the first real flap).
                return;
            }
            boolean catchUp = !workerCatchUpDone.getAndSet(true);
            if (!catchUp) {
                long now = System.currentTimeMillis();
                if (now - lastWorkerRedeclareMs.get() < WORKER_REDECLARE_DEBOUNCE_MS) {
                    return;
                }
                lastWorkerRedeclareMs.set(now);
            }
            int redeclared = 0;
            if (completionPoisonEnabled
                    && !workerLegacyPinned.contains(CompletionPoisonRetryListener.POISON_QUEUE)) {
                try {
                    declarePoisonTopology(amqpAdmin);
                    redeclared++;
                } catch (RuntimeException e) {
                    if (isPreconditionFailed(e)) {
                        workerLegacyPinned.add(CompletionPoisonRetryListener.POISON_QUEUE);
                        log.warn("WO-REL-66: poison topology exists with legacy arguments — "
                            + "not retrying (see docs/runbooks/rabbitmq-legacy-queue-dlx-migration.md)");
                    } else {
                        log.warn("WO-REL-66: poison topology redeclare on reconnect failed "
                            + "(will retry on next reconnect): {}", e.getMessage());
                    }
                }
            }
            for (HandlerQueue hq : handlerQueues) {
                if (workerLegacyPinned.contains(hq.queueName())) {
                    continue;
                }
                try {
                    declareQueue(hq.handler(), hq.queueName());
                    redeclared++;
                } catch (RuntimeException e) {
                    if (isPreconditionFailed(e)) {
                        // WO-REL-51 mirror (same terminal semantics as the
                        // engine side): warn exactly once, pin for this JVM.
                        workerLegacyPinned.add(hq.queueName());
                        log.warn("WO-REL-66: queue {} exists with legacy arguments (no DLX — "
                            + "DLQ inactive for this job type); not retrying. "
                            + "Migrate per docs/runbooks/rabbitmq-legacy-queue-dlx-migration.md",
                            hq.queueName());
                    } else {
                        log.warn("WO-REL-66: redeclare of {} on reconnect failed (will retry "
                            + "on next reconnect): {}", hq.queueName(), e.getMessage());
                    }
                }
            }
            log.info("WO-REL-66: worker topology redeclare round done ({} declare(s))", redeclared);
        } finally {
            workerRedeclareInFlight.set(false);
        }
    }

    /**
     * WO-REL-51, локальная копия engine-side {@code JobQueueDeclarer.isPreconditionFailed}:
     * стартер не зависит от {@code zorrobpm-rabbitmq} в compile-scope (там только
     * test-scope, см. pom) — тащить модуль ради одного предиката тяжелее, чем
     * держать зеркало с явной ссылкой. Сигналы те же, оба независимых:
     * структурированный (ShutdownSignalException с reply-code 406) и текстовый
     * фолбэк (PRECONDITION_FAILED / 406+inequivalent). Голый 406 без ключевых слов —
     * НЕ terminal (ретрай может вылечить), как в engine.
     */
    static boolean isPreconditionFailed(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof com.rabbitmq.client.ShutdownSignalException sse) {
                Object reason = sse.getReason();
                if (reason instanceof com.rabbitmq.client.AMQP.Channel.Close channelClose
                    && channelClose.getReplyCode() == 406) {
                    return true;
                }
                if (reason instanceof com.rabbitmq.client.AMQP.Connection.Close connectionClose
                    && connectionClose.getReplyCode() == 406) {
                    return true;
                }
            }
            String message = c.getMessage();
            if (message != null) {
                String upper = message.toUpperCase(java.util.Locale.ROOT);
                if (upper.contains("PRECONDITION_FAILED")) {
                    return true;
                }
                if (upper.contains("406") && upper.contains("INEQUIVALENT")) {
                    return true;
                }
            }
        }
        return false;
    }
}
