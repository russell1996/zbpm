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
}
