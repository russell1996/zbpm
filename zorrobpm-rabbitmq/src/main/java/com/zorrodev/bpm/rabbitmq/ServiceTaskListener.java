package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import com.zorrodev.bpm.exchange.TraceHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceTaskListener {

    private final JobQueueDeclarer jobQueueDeclarer;
    private final RabbitTemplate rabbitTemplate;
    private final ApplicationEventPublisher publisher;

    @EventListener
    public void on(ServiceTaskEnqueued event) {
        JobDetailModel detail = event.getDetail();

        // WO-REL-16: normally a no-op — the queue was already declared when the definition was
        // deployed or at startup. Kept as the fallback for job types those paths did not cover
        // (an older definition version still running, or a broker that was down back then).
        // Replaces a per-message amqpAdmin.getQueueInfo(), which cost a broker round-trip on
        // every single send and closed the channel whenever the queue did not exist yet.
        jobQueueDeclarer.declare(detail.getJob());
        String queueName = JobQueueDeclarer.queueNameFor(detail.getJob());

        // WO-REL-12 (R-02/R-06): CorrelationData id = outbox entry id → broker ACK is matched
        // back to the DB row (markPublished only after confirmation). The id is also carried
        // on the message properties so the return callback can match unroutable messages.
        // WO-OBS-8: W3C trace context rides the same hop as AMQP headers (tolerant: null —
        // and therefore absent — when the outbox entry was enqueued outside any trace).
        String traceParent = event.getTraceParent();
        String processInstanceId = detail.getProcessInstanceId() != null
            ? detail.getProcessInstanceId().toString() : null;
        rabbitTemplate.convertAndSend(
            queueName,
            detail,
            m -> {
                m.getMessageProperties().setCorrelationId(event.getOutboxId());
                if (traceParent != null) {
                    m.getMessageProperties().setHeader(TraceHeaders.TRACE_PARENT_HEADER, traceParent);
                }
                if (processInstanceId != null) {
                    m.getMessageProperties().setHeader(TraceHeaders.PROCESS_INSTANCE_ID_HEADER, processInstanceId);
                }
                return m;
            },
            new CorrelationData(event.getOutboxId()));
        log.info("Sent data for job {} to {}", detail.getJob(), queueName);
    }

    @RabbitListener(queues = com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration.COMPLETE_QUEUE)
    public void on(ServiceTaskCompleteData data, @Headers Map<String, Object> headers) {
        // WO-OBS-8: continue the worker's trace on the completion hop. This module has no
        // OTel SDK (exchange-only), so no span here — MDC + verbatim forward into the
        // engine-internal event; the engine-side listener (same thread, Spring event)
        // opens the real child span from the forwarded traceparent. Save/restore: the
        // listener container reuses threads, never leak one completion's MDC into the next.
        String traceParent = headerAsString(headers, TraceHeaders.TRACE_PARENT_HEADER);
        String processInstanceId = headerAsString(headers, TraceHeaders.PROCESS_INSTANCE_ID_HEADER);
        String priorTraceId = MDC.get(TraceHeaders.MDC_TRACE_ID);
        String priorPi = MDC.get(TraceHeaders.MDC_PROCESS_INSTANCE_ID);
        String mdcTraceId = TraceHeaders.extractTraceId(traceParent);
        if (mdcTraceId != null) {
            MDC.put(TraceHeaders.MDC_TRACE_ID, mdcTraceId);
        }
        if (processInstanceId != null) {
            MDC.put(TraceHeaders.MDC_PROCESS_INSTANCE_ID, processInstanceId);
        }
        try {
            log.info("Service task to complete message received - {}", data.getServiceTaskId());
            ServiceTaskCompleted serviceTaskCompleted = new ServiceTaskCompleted();
            serviceTaskCompleted.setServiceTaskId(data.getServiceTaskId());
            serviceTaskCompleted.setStatus(data.getStatus());
            serviceTaskCompleted.setErrorMessage(data.getErrorMessage());
            serviceTaskCompleted.setVariables(data.getVariables());
            serviceTaskCompleted.setTraceParent(traceParent);
            serviceTaskCompleted.setProcessInstanceId(processInstanceId);
            publisher.publishEvent(serviceTaskCompleted);
        } finally {
            restoreMdc(TraceHeaders.MDC_TRACE_ID, priorTraceId);
            restoreMdc(TraceHeaders.MDC_PROCESS_INSTANCE_ID, priorPi);
        }
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
