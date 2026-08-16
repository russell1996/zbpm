package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A process submission as seen by the UI (WO-ACL-3). */
@Getter
@Setter
public class ProcessSubmissionDTO {
    private UUID id;
    private String processKey;
    private String name;
    /** PENDING / APPROVED / REJECTED — see engine ProcessSubmissionStatus. */
    private String status;
    private UUID submittedBy;
    private Instant submittedAt;
    private UUID reviewedBy;
    private Instant reviewedAt;
    private String rejectReason;
    private UUID approvedDefinitionId;
    private UUID previousSubmissionId;
}