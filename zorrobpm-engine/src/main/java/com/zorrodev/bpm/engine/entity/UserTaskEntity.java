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
}
