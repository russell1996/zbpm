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
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
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
     * Проводка та же: send → очередь → чтение независимым наблюдателем.
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
     * САМОЙ отправки — {@link CorrelationData#getReturned()}, который spring-amqp
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
     * {@code waitForConfirmsOrDie} на канале без confirms кидает ПОСЛЕ успешной
     * публикации → каждый redelivery публиковал бы ещё один дубликат (поймано
     * живьём: 14k сообщений в очереди — см. отчёт). Поэтому wait — только при
     * реальных confirms (прод-стартер ставит CORRELATED); прямое использование
     * без confirms — старое поведение (sync-исключения) + один warn.
     */
    private boolean confirmsAvailable() {
        try {
            ConnectionFactory cf = rabbitTemplate.getConnectionFactory();
            return cf != null && cf.isPublisherConfirms();
        } catch (RuntimeException e) {
            return false;
        }
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
            sendCompletion(cached);
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
        sendCompletion(completeData);
    }

    private void sendCompletion(ServiceTaskCompleteData completeData) {
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
        CorrelationData correlationData = new CorrelationData(completionId);
        try {
            // WO-C8-36 (red-team blocker 1.1): RabbitTemplate.waitForConfirmsOrDie
            // ТРЕБУЕТ scope invoke(...) — вне его бросает IllegalStateException
            // («This operation is only available within the scope of an invoke
            // operation», подтверждено байткодом spring-rabbit 4.0.4: метод читает
            // ThreadLocal dedicatedChannels). Наш send идёт обычным
            // convertAndSend, поэтому каждый completion падал бы с
            // IllegalStateException → NACK входа → после retry-окна задание в DLQ,
            // т.е. фикс давал противоположность заявленной гарантии.
            // Заменяем на per-send confirm-future: корреляция ровно с ЭТОЙ
            // отправкой (channel-wide waitForConfirms при concurrency 3-5 ждал бы
            // чужие confirms и давал ложные NACK).
            // WO-OBS-8: the completion hop carries the forwarded trace context as AMQP
            // headers (the engine-side @RabbitListener reads them via @Headers) AND
            // inside the converted body (belt and braces: the body fields feed the
            // engine-internal Spring event when headers are stripped by an
            // intermediate). Tolerant: absent when the job arrived untraced.
            rabbitTemplate.convertAndSend(completeQueueName, completeData, m -> {
                m.getMessageProperties().setCorrelationId(completionId);
                if (completeData.getTraceParent() != null) {
                    m.getMessageProperties().setHeader(TraceHeaders.TRACE_PARENT_HEADER,
                        completeData.getTraceParent());
                }
                if (completeData.getProcessInstanceId() != null) {
                    m.getMessageProperties().setHeader(TraceHeaders.PROCESS_INSTANCE_ID_HEADER,
                        completeData.getProcessInstanceId());
                }
                return m;
            }, correlationData);
            if (ensurePublisherConfirms && confirmsAvailable()) {
                // Синхронный per-send confirm: NACK/timeout/разрыв до confirm →
                // исключение (тот же transport-проброс, что выше — вход не
                // подтверждается). Unroutable даёт confirm ack=true ПОСЛЕ
                // basic.return — возврат ловится отдельно ниже (голый ack
                // доставке не равен).
                //
                // WO-C8-36 (red-team HOLD-6 re-pass): порядок return-vs-confirm.
                // Брокер шлёт basic.return ДО confirm-ack на том же канале;
                // spring-amqp доставляет оба колбэка последовательно через
                // executor соединения, а future завершается только по confirm —
                // в штатном случае к моменту проверки callback уже отработал и
                // id в сете. Строгого happens-before спецификация executor'а не
                // даёт — остаточное окно (return опоздал ПОСЛЕ confirm): текущий
                // send засчитан успехом, вход ACK'нут, а сообщение
                // немаршрутизируемо = результат потерян (CR-13-режим в
                // миниатюре; окно микроскопическое — return идёт до confirm на том
                // же канале, опоздание требует переупорядочивания в executor'е).
                // Это остаточный риск, а не «ложного успеха нет»: он не чинится
                // TTL-чисткой (сет возвратов удалён в re-pass HOLD-6) и не чинится
                // повтором — чтобы сузить его до нуля, нужен was-accepted-сигнал
                // брокера, которого в AMQP 0-9-1 нет. Оставлен явным, не спрятан.
                //
                // Per-send future вместо waitForConfirmsOrDie: у того есть
                // invoke-scope-требование (см. комментарий выше про dedicatedChannels),
                // а channel-wide барьер при concurrency 3-5 ждал бы чужие confirms.
                try {
                    CorrelationData.Confirm confirm =
                        correlationData.getFuture().get(confirmTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (!confirm.isAck()) {
                        throw new AmqpException("Completion " + completionId
                            + " was NACKed by broker: " + confirm.getReason()
                            + " — will be redelivered");
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new AmqpException("Completion " + completionId
                        + " confirm failed: " + e.getCause(), e.getCause());
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new AmqpException("Completion " + completionId
                        + " was not confirmed within " + confirmTimeoutMs + "ms"
                        + " — will be redelivered", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AmqpException("Completion " + completionId
                        + " confirm wait was interrupted — will be redelivered", e);
                }
                // Голый confirm ack НЕ равен доставке: брокер подтверждает ПРИЁМ
                // и для немаршрутизируемого сообщения (сперва basic.return, потом
                // ack). Ответ лежит на самой отправке (см. javadoc про
                // getReturned) — гонки нет, чужой отправки не коснёмся.
                org.springframework.amqp.core.ReturnedMessage returnedMessage =
                    correlationData.getReturned();
                if (returnedMessage != null) {
                    unroutableCount.incrementAndGet();
                    throw new AmqpException("Completion " + completionId
                        + " returned as unroutable by broker (mandatory): "
                        + returnedMessage.getReplyCode() + " " + returnedMessage.getReplyText()
                        + " — will be redelivered");
                }
                // Доставка подтверждена и маршрутизируема: сбрасываем шкалу
                // backoff для этой отправки, иначе задание, у которого сначала
                // был немаршрутизируемый маршрут, осталось бы на 30с навсегда.
                redeliveryBackoffRef.get().reset(completionId);
            } else if (ensurePublisherConfirms) {
                long n = confirmsUnavailableCount.incrementAndGet();
                log.warn("Publisher confirms unavailable on worker connection factory "
                    + "(HandlerAutoConfiguration sets CORRELATED in prod) — completion {} "
                    + "falls back to sync-exceptions-only (fallback #{} until confirms appear)",
                    completionId, n);
            }
        } catch (AmqpException e) {
            // Transport failure: проброс наружу — контейнер NACK'ает/ретраит вход,
            // результат (уже в resultCache) не теряется. НЕ логируем как deserialize.
            backOffBeforeRedelivery(completionId, e);
            throw e;
        } catch (RuntimeException e) {
            // Не-AMQP сбой отправки (сериализация конвертера и т.п.) — та же семантика.
            backOffBeforeRedelivery(completionId, e);
            throw e;
        }
    }

    /**
     * WO-C8-36 (M-1): перед тем как отказ уйдёт наружу (и контейнер сделает
     * requeue), выдерживаем ограниченный экспоненциальный интервал.
     *
     * <p>Ключ счётчика — {@code completionId}: он стабилен на переотправке одной
     * отправки (результат переигрывается из кэша), поэтому наши же повторы
     * копятся в одну шкалу, а разные задания не замедляют друг друга.
     *
     * <p>Лог — ERROR, а не WARN: без задержки это был тихий цикл, и WARN на
     * каждой итерации его не делал заметным. Сообщение называет попытку и
     * интервал, чтобы по логу было видно, что темп действительно растёт.
     */
    private void backOffBeforeRedelivery(String completionId, Exception cause) {
        CompletionRedeliveryBackoff backoff = redeliveryBackoffRef.get();
        int attempt = backoff.recordFailedAttempt(completionId);
        redeliveryCount.incrementAndGet();
        long delay = backoff.awaitBeforeRedelivery(completionId, attempt);
        log.error("Completion send failed (transport) — redelivery #{} of this send, "
                + "retrying in {}ms (retry backoff is bounded 1s..30s and never drops the job): {}",
            attempt, delay, cause.getMessage());
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
