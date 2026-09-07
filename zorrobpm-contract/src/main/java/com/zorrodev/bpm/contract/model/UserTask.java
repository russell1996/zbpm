package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class UserTask {
    private UUID id;
    private String code;
    private String name;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String formKey;
    /** Resolved zeebe:taskSchedule dueDate (WO-C8-8) — informational, for Tasklist-style UI. */
    private String dueDate;
    /** Resolved zeebe:taskSchedule followUpDate (WO-C8-8). */
    private String followUpDate;
    /** Activity lifecycle status: CREATED / IN_PROGRESS / COMPLETED / CANCELLED / ERROR. */
    private String status;
    /**
     * WO-C8-30: task priority 0-100 for the task list (docs default 50).
     * Additive response field — pre-existing consumers ignore unknown fields.
     */
    private Integer priority;
    private Instant createdAt;
    private Instant completedAt;
}
