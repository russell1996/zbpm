package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
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
    /**
     * WO-IN-4: resolved assignee (value of {@code zeebe:assignmentDefinition/@assignee},
     * e.g. a numeric employeeId as computed by the FEEL expression). Additive response
     * field — pre-existing consumers ignore unknown fields; null when unassigned.
     */
    private String assignee;
    /**
     * WO-IN-4: resolved candidate groups, as a list (source of truth is the normalized
     * {@code user_task_candidates} table, role GROUP — same rows the
     * {@code ?candidateGroup=} filter matches since WO-IN-3). Additive; empty list
     * when the task has no candidate groups.
     */
    private List<String> candidateGroups;
    /**
     * WO-IN-4: resolved candidate users, as a list (role USER in
     * {@code user_task_candidates} — same rows the {@code ?candidateUser=} filter
     * matches since WO-IN-3). Additive; empty list when none.
     */
    private List<String> candidateUsers;
    private Instant createdAt;
    private Instant completedAt;
}
