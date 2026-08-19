package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.contract.dto.RejectSubmissionDTO;
import com.zorrodev.bpm.contract.dto.SubmitProcessSubmissionDTO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.UUID;

/**
 * Process submissions (WO-ACL-3): a user asks for a NEW process to be deployed,
 * a SUPER_ADMIN approves (submitter becomes OWNER) or rejects with a reason.
 */
public interface ProcessSubmissionContract {

    /** Submit a new process for review. Any authenticated user. */
    @PostExchange("/process-submissions")
    ProcessSubmissionDTO submit(@RequestBody SubmitProcessSubmissionDTO dto);

    /** The current user's submissions, newest first. Any authenticated user. */
    @GetExchange("/process-submissions/mine")
    List<ProcessSubmissionDTO> listMine();

    /**
     * Review queue, oldest first. SUPER_ADMIN only. WO-ACL-12: optional {@code status}
     * filter — PENDING (default, previous behavior) | APPROVED | REJECTED | SUPERSEDED | ALL.
     */
    @GetExchange("/process-submissions")
    List<ProcessSubmissionDTO> listPending(@RequestParam(value = "status", required = false) String status);

    /**
     * WO-ACL-7: the raw BPMN of a submission, so a reviewer can SEE the model before
     * approving it. SUPER_ADMIN only — mirrors /process-definitions/{id}/xml.
     */
    @GetExchange(url = "/process-submissions/{id}/bpmn", accept = MediaType.APPLICATION_JSON_VALUE)
    String getSubmissionBpmn(@PathVariable UUID id);

    /** Deploy the submitted BPMN and register the submitter as OWNER. SUPER_ADMIN only. */
    @PostExchange("/process-submissions/{id}/approve")
    ProcessSubmissionDTO approve(@PathVariable UUID id);

    /** Decline the submission with a mandatory reason. SUPER_ADMIN only. */
    @PostExchange("/process-submissions/{id}/reject")
    ProcessSubmissionDTO reject(@PathVariable UUID id, @RequestBody RejectSubmissionDTO dto);
}