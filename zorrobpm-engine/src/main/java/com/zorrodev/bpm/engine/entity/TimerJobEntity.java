package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A scheduled timer for a token parked at a timer catch event. When {@code dueAt} passes the
 * timer scheduler fires the job and signals the {@code activityId}, resuming the token.
 */
@Getter
@Setter
@Entity
@Table(name = "timer_jobs")
public class TimerJobEntity {
    @Id
    private UUID id;
    private UUID activityId;
    private Instant dueAt;
    private boolean fired;
    private Instant createdAt;
    /**
     * When set, this is an interrupting timer boundary on {@code activityId}: firing cancels that
     * host activity and continues from this boundary element's outgoing flows. Null = catch timer.
     */
    private String boundaryElementId;
    /** For an event-subprocess timer trigger: the instance and the event sub-process to start when due
     *  (no host {@code activityId}). */
    private UUID processInstanceId;
    private String eventSubprocessId;
    /**
     * For bounded repeating timers (e.g. R3/PT1S): remaining repetitions after current fire.
     * Null = infinite repeat. 0 = done (no more re-arm).
     */
    private Integer remainingCount;
    /**
     * WO-REL-14 (R-04): the original {@code timeCycle} expression (e.g. {@code R3/PT1H}),
     * persisted so re-arm computes the next occurrence from the REAL interval instead of a
     * hardcoded zero-second cycle. Null for non-repeating (one-shot / boundary) timers.
     */
    private String expression;
    /**
     * Number of failed fire attempts (WO-REL-13). Incremented per-job when {@code fire} throws,
     * so a failing timer is visible for retry/quarantine instead of being silently lost.
     */
    private int attempts;
    /** Last fire failure message (WO-REL-13), null while the job never failed. */
    private String lastError;
}
