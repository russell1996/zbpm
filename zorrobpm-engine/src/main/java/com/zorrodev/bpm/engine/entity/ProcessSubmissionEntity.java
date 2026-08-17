package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's request to deploy a NEW process (WO-ACL-3).
 *
 * The BPMN XML is stored in the database (text column), not on disk, so a submission
 * can be approved later by a SUPER_ADMIN without any filesystem state to lose or leak.
 * Approval deploys the BPMN through the regular {@code ProcessDefinitionService} path and
 * registers the submitter as OWNER of the created process — atomically (see
 * {@code ProcessSubmissionServiceImpl.approve}).
 */
@Entity
@Table(name = "process_submission")
@Getter
@Setter
public class ProcessSubmissionEntity {

    @Id
    private UUID id;

    /** Raw BPMN XML as submitted. Stored in DB — the only copy until approval. */
    @Column(nullable = false)
    private String bpmn;

    @Column(name = "process_key", nullable = false)
    private String processKey;

    @Column
    private String name;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    /** PENDING / APPROVED / REJECTED — see {@link ProcessSubmissionStatus}. */
    @Column(nullable = false)
    private String status;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reject_reason")
    private String rejectReason;

    /** process_definitions.id created by the approval (null until APPROVED). */
    @Column(name = "approved_definition_id")
    private UUID approvedDefinitionId;

    /** Link to the user's previous submission of the same process key (resubmission chain). */
    @Column(name = "previous_submission_id")
    private UUID previousSubmissionId;
}