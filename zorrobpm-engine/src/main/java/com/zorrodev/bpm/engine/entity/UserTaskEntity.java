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
    /**
     * WO-C8-22: linked-form id pinned at task creation (mirror of {@code formKey} above).
     * Read by the form endpoint before {@code formKey}; null for key/external models and
     * mid-creating-phase (C8-21) marker rows — those resolve exactly as before.
     */
    private String formId;
    /**
     * WO-C8-23: resource binding pinned at task creation (mirror of the two fields above).
     * Only {@code "deployment"} changes the resolve path; {@code latest}/absent/null keep it.
     */
    private String bindingType;
    /** Resolved zeebe:taskSchedule dueDate (WO-C8-8) — informational String, never blocks execution. */
    private String dueDate;
    /** Resolved zeebe:taskSchedule followUpDate (WO-C8-8). */
    private String followUpDate;
    private String assignee;
    /** Comma-separated resolved candidate groups (WO-INT-1). */
    private String candidateGroups;
    /**
     * WO-C8-30: task priority 0-100 resolved at activation from
     * {@code zeebe:priorityDefinition} (static integer or FEEL, docs default 50 when
     * absent). Nullable for pre-WO rows — readers map null to 50.
     */
    private Integer priority;
}
