package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.contract.dto.RejectSubmissionDTO;
import com.zorrodev.bpm.contract.dto.SubmitProcessSubmissionDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** PENDING review queue, oldest first. SUPER_ADMIN only. */
    @GetExchange("/process-submissions")
    List<ProcessSubmissionDTO> listPending();

    /** Deploy the submitted BPMN and register the submitter as OWNER. SUPER_ADMIN only. */
    @PostExchange("/process-submissions/{id}/approve")
    ProcessSubmissionDTO approve(@PathVariable UUID id);

    /** Decline the submission with a mandatory reason. SUPER_ADMIN only. */
    @PostExchange("/process-submissions/{id}/reject")
    ProcessSubmissionDTO reject(@PathVariable UUID id, @RequestBody RejectSubmissionDTO dto);
}