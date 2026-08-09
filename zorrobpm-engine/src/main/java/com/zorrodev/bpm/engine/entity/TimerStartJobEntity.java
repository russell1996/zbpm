package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A definition-scoped timer start: when {@code dueAt} passes, the scheduler starts a new instance
 * of {@code processDefinitionId} at {@code elementId}. Superseded by newer versions of the same
 * {@code processKey} at deploy time.
 */
@Getter
@Setter
@Entity
@Table(name = "timer_start_jobs")
public class TimerStartJobEntity {
    @Id
    private UUID id;
    private String processKey;
    private UUID processDefinitionId;
    private String elementId;
    private Instant dueAt;
    private boolean fired;
    private Instant createdAt;
    /**
     * Number of failed fire attempts (WO-REL-13). Incremented per-job when {@code fire} throws,
     * so a failing timer start is visible for retry instead of being silently lost.
     */
    private int attempts;
    /** Last fire failure message (WO-REL-13), null while the job never failed. */
    private String lastError;
    /**
     * WO-REL-14 (R-04): remaining repetitions after current fire, for bounded repeating timer
     * starts (e.g. R3/PT1H). Null = infinite repeat (unbounded R/... or cron). 0 = done, do not
     * reschedule. Persisted (unlike the pre-fix behavior of recomputing repeatCount from the
     * BPMN model on every fire, which meant a bounded cycle never actually exhausted).
     */
    private Integer remainingCount;
}
