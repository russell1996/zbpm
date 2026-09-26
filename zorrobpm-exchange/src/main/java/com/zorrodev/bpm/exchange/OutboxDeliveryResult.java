package com.zorrodev.bpm.exchange;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Broker delivery result for one outbox entry, published back from the RabbitMQ module
 * to the engine (WO-REL-12 R-02).
 *
 * <p>At-least-once contract: the outbox row is marked {@code published} ONLY after the
 * broker confirms (ACK) the message — never before {@code convertAndSend}. If the broker
 * NACKs or returns the message as unroutable, the row stays pending and is retried via the
 * existing attempts/maxRetries mechanism. A crash between broker ACK and DB commit leads to
 * a duplicate publish on restart — consumers must dedupe by the stable messageId
 * (= outbox id, carried in CorrelationData/message correlationId).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OutboxDeliveryResult {
    private String outboxId;
    /** true = broker ACKed the message; false = NACK / unroutable / delivery failure. */
    private boolean acked;
    /** Reason for non-ACK (broker reply text, exception message, ...). Null when acked. */
    private String cause;
}
