package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.engine.security.Principal;

import java.util.List;
import java.util.UUID;

/**
 * Process submissions (WO-ACL-3): users request deployment of NEW processes, SUPER_ADMINs
 * approve (submitter becomes OWNER) or reject with a reason.
 */
public interface ProcessSubmissionService {

    /**
     * Validate and persist a submission. Rejects BPMN that does not parse, a process key
     * that already exists in the registry, or a key violating the naming convention.
     *
     * @param bpmn      raw BPMN XML
     * @param principal authenticated user (never null — the resource layer guarantees it)
     * @return the created submission
     */
    ProcessSubmissionDTO submit(String bpmn, Principal principal);

    /** The user's own submissions, newest first. */
    List<ProcessSubmissionDTO> listMine(UUID userId);

    /**
     * Review queue, oldest first. WO-ACL-12: optional status filter — null/blank keeps the
     * previous behavior (PENDING only); otherwise one of PENDING/APPROVED/REJECTED/SUPERSEDED
     * or ALL for every status.
     *
     * @param status filter value or null for the default PENDING queue
     */
    List<ProcessSubmissionDTO> listPending(String status);

    /**
     * WO-ACL-7: the raw BPMN stored with the submission — the reviewer must be able to
     * see the model before approving it. The resource layer enforces SUPER_ADMIN.
     *
     * @throws org.springframework.web.server.ResponseStatusException 404 if the submission does not exist
     */
    String getBpmn(UUID submissionId);

    /**
     * Approve a PENDING submission: deploy the stored BPMN, register the submitter as OWNER
     * of the created process, mark the submission APPROVED — all in ONE transaction.
     *
     * @param admin the SUPER_ADMIN principal performing the approval (never null — the
     *              resource layer guarantees it)
     * @throws org.springframework.web.server.ResponseStatusException 409 if the submission
     *         is not PENDING or the process key was already created (first approval wins)
     */
    ProcessSubmissionDTO approve(UUID submissionId, Principal admin);

    /** Reject a PENDING submission with a mandatory reason. */
    ProcessSubmissionDTO reject(UUID submissionId, String reason, Principal admin);
}