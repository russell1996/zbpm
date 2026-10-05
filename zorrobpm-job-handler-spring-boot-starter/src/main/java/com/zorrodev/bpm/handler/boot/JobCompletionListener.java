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
     * результата. Отправка идёт с per-send {@code CorrelationData} + mandatory;
     * после send — синхронное ожидание брокерского confirm
     * ({@code waitForConfirmsOrDie}, требует confirm-type CORRELATED — ставит
     * {@code HandlerAutoConfiguration} при {@code ensurePublisherConfirms}).
     * NACK/timeout/return → исключение → вход НЕ подтверждается (retry/NACK
     * контейнера), результат уже в resultCache — восстанавливаем без повторного
     * бизнес-эффекта. Выключатель — {@code ensurePublisherConfirms=false}
     * (тогда только синхронные исключения, как до WO).
     */
    private boolean ensurePublisherConfirms = true;
    private long confirmTimeoutMs = 5_000L;

    /** WO-C8-36: ставит {@code HandlerAutoConfiguration} из пропертей (тесты — дефолт). */
    public void setEnsurePublisherConfirms(boolean ensurePublisherConfirms) {
        this.ensurePublisherConfirms = ensurePublisherConfirms;
    }

    /** WO-C8-36: ставит {@code HandlerAutoConfiguration} из пропертей (тесты — дефолт). */
    public void setConfirmTimeoutMs(long confirmTimeoutMs) {
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    /**
     * WO-C8-36 (red-team 1.2 + пересмотр HOLD-6): НЕ держим разделяемый сет
     * возвратов. Немаршрутизируемость читается с САМОЙ отправки —
     * {@link CorrelationData#getReturned()}, который spring-amqp заполняет в
     * {@code basic.return}-обработчике ({@code PublisherCallbackChannelImpl}).
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

    /** WO-C8-36: сколько completion'ов ушло без confirm-wait (fallback). */
    public long confirmsUnavailableCountForTest() {
        return confirmsUnavailableCount.get();
    }

    /** WO-C8-36: сколько completion'ов поймано как unroutable. */
    public long unroutableCountForTest() {
        return unroutableCount.get();
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
            // Malformed payload: тихо, без отправки, без проброса (см. javadoc).
            log.error("Skipping malformed message for queue {}: {}", queueName, e.getMessage());
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
                // Это остаточный риск, а не «ложного успеха нет». Чистка ниже
                // убирает только МУСОР сета (опоздавшие id старше TTL — за это
                // время любой return уже пришёл), доставку она не чинит и не
                // обязана: TTL удаляет строку, не возвращает результат.
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
            log.warn("Completion send failed (transport), message will be redelivered: {}", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // Не-AMQP сбой отправки (сериализация конвертера и т.п.) — та же семантика.
            log.warn("Completion send failed (transport), message will be redelivered: {}", e.getMessage());
            throw e;
        }
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
