package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Spring event published when a service-task job is ready to be sent to RabbitMQ (WO-EVT-2).
 * outboxId = outbox entry id, used as the RabbitMQ CorrelationData id so the broker confirm
 * can be matched back to the DB row (WO-REL-12 R-02/R-06). Consumers may use it as stable
 * messageId for deduplication (at-least-once contract).
 *
 * <p>WO-OBS-8: {@code traceParent} is the W3C traceparent of the outbox-processing span
 * (nullable — null when the entry was enqueued outside any traced context). The RabbitMQ
 * bridge copies it verbatim into the {@code traceparent} AMQP header; explicit ctor
 * overloads (not Lombok {@code @AllArgsConstructor}) so the pre-OBS-8 2-arg call sites
 * keep compiling untouched.
 */
@Getter
@Setter
@NoArgsConstructor
public class ServiceTaskEnqueued {
    private JobDetailModel detail;
    private String outboxId;
    private String traceParent;

    public ServiceTaskEnqueued(JobDetailModel detail, String outboxId) {
        this(detail, outboxId, null);
    }

    public ServiceTaskEnqueued(JobDetailModel detail, String outboxId, String traceParent) {
        this.detail = detail;
        this.outboxId = outboxId;
        this.traceParent = traceParent;
    }
}
