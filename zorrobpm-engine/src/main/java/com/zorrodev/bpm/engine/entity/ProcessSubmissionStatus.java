package com.zorrodev.bpm.engine.entity;

/**
 * Lifecycle of a process submission (WO-ACL-3).
 * PENDING — waiting for a SUPER_ADMIN review; APPROVED — deployed and the submitter
 * became OWNER of the process; REJECTED — declined by a reviewer with a reason;
 * SUPERSEDED — a duplicate PENDING replaced by a newer one for the same process key
 * (WO-ACL-12 migration: history is kept, nothing is deleted).
 */
public enum ProcessSubmissionStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SUPERSEDED
}