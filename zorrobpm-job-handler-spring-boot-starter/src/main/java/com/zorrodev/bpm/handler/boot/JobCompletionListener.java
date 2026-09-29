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
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
        try {
            // WO-OBS-8: the completion hop carries the forwarded trace context as AMQP
            // headers (the engine-side @RabbitListener reads them via @Headers) AND
            // inside the converted body (belt and braces: the body fields feed the
            // engine-internal Spring event when headers are stripped by an
            // intermediate). Tolerant: absent when the job arrived untraced.
            rabbitTemplate.convertAndSend(completeQueueName, completeData, m -> {
                if (completeData.getTraceParent() != null) {
                    m.getMessageProperties().setHeader(TraceHeaders.TRACE_PARENT_HEADER,
                        completeData.getTraceParent());
                }
                if (completeData.getProcessInstanceId() != null) {
                    m.getMessageProperties().setHeader(TraceHeaders.PROCESS_INSTANCE_ID_HEADER,
                        completeData.getProcessInstanceId());
                }
                return m;
            });
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
