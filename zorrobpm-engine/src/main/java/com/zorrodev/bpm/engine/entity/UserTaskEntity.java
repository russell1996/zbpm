package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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
    /**
     * WO-IN-4: task id == activity id — authoritative lifecycle lives on the activity row.
     * Read-only view ({@code insertable/updatable = false}: the row is written by
     * {@code UserTaskDbOperationsImpl}, never through this association) used ONLY as an
     * {@code @EntityGraph} path on the paged {@code findAll} — the status then rides the
     * page query as a to-one JOIN (one statement, pagination-safe, unlike a collection
     * fetch). LAZY, {@code optional = true} DESPITE the domain invariant (the activity
     * always exists by construction): with {@code optional = false} Hibernate issues a
     * strict existence check that fails on live rows for this shared-PK mapping
     * (proven live: JpaObjectRetrievalFailure on existing activities, fixed by this
     * flag alone). Non-graph readers (findById in DbOperations/TaskFormDataService/…)
     * get a proxy with ZERO extra queries until touched — and nothing touches it
     * outside the mapper.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "id", referencedColumnName = "id", insertable = false, updatable = false)
    private ActivityEntity activity;
    /** Comma-separated resolved candidate groups (WO-INT-1). */
    private String candidateGroups;
    /**
     * WO-C8-30: task priority 0-100 resolved at activation from
     * {@code zeebe:priorityDefinition} (static integer or FEEL, docs default 50 when
     * absent). Nullable for pre-WO rows — readers map null to 50.
     */
    private Integer priority;
}
