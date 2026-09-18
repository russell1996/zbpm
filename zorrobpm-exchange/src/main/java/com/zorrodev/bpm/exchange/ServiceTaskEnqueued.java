package com.zorrodev.bpm.exchange;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Spring event published when a service-task job is ready to be sent to RabbitMQ (WO-EVT-2).
 * outboxId = outbox entry id, used as the RabbitMQ CorrelationData id so the broker confirm
 * can be matched back to the DB row (WO-REL-12 R-02/R-06). Consumers may use it as stable
 * messageId for deduplication (at-least-once contract).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ServiceTaskEnqueued {
    private JobDetailModel detail;
    private String outboxId;
}
