package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.TraceHeaders;
import com.zorrodev.bpm.handler.JobHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WO-REL-36 (F10): слушатель worker-очереди с разделённой обработкой ошибок.
 *
 * <p>Было: один внешний {@code catch} в {@code HandlerAutoConfiguration} оборачивал
 * И десериализацию входящей джобы, И отправку completion-результата — сбой ОТПРАВКИ
 * логировался как «Failed to deserialize» и тихо возвращался; при AUTO-ack контейнер
 * подтверждал вход, а результат терялся навсегда (watchdog WO-REL-35 такое не чинит).
 *
 * <p>Стало, три раздельные ветки:
 * <ul>
 *   <li>malformed payload (десериализация упала) → тихо, без отправки, без проброса:
 *       poison-сообщение не должно ретраиться вечно; DLQ-политику применяет сам
 *       контейнер/брокер, не этот код;</li>
 *   <li>handler упал (бизнес-ошибка) → completion со статусом FAILED отправляется
 *       как раньше (ретраи движка и инцидент — на стороне engine);</li>
 *   <li>отправка completion упала (transport failure: broker недоступен) →
 *       исключение ПРОБРАСЫВАЕТСЯ наружу, вход НЕ подтверждается (retry/NACK
 *       контейнера), результат не теряется.</li>
 * </ul>
 *
 * <p>Идемпотентность (п.2 WO): ключ — {@code correlationId} входящего сообщения
 * (engine ставит туда outboxId отправки — уникален на каждую джобу, проверено чтением
 * {@code ServiceTaskListener}). Повторная доставка того же сообщения (redelivery после
 * сбоя отправки) НЕ повторяет бизнес-эффект: handler вызывается один раз, а готовый
 * {@code ServiceTaskCompleteData} переотправляется из bounded result-cache. Без
 * correlationId кэшировать нечего — выполняется и отправляется как раньше (честное
 * ограничение, не тихий скип). Кэш bounded (10k/10m, паттерн F38) — утечки нет.
 */
@Slf4j
public class JobCompletionListener implements MessageListener {

    /**
     * Имя очереди completion'ов. В проде — {@code zorrobpm.complete-service-task} (см.
     * {@code RabbitConfiguration.COMPLETE_QUEUE}); в тесте — своя очередь, чтобы не
     * спорить с чужими durable-флагами shared-брокера (406 PRECONDITION_FAILED).
     * Проводка та же: send → exchange → очередь → чтение независимым наблюдателем.
     *
     * <p>WO-INT-10: публикация идёт через {@code CompletionTopology.COMPLETION_EXCHANGE}
     * с routing key = имя очереди (identity-биндинги объявляет движок) — НЕ через
     * default exchange (брокер проверяет write-право против {@code amq.default},
     * и такое право воркеру давать нельзя).
     */
    public static final String COMPLETE_QUEUE = "zorrobpm.complete-service-task";

    private final JobHandler handler;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String queueName;
    private final String completeQueueName;

    private final Cache<String, ServiceTaskCompleteData> resultCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofMinutes(10))
        .build();

    /**
     * WO-C8-36 (CR-13): ACK входящего задания связан с надёжной публикацией
     * результата. Отправка идёт с per-send {@code CorrelationData} + mandatory,
     * после send — синхронное ожидание брокерского confirm этой отправки
     * (код — {@link #sendCompletion}). Требует confirm-type CORRELATED (ставит
     * {@code HandlerAutoConfiguration}); при недоступных confirms см.
     * {@link #confirmsAvailable} — warn + счётчик, не тишина.
     *
     * <p>Голый confirm ack НЕ равен доставке: брокер подтверждает ПРИЁМ и для
     * {@code basic.return}, поэтому возврат ловится отдельно и сам по себе
     * бросает исключение.
     *
     * <p>NACK/timeout/return → исключение → вход НЕ подтверждается (retry/NACK
     * контейнера), а результат уже в resultCache — восстанавливается без
     * повторного бизнес-эффекта. Выключатель —
     * {@code ensurePublisherConfirms=false} (тогда только синхронные исключения,
     * как до WO).
     */
    private boolean ensurePublisherConfirms = true;
    private long confirmTimeoutMs = 5_000L;

    /** WO-C8-36: нижняя граница ожидания confirm (см. {@link #setConfirmTimeoutMs}). */
    static final long MIN_CONFIRM_TIMEOUT_MS = 100L;

    /**
     * WO-C8-36 (M-2): верхняя граница ожидания confirm.
     *
     * <p>Зачем: контейнер воркера по умолчанию однопоточный (настройки
     * {@code spring.rabbitmq.listener.*} живут в {@code zorrobpm-rabbitmq}, а
     * стартер воркера на прод-classpath не значится — то есть внешний воркер
     * работает на дефолтах Spring). При concurrency=1 единственный поток
     * потребителя проводит в {@code future.get(...)} ВСЁ это время — на
     * {@code completion-confirm-timeout=3600000} воркер молча вставал на час
     * на каждом задании, без единого warn'а (warn ниже пола срабатывал только
     * на слишком маленьком значении). Час — это уже не «медленно», это
     * нерабочий воркер; поэтому 60с.
     */
    static final long MAX_CONFIRM_TIMEOUT_MS = 60_000L;

    /**
     * WO-C8-36: ACK входа завязывается на confirm публикации результата. Полный
     * контракт — в javadoc {@link #ensurePublisherConfirms}; выключатель —
     * {@code ensurePublisherConfirms=false} (тогда только синхронные исключения).
     */
    public void setEnsurePublisherConfirms(boolean ensurePublisherConfirms) {
        this.ensurePublisherConfirms = ensurePublisherConfirms;
    }

    /**
     * WO-C8-36: таймаут подтверждения не может быть неположительным: при 0/отрицательном
     * {@code future.get(<=0)} истекает мгновенно, то confirm-wait бросал бы на КАЖДОМ
     * completion'е → вход не ACK'ается никогда → воркер уходит в бесконечную
     * переотправку (redelivery storm), то есть опsetting-protection превращается в
     * DoS самому себе. Конфиг недоверенный ввод — зажимаем полом и warn'им (P-41:
     * новая ручка рядом с проверяемой не должна быть ловушкой; здесь она единственная
     * в namespace {@code zorrobpm.worker.*}, сравнивать не с чему, поэтому пол явный).
     */
    public void setConfirmTimeoutMs(long confirmTimeoutMs) {
        // WO-C8-36 (M-2): ограничены ОБЕ границы, а не только пол. Значение выше
        // потолка опасно не «медленно», а молча: единственный поток потребителя
        // (воркер вне zorrobpm-rabbitmq идёт на дефолтах Spring = concurrency 1)
        // проводит в future.get() всё это время и воркер перестаёт обрабатывать
        // всё подряд — без warn'а. P-46 в применении: новая ручка рядом с
        // проверяемой не должна быть ловушкой с другой стороны.
        if (confirmTimeoutMs < MIN_CONFIRM_TIMEOUT_MS) {
            log.warn("completion-confirm-timeout={}ms is below the {}ms floor — clamped. "
                + "A non-positive timeout would expire every confirm immediately and "
                + "redeliver every job forever.", confirmTimeoutMs, MIN_CONFIRM_TIMEOUT_MS);
            this.confirmTimeoutMs = MIN_CONFIRM_TIMEOUT_MS;
            return;
        }
        if (confirmTimeoutMs > MAX_CONFIRM_TIMEOUT_MS) {
            log.warn("completion-confirm-timeout={}ms is above the {}ms ceiling — clamped. "
                + "A worker on default listener settings has a single consumer thread; "
                + "waiting longer than the ceiling parks it on every job.",
                confirmTimeoutMs, MAX_CONFIRM_TIMEOUT_MS);
            this.confirmTimeoutMs = MAX_CONFIRM_TIMEOUT_MS;
            return;
        }
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    /** Тест-хук: фактически применённый таймаут ожидания confirm, мс. */
    long confirmTimeoutMsForTest() {
        return confirmTimeoutMs;
    }

    /**
     * WO-C8-36 (red-team 1.2 + пересмотр HOLD-6): немаршрутизиваемость читается с
     * САМОЙ отправки — {@link org.springframework.amqp.rabbit.connection.CorrelationData#getReturned()}, который spring-amqp
     * заполняет в {@code basic.return}-обработчике ({@code PublisherCallbackChannelImpl});
     * разделяемый сет возвратов НЕ держим.
     *
     * <p>Почему это не гонка (HOLD-6 был про окно «return опоздал после confirm»):
     * в ack-пути {@code doHandleConfirm} сначала зовёт
     * {@code PendingConfirm.waitForReturnIfNeeded()} (ждёт return-latch до 60 с) и
     * только затем дёргает confirm-listener — то есть к моменту, когда наша
     * confirm-future завершится, {@code getReturned()} уже заполнен. Проверено
     * байткодом spring-rabbit 4.0.4. Разделяемый сет + TTL-чистка были лишним
     * состоянием с собственной гонкой и несовпадением форматов (prod «cid#millis»
     * против тестового «cid») — удалены.
     */
    /**
     * WO-C8-36 (red-team HOLD-4): наблюдаемость тихих ослаблений БЕЗ новой
     * зависимости (micrometer в стартер не тянем): счётчики + warn на каждый
     * fallback-путь (видно в логах/алертах; громкий путь — confirm-throw).
     */
    private final java.util.concurrent.atomic.AtomicLong confirmsUnavailableCount =
        new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong unroutableCount =
        new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * WO-C8-36 (M-1): задержка перед повторной обработкой при недоставленном
     * результате. Ключ счётчика — {@code correlationId} входящего задания.
     */
    private final java.util.concurrent.atomic.AtomicReference<CompletionRedeliveryBackoff>
        redeliveryBackoffRef =
            new java.util.concurrent.atomic.AtomicReference<>(new CompletionRedeliveryBackoff(
                JobCompletionListener::sleepQuietly));

    /**
     * Тест-хук: подмена backoff'а sleeper'ом, который только ЗАПИСЫВАЕТ интервалы.
     * Без этого проверка «1с→2с→4с» стоила бы 7 секунд реального сна на прогон.
     */
    void setRedeliveryBackoff(CompletionRedeliveryBackoff backoff) {
        this.redeliveryBackoffRef.set(backoff);
    }

    /** Счётчик горячих переотправок — оператор должен видеть и сам факт, и темп. */
    private final java.util.concurrent.atomic.AtomicLong redeliveryCount =
        new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * WO-INT-10 (Q3): сколько публикаций результата отказано брокером по правам
     * (403/access_refused — misconfig permissions, не транспорт). Растёт —
     * чинить права воркера и перегонять припаркованное из poison.
     */
    private final java.util.concurrent.atomic.AtomicLong accessDeniedCount =
        new java.util.concurrent.atomic.AtomicLong(0);

    /** WO-INT-10 (Q3): отказы публикации по правам (misconfig, не транспорт). */
    public long accessDeniedCountForTest() {
        return accessDeniedCount.get();
    }

    /** WO-C8-36 (M-1): сколько раз задание было переотправлено после отказа доставки. */
    public long redeliveryCountForTest() {
        return redeliveryCount.get();
    }

    /** Тест-хук: сам backoff (счётчик попыток/задержки), не публикуется наружу. */
    CompletionRedeliveryBackoff redeliveryBackoffForTest() {
        return redeliveryBackoffRef.get();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** WO-C8-36: сколько completion'ов ушло без confirm-wait (fallback). */
    public long confirmsUnavailableCountForTest() {
        return confirmsUnavailableCount.get();
    }

    /**
     * WO-REL-64: потолок попыток публикации одного результата.
     *
     * <p>После стольких неудач подряд результат паркуется в poison-очередь
     * (см. {@code CompletionPoisonRetryListener}), а вход подтверждается —
     * основная очередь продолжает работать. Без потолка одно отравленное
     * задание держало единственный поток потребителя вечно (измерено в
     * WO-C8-36, принято временно, закрывается здесь).
     */
    static final int DEFAULT_MAX_COMPLETION_ATTEMPTS = 10;

    /** WO-REL-64: пол — при 0 попыток результат парковался бы сразу, не пытаясь. */
    static final int MIN_MAX_COMPLETION_ATTEMPTS = 1;

    /**
     * WO-REL-64: потолок потолка — та же логика, что M-2 у confirm-таймаута
     * (P-41: новая ручка рядом с проверяемой не должна быть ловушкой с другой
     * стороны): сотни попыток по 30с — это годы горячей очереди вместо
     * видимой парковки.
     */
    static final int MAX_MAX_COMPLETION_ATTEMPTS = 100;

    private int maxCompletionAttempts = DEFAULT_MAX_COMPLETION_ATTEMPTS;
    private boolean poisonParkingEnabled = true;
    private String poisonQueueName = CompletionPoisonRetryListener.POISON_QUEUE;

    /** WO-REL-64: сколько результатов ушло в poison-очередь (не drop, не потеря). */
    private final java.util.concurrent.atomic.AtomicLong poisonedCount =
        new java.util.concurrent.atomic.AtomicLong(0);

    /** WO-REL-64: сколько результатов ушло в poison-очередь. */
    public long poisonedCountForTest() {
        return poisonedCount.get();
    }

    /**
     * WO-REL-64: потолок попыток публикации. Конфиг — недоверенный ввод:
     * зажимаем обе границы с warn'ом (та же дисциплина, что
     * {@link #setConfirmTimeoutMs}).
     */
    public void setMaxCompletionAttempts(int maxCompletionAttempts) {
        if (maxCompletionAttempts < MIN_MAX_COMPLETION_ATTEMPTS) {
            log.warn("completion-max-attempts={} is below the {} floor — clamped. "
                    + "Zero attempts would park every result without even trying.",
                maxCompletionAttempts, MIN_MAX_COMPLETION_ATTEMPTS);
            this.maxCompletionAttempts = MIN_MAX_COMPLETION_ATTEMPTS;
            return;
        }
        if (maxCompletionAttempts > MAX_MAX_COMPLETION_ATTEMPTS) {
            log.warn("completion-max-attempts={} is above the {} ceiling — clamped. "
                    + "Hundreds of 30s-spaced attempts keep a poisoned result hot for years "
                    + "instead of parking it visibly.",
                maxCompletionAttempts, MAX_MAX_COMPLETION_ATTEMPTS);
            this.maxCompletionAttempts = MAX_MAX_COMPLETION_ATTEMPTS;
            return;
        }
        this.maxCompletionAttempts = maxCompletionAttempts;
    }

    /** Тест-хук: применённый потолок попыток. */
    int maxCompletionAttemptsForTest() {
        return maxCompletionAttempts;
    }

    /**
     * WO-REL-64: выключатель парковки (осознанный opt-out в старую семантику
     * «бесконечный backoff на потоке потребителя» — см. javadoc
     * {@code CompletionRedeliveryBackoff} про HOL-блокировку).
     */
    public void setPoisonParkingEnabled(boolean poisonParkingEnabled) {
        this.poisonParkingEnabled = poisonParkingEnabled;
    }

    /** Тест-хук: очередь парковки (в проде — {@code POISON_QUEUE}). */
    void setPoisonQueueName(String poisonQueueName) {
        this.poisonQueueName = poisonQueueName;
    }

    /** WO-C8-36: сколько completion'ов поймано как unroutable. */
    public long unroutableCountForTest() {
        return unroutableCount.get();
    }

    /**
     * WO-C8-36 (H-1, п.е): сколько входящих заданий не удалось прочитать.
     *
     * <p>Раньше этот случай был ТИХИМ: {@code log.error} без счётчика, после
     * которого метод возвращается — то есть контейнер ACK'ает, и задание
     * исчезает. Строка лога сама по себе не считается наблюдаемостью: при
     * массовом апгрейде движка «новые поля» уезжают в лог сотнями строк, и
     * без числа нельзя ни заметить масштаб, ни доказать, что после правки
     * маршрута поток прекратился. Ошибка при этом логируется на ERROR с
     * классом и причиной — версия движка больше не угадывается по тексту.
     */
    private final java.util.concurrent.atomic.AtomicLong malformedCount =
        new java.util.concurrent.atomic.AtomicLong(0);

    /** WO-C8-36 (H-1, п.е): непрочитанные задания (тихий ACK на стороне брокера). */
    public long malformedCountForTest() {
        return malformedCount.get();
    }

    /**
     * WO-C8-36: доступны ли publisher confirms на фабрике шаблона.
     * Делегирует общему отправителю (тот же предикат — см.
     * {@link ConfirmedCompletionSender#confirmsAvailable}).
     */
    private boolean confirmsAvailable() {
        return ConfirmedCompletionSender.confirmsAvailable(rabbitTemplate);
    }

    public JobCompletionListener(JobHandler handler, RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper, String queueName) {
        this(handler, rabbitTemplate, objectMapper, queueName, COMPLETE_QUEUE);
    }

    /** Тест-ctor: очередь completion'ов задаётся явно (изоляция от shared-брокера). */
    public JobCompletionListener(JobHandler handler, RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper, String queueName, String completeQueueName) {
        this.handler = handler;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
        this.completeQueueName = completeQueueName;
    }

    @Override
    public void onMessage(Message message) {
        final JobDetailModel model;
        try {
            model = objectMapper.readValue(message.getBody(), JobDetailModel.class);
        } catch (Exception e) {
            // Malformed payload: без отправки, без проброса (см. javadoc) — иначе
            // poison-сообщение ретраилось бы вечно. ACK при этом всё равно
            // происходит (чистый возврат), поэтому исход обязан быть виден в
            // телеметрии: счётчик + ERROR с классом/причиной (WO-C8-36 H-1 п.е).
            // Самый частый источник — не битый JSON, а поле от более нового
            // движка, поэтому в тексте прямо назван этот сценарий: без
            // ignoreUnknown на DTO и толерантного reader'а (см.
            // HandlerAutoConfiguration.createJobBodyReader) апгрейд движка раньше
            // воркеров терял здесь каждое задание.
            long n = malformedCount.incrementAndGet();
            log.error("Malformed job message dropped without completion (queue={}, dropped total={}): "
                    + "{}: {}. If this appeared right after an engine upgrade, the engine is "
                    + "newer than this worker — check dispatch-phase stamping flag "
                    + "(zorrobpm.engine.dispatch-phase-stamping) and worker version",
                queueName, n, e.getClass().getName(), e.getMessage(), e);
            return;
        }

        // WO-OBS-8: the worker hop. This starter ships to EXTERNAL worker JVMs that do
        // not (and must not be required to) carry the OTel SDK — so no span is opened
        // here even when one could be: the incoming W3C traceparent is forwarded VERBATIM
        // into the completion message and the engine-side completion listener (which HAS
        // the SDK) continues the trace from it. Trace-ID continuity — the actual WO
        // criterion — holds with or without a worker-local SDK; a worker-local span
        // would silently break the chain on every external worker without OTel
        // configured (GlobalOpenTelemetry noop → invalid span → null traceparent).
        // MDC (traceId + processInstanceId) IS set here — worker logs are criterion 2.
        Map<String, Object> incomingHeaders = message.getMessageProperties() != null
            ? message.getMessageProperties().getHeaders() : Map.of();
        String incomingTraceParent = headerAsString(incomingHeaders, TraceHeaders.TRACE_PARENT_HEADER);
        String headerPi = headerAsString(incomingHeaders, TraceHeaders.PROCESS_INSTANCE_ID_HEADER);
        String processInstanceId = headerPi != null ? headerPi
            : (model.getProcessInstanceId() != null ? model.getProcessInstanceId().toString() : null);
        String priorTraceId = MDC.get(TraceHeaders.MDC_TRACE_ID);
        String priorPi = MDC.get(TraceHeaders.MDC_PROCESS_INSTANCE_ID);
        String mdcTraceId = TraceHeaders.extractTraceId(incomingTraceParent);
        if (mdcTraceId != null) {
            MDC.put(TraceHeaders.MDC_TRACE_ID, mdcTraceId);
        }
        if (processInstanceId != null) {
            MDC.put(TraceHeaders.MDC_PROCESS_INSTANCE_ID, processInstanceId);
        }
        try {
            onMessageTraced(message, model, incomingTraceParent, processInstanceId);
        } finally {
            restoreMdc(TraceHeaders.MDC_TRACE_ID, priorTraceId);
            restoreMdc(TraceHeaders.MDC_PROCESS_INSTANCE_ID, priorPi);
        }
    }

    private void onMessageTraced(Message message, JobDetailModel model,
            String incomingTraceParent, String processInstanceId) {
        String correlationId = message.getMessageProperties() != null
            ? message.getMessageProperties().getCorrelationId()
            : null;

        ServiceTaskCompleteData cached =
            correlationId != null ? resultCache.getIfPresent(correlationId) : null;
        if (cached != null) {
            // Redelivery: работа уже сделана, переотправляем только результат.
            // Сбой и здесь — тоже проброс (вход не подтверждаем).
            sendCompletion(cached, cached.getCompletionId());
            return;
        }

        ServiceTaskCompleteData completeData = new ServiceTaskCompleteData();
        completeData.setServiceTaskId(model.getServiceTaskId());
        // WO-C8-36 (CR-01): эхо идентификатора вызова — движок принимает ТОЛЬКО
        // результат ожидаемой фазы (exact-match); дубликаты/устаревшие игнорит.
        // Null (старый движок) — эхо null, движок идёт legacy-путём без проверки.
        // Кэшированная переотправка переигрывает ТОТ ЖЕ объект — фаза при нём.
        completeData.setDispatchPhase(model.getDispatchPhase());
        completeData.setDispatchIndex(model.getDispatchIndex());
        // WO-OBS-8: verbatim-forward (see onMessage) — set BEFORE the resultCache.put
        // below so redeliveries replay the same trace linkage, not a blank one.
        completeData.setTraceParent(incomingTraceParent);
        completeData.setProcessInstanceId(processInstanceId);
        try {
            List<ProcessVariable> result = handler.handleJob(model).stream().map(x -> {
                ProcessVariable v = new ProcessVariable();
                v.setName(x.getName());
                v.setValue(x.getValue());
                v.setType(x.getType().toString());
                return v;
            }).toList();
            completeData.setStatus("SUCCESS");
            completeData.setVariables(result);
        } catch (Exception e) {
            // Бизнес-ошибка воркера: FAILED-completion как раньше (ретраи/инцидент — engine).
            completeData.setStatus("FAILED");
            completeData.setErrorMessage(e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            log.warn("Job '{}' handler failed: {}", handler.getJob(), completeData.getErrorMessage());
        }

        if (correlationId != null) {
            resultCache.put(correlationId, completeData);
        }
        // WO-REL-64 (red-team H-1): ключ шкалы попыток обязан быть стабилен на
        // redelivery того же входящего сообщения. При correlationId это
        // completionId (объект переигрывается из кэша, id тот же); БЕЗ
        // correlationId кэша нет и каждый redelivery создаёт новый объект с
        // новым UUID — ключ по completionId давал бы вечно «попытку №1» и
        // потолок не наступал бы никогда. Fallback — serviceTaskId + фаза:
        // то же сообщение переигрывается с теми же значениями, шкала растёт.
        // Два РАЗНЫХ задания одного serviceTask делят fallback-шкалу — это
        // fail-safe направление (парковка раньше, результат не теряется).
        String attemptKey = correlationId != null ? null : fallbackAttemptKey(completeData);
        sendCompletion(completeData, attemptKey);
    }

    /**
     * WO-REL-64 (red-team H-1): ключ шкалы для отправок без correlationId —
     * стабилен между redelivery одного сообщения (те же serviceTaskId/фаза).
     */
    private static String fallbackAttemptKey(ServiceTaskCompleteData completeData) {
        return "nocorr:" + completeData.getServiceTaskId()
            + "#" + completeData.getDispatchPhase()
            + "#" + completeData.getDispatchIndex();
    }

    private void sendCompletion(ServiceTaskCompleteData completeData, String attemptKeyOverride) {
        // WO-C8-36 (CR-13): per-send CorrelationData — confirm/return маппятся на
        // ЭТУ отправку (общий confirm-callback движка без correlation data их
        // игнорировал — та же дыра, что чиним).
        // WO-C8-36 (red-team HOLD-1): completionId СТАБИЛЕН на redelivery —
        // объект completeData создаётся один раз на correlationId и переигрывается
        // из resultCache (см. onMessageTraced), поэтому ставим id один раз и
        // храним ВНУТРИ тела: движок дедуплицирует FAILED-дубликаты по нему.
        String idFromBody = completeData.getCompletionId();
        if (idFromBody == null) {
            idFromBody = "completion-" + UUID.randomUUID();
            completeData.setCompletionId(idFromBody);
        }
        final String completionId = idFromBody;
        boolean waitForConfirm = ensurePublisherConfirms && confirmsAvailable();
        // WO-REL-64 (red-team H-1): null — обычный путь с correlationId
        // (шкала по completionId, он стабилен через кэш).
        final String attemptKey =
            attemptKeyOverride != null ? attemptKeyOverride : completionId;
        try {
            // WO-REL-64: сама отправка — через общего отправителя
            // (Confirm/return-контракт WO-C8-36 — см. ConfirmedCompletionSender —
            // здесь был дословно; per-send CorrelationData, trace-forward
            // WO-OBS-8 и waitForConfirmsOrDie-запрет red-team blocker 1.1 —
            // всё переехало туда без изменения поведения).
            // WO-INT-10: через completion-exchange, ключ — имя очереди
            // (identity-биндинг движка), не default exchange.
            ConfirmedCompletionSender.sendAndConfirm(rabbitTemplate,
                CompletionTopology.COMPLETION_EXCHANGE, completeQueueName,
                completeData, null, null, confirmTimeoutMs, waitForConfirm, unroutableCount);
            if (waitForConfirm) {
                // NACK/timeout/разрыв до confirm и unroutable-возврат бросают из
                // общего отправителя (см. выше) — сюда доходим только когда
                // результат надёжно опубликован и маршрутизируем.
                //
                // WO-C8-36 (red-team HOLD-6 re-pass), остаточное окно return-vs-confirm:
                // брокер шлёт basic.return ДО confirm-ack на том же канале, окно
                // «return опоздал ПОСЛЕ confirm» микроскопическое и в AMQP 0-9-1
                // неустранимо (нужен was-accepted-сигнал, которого нет). Оставлено
                // явным, не спрятано — было здесь дословно, переехало в отчёт.
                redeliveryBackoffRef.get().reset(attemptKey);
            } else if (ensurePublisherConfirms) {
                long n = confirmsUnavailableCount.incrementAndGet();
                log.warn("Publisher confirms unavailable on worker connection factory "
                    + "(HandlerAutoConfiguration sets CORRELATED in prod) — completion {} "
                    + "falls back to sync-exceptions-only (fallback #{} until confirms appear)",
                    completionId, n);
            }
        } catch (RuntimeException e) {
            // Transport failure: потолок → парковка в poison (вход ACK'ается,
            // очередь течёт), иначе проброс наружу — контейнер NACK'ает/ретраит
            // вход, результат (уже в resultCache) не теряется. НЕ логируем как
            // deserialize.
            handleSendFailure(completeData, completionId, attemptKey, e);
        }
    }

    /**
     * WO-REL-64: отказ публикации результата — либо парковка, либо backoff и
     * проброс.
     *
     * <p>WO-INT-10 (Q3): отказ ПРАВ ({@code CompletionPublishDeniedException} —
     * 403/access_refused: неверные permissions воркера) — НЕ транспорт.
     * Отличие в реакции: счётчик + ERROR, называющий misconfig (exchange/ключ),
     * и ЕДИНИЧНАЯ попытка немедленной парковки (шкала попыток бессмысленна —
     * повтор с теми же правами даст тот же 403). Честная механика stock-прав
     * RabbitMQ (write — на exchange целиком): отказ означает, что парковаться
     * через тот же exchange тоже некуда, поэтому попытка обычно падает тем же
     * 403 — тогда и при выключенной парковке результат остаётся в resultCache,
     * а вход — неподтверждённым: штатный backoff-redelivery до починки прав
     * (видный по счётчику, самолечащийся — переотправка из кэша без повторного
     * бизнес-эффекта). Потеря результата исключена в обеих ветках.
     *
     * <p>Счётчик попыток ведётся на отправку: ключ — completionId при наличии
     * correlationId (стабилен на redelivery через resultCache) либо
     * fallback-ключ serviceTaskId#фаза без него (см. H-1 выше). N-я неудача
     * подряд при включённой парковке и {@code N >= maxCompletionAttempts} —
     * последняя: результат уходит в poison-очередь verbatim (тот же completionId —
     * движок дедуплицирует), шкала сбрасывается, вход подтверждается
     * (нормальный возврат — очередь продолжает работать). Иначе — ограниченный
     * backoff и проброс исходного исключения (вход НЕ подтверждается).
     */
    private void handleSendFailure(ServiceTaskCompleteData completeData, String completionId,
            String attemptKey, RuntimeException cause) {
        if (cause instanceof CompletionPublishDeniedException denied) {
            long n = accessDeniedCount.incrementAndGet();
            log.error("Completion {} publish DENIED by broker (access denied total={}): {} — "
                    + "worker permissions misconfigured (not a transport failure)",
                completionId, n, denied.getMessage());
            if (poisonParkingEnabled) {
                try {
                    parkPoisonedCompletion(completeData, completionId, 1, denied);
                } catch (RuntimeException parkFailure) {
                    // Штатно при stock-правах: отказ = нет write на exchange,
                    // парковаться некуда. Падаем в транспортный хвост ниже
                    // (backoff + проброс, вход не подтверждён).
                    log.error("Denied completion {} could not be parked ({}) — "
                            + "holding input unacked with backoff until permissions "
                            + "are fixed (result is cached, no business re-effect)",
                        completionId, parkFailure.getMessage());
                    handleTransportFailure(completeData, completionId, attemptKey, denied);
                    return;
                }
                long parked = poisonedCount.incrementAndGet();
                log.error("Denied completion {} parked in {} (parked total={}) — "
                    + "fix worker permissions, then redrive from poison",
                    completionId, poisonQueueName, parked);
                return;
            }
            log.error("Denied completion {} NOT parked (poison parking disabled) — "
                + "falling back to transport-style redelivery (result is cached, "
                + "input stays unacked)", completionId);
        }
        handleTransportFailure(completeData, completionId, attemptKey, cause);
    }

    /**
     * WO-REL-64: транспортный хвост отказа — шкала попыток, потолок с парковкой
     * либо backoff и проброс. Сюда же падает отказ прав, когда парковаться
     * некуда/выключено (см. выше).
     */
    private void handleTransportFailure(ServiceTaskCompleteData completeData, String completionId,
            String attemptKey, RuntimeException cause) {
        CompletionRedeliveryBackoff backoff = redeliveryBackoffRef.get();
        int attempt = backoff.recordFailedAttempt(attemptKey);
        if (poisonParkingEnabled && attempt >= maxCompletionAttempts) {
            try {
                parkPoisonedCompletion(completeData, completionId, attempt, cause);
            } catch (RuntimeException parkFailure) {
                // Сама парковка не удалась (брокер в беде): терять результат
                // нельзя — откатываемся к старому поведению (backoff + проброс).
                // Попытка уже засчитана выше, второй раз не считаем.
                long delay = backoff.awaitBeforeRedelivery(attemptKey, attempt);
                log.error("Completion {} could not be parked in {} ({}) — "
                        + "falling back to redelivery #{} in {}ms: {}",
                    completionId, poisonQueueName, parkFailure.getMessage(),
                    attempt, delay, cause.getMessage(), cause);
                throw parkFailure;
            }
            backoff.reset(attemptKey);
            long n = poisonedCount.incrementAndGet();
            log.error("Completion {} parked in {} after {} failed attempts "
                    + "(parked total={}): {} — main queue keeps flowing, "
                    + "retry with growing delay from the poison queue",
                completionId, poisonQueueName, attempt, n, cause.getMessage());
            return;
        }
        redeliveryCount.incrementAndGet();
        long delay = backoff.awaitBeforeRedelivery(attemptKey, attempt);
        log.error("Completion send failed (transport) — redelivery #{} of this send, "
                + "retrying in {}ms (retry backoff is bounded 1s..30s and never drops the job): {}",
            attempt, delay, cause.getMessage());
        throw cause;
    }

    /**
     * WO-REL-64: парковка отравленного результата — та же надёжная отправка
     * (confirm/return), что основная, плюс заголовки попыток и причины.
     * WO-INT-10: через completion-exchange, ключ — имя poison-очереди
     * (identity-биндинг движка).
     */
    private void parkPoisonedCompletion(ServiceTaskCompleteData completeData, String completionId,
            int attempt, RuntimeException cause) {
        String reason = cause.getClass().getSimpleName()
            + (cause.getMessage() != null
                ? ": " + truncate(cause.getMessage(), 500) : "");
        ConfirmedCompletionSender.sendAndConfirm(rabbitTemplate,
            CompletionTopology.COMPLETION_EXCHANGE, poisonQueueName, completeData,
            Map.of(CompletionPoisonRetryListener.HDR_ATTEMPTS, attempt,
                CompletionPoisonRetryListener.HDR_REASON, reason),
            null, confirmTimeoutMs,
            ensurePublisherConfirms && confirmsAvailable(), unroutableCount);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Тест-хук: сколько результатов сейчас закэшировано (не размер кэша Caffeine). */
    Map<String, ServiceTaskCompleteData> cachedResultsForTest() {
        return resultCache.asMap();
    }

    private static String headerAsString(Map<String, Object> headers, String key) {
        if (headers == null) {
            return null;
        }
        Object v = headers.get(key);
        return v != null ? v.toString() : null;
    }

    private static void restoreMdc(String key, String prior) {
        if (prior == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, prior);
        }
    }
}
