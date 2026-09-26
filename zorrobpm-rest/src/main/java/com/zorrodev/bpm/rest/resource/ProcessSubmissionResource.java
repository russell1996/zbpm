package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.ProcessSubmissionContract;
import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.contract.dto.RejectSubmissionDTO;
import com.zorrodev.bpm.contract.dto.SubmitProcessSubmissionDTO;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.ProcessSubmissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;

/**
 * WO-ACL-3: process submissions. Submitting and listing one's own submissions require any
 * authenticated user; the review queue, approval and rejection are SUPER_ADMIN-only.
 */
@RestController
@RequiredArgsConstructor
public class ProcessSubmissionResource implements ProcessSubmissionContract {

    private final ProcessSubmissionService submissionService;
    private final HttpServletRequest request;
    private final HttpServletResponse httpResponse;

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private Principal requireUserPrincipal() {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!(principal instanceof Principal.UserPrincipal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Process submissions are a user self-service — API keys cannot use this endpoint");
        }
        return principal;
    }

    private void requireSuperAdmin() {
        Principal principal = getPrincipal();
        if (principal == null || !principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPER_ADMIN required");
        }
    }

    /**
     * WO-API-1 (API-1): create → 201 + Location (контракт не тронут: тело то же,
     * статус/заголовок — через `@ResponseStatus` + инжектированный response).
     */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public ProcessSubmissionDTO submit(@Valid @RequestBody SubmitProcessSubmissionDTO dto) {
        Principal principal = requireUserPrincipal();
        if (dto == null || dto.getBpmn() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BPMN XML is required");
        }
        ProcessSubmissionDTO result = submissionService.submit(dto.getBpmn(), principal);
        if (httpResponse != null) {
            httpResponse.setHeader("Location", "/process-submissions/" + result.getId());
        }
        return result;
    }

    @Override
    public List<ProcessSubmissionDTO> listMine() {
        Principal principal = requireUserPrincipal();
        return submissionService.listMine(((Principal.UserPrincipal) principal).userId());
    }

    @Override
    public List<ProcessSubmissionDTO> listPending(String status) {
        requireSuperAdmin();
        return submissionService.listPending(status);
    }

    /**
     * WO-ACL-7: raw BPMN of a submission — the reviewer sees the model BEFORE approving.
     * SUPER_ADMIN only (mirrors {@code /process-definitions/{id}/xml}): an admin approving
     * a model they cannot look at turns approval into theatre.
     */
    @Override
    public String getSubmissionBpmn(@PathVariable UUID id) {
        requireSuperAdmin();
        return submissionService.getBpmn(id);
    }

    @Override
    public ProcessSubmissionDTO approve(@PathVariable UUID id) {
        requireSuperAdmin();
        return submissionService.approve(id, requireUserPrincipal());
    }

    @Override
    public ProcessSubmissionDTO reject(@PathVariable UUID id, @Valid @RequestBody RejectSubmissionDTO dto) {
        requireSuperAdmin();
        String reason = dto == null ? null : dto.getReason();
        return submissionService.reject(id, reason, requireUserPrincipal());
    }
}