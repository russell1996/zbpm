package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "user_tasks")
public class UserTaskEntity {
    @Id
    private UUID id;
    private String bpmnElementId;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private Instant createdAt;
    private Instant completedAt;
    private String formKey;
    /** Resolved zeebe:taskSchedule dueDate (WO-C8-8) — informational String, never blocks execution. */
    private String dueDate;
    /** Resolved zeebe:taskSchedule followUpDate (WO-C8-8). */
    private String followUpDate;
    private String assignee;
    /** Comma-separated resolved candidate groups (WO-INT-1). */
    private String candidateGroups;
    /**
     * WO-C8-21: index of the in-flight creating task listener (mirror of
     * {@code pendingListenerIndex} on service tasks, WO-C8-11). The row is the durable
     * phase marker: it is written when the phase opens (task fields still null, task
     * invisible) and cleared when the last listener completes (the task is created then).
     * Null = no phase in flight. Never magic values.
     */
    private Integer pendingCreatingListenerIndex;
}
