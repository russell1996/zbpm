package com.zorrodev.bpm.engine.entity;

/**
 * Lifecycle of a process submission (WO-ACL-3).
 * PENDING — waiting for a SUPER_ADMIN review; APPROVED — deployed and the submitter
 * became OWNER of the process; REJECTED — declined by a reviewer with a reason.
 */
public enum ProcessSubmissionStatus {
    PENDING,
    APPROVED,
    REJECTED
}