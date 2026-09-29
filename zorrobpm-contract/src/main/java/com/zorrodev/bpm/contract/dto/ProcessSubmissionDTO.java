package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A process submission as seen by the UI (WO-ACL-3, WO-ACL-9). */
@Getter
@Setter
public class ProcessSubmissionDTO {
    private UUID id;
    private String processKey;
    private String name;
    /** PENDING / APPROVED / REJECTED — see engine ProcessSubmissionStatus. */
    private String status;
    private UUID submittedBy;
    /** WO-ACL-9: enriched submitter identity (null if user was deleted). */
    private String submittedByUsername;
    private String submittedByFullName;
    private String submittedByEmail;
    private Instant submittedAt;
    private UUID reviewedBy;
    /** WO-ACL-9: reviewer username (null if not yet reviewed or user was deleted). */
    private String reviewedByUsername;
    private Instant reviewedAt;
    private String rejectReason;
    private UUID approvedDefinitionId;
    private UUID previousSubmissionId;
}