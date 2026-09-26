package com.zorrodev.bpm.engine.entity;

/**
 * WO-QW-4 (NEW-16e): lifecycle state of an outbox entry. String-typed before
 * ({@code "PENDING"}/{@code "FAILED"} literals scattered across the entity,
 * repository JPQL, the delivery listener and the admin resource) — a typo
 * like {@code "FALIED"} compiled fine and silently matched nothing. Same
 * shape as {@link OutboxKind} (STRING-mapped, so the column stays
 * {@code varchar} and existing rows keep working — no migration).
 */
public enum OutboxStatus {
    PENDING,
    FAILED
}
