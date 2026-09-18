package com.zorrodev.bpm.engine.entity;

/**
 * Explicit type of an outbox entry, set at write time by the producer (WO-REL-12 R-01).
 * The batch processor routes on this value instead of guessing from payload substrings.
 */
public enum OutboxKind {
    SERVICE_TASK,
    DOMAIN_EVENT,
    /** WO-INT-5: mail delivery via outbox — same at-least-once pattern as MQ. */
    EMAIL
}
