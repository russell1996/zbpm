package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.AssignUserTaskDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.exception.FormValidationException;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormArtifactService;
import com.zorrodev.bpm.engine.service.FormValidator;
import com.zorrodev.bpm.engine.service.RuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserTaskRuntimeOperationsImpl implements UserTaskRuntimeOperations {

    private final UserTaskRepository userTaskRepository;
    private final RuntimeService runtimeService;
    private final AuditLogService auditLogService;
    private final FormArtifactService formArtifactService;
    private final DBService dbService;
    private final AuthorizationService authorizationService;
    private final RuntimeOperationSupport runtimeOperationSupport;

    @Transactional
    @Override
    public IdDTO completeUserTask(UUID id, CompleteTaskDTO dto) {
        Principal principal = runtimeOperationSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        UserTaskEntity task = userTaskRepository.findById(id).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found");
        }

        if (!authorizationService.canCompleteUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Also check assignee (existing check, refactored to use principal)
        runtimeOperationSupport.checkAssignee(principal, task);

        // ADR-6 §D9: form validation via FormArtifactService facade
        if (dto.getVariables() != null && !dto.getVariables().isEmpty()) {
            List<FormValidator.ValidationError> errors = formArtifactService.validateFormIfApplicable(
                task.getFormKey(), dto.getVariables());
            if (!errors.isEmpty()) {
                throw new FormValidationException(errors);
            }
        }

        String onBehalfOf = runtimeOperationSupport.checkedOnBehalfOf();
        // WO-INT-4 criterion 9: a service key's attribution claim must be verifiable —
        // the named user has to be the assignee or a candidate for this task.
        if (onBehalfOf != null) {
            runtimeOperationSupport.requireOnBehalfMatchesTask(task, onBehalfOf);
        }
        IdDTO result;
        try {
            result = Optional.ofNullable(runtimeService.completeUserTask(id, dto.getVariables())).map(runtimeOperationSupport::toDTO).orElseThrow();
        } catch (com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException e) {
            // WO-C8-24: repeat complete while completing listeners run — 409, never 500
            // (dedicated type, so no other failure is masked into a conflict).
            log.warn("Complete of user task {} while completing listeners run: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        auditLogService.record(runtimeOperationSupport.getPrincipal(), "COMPLETE_USER_TASK", runtimeOperationSupport.resolveDefinitionKeyByInstance(task.getProcessInstanceId()), id.toString(),
            onBehalfOf != null ? "[claimed] " + onBehalfOf : null);
        return result;
    }

    @Transactional
    @Override
    public IdDTO claimUserTask(UUID id) {
        Principal principal = runtimeOperationSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        UserTaskEntity task = userTaskRepository.findById(id).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found");
        }
        if (task.getCompletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User task is already completed");
        }
        if (task.getAssignee() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User task is already assigned");
        }

        if (!authorizationService.canClaimUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Assignee: X-On-Behalf-Of header (verified against candidates — WO-INT-4),
        // otherwise principal id
        String assignee = runtimeOperationSupport.checkedOnBehalfOf();
        if (assignee == null || assignee.isBlank()) {
            assignee = runtimeOperationSupport.resolvePrincipalId(principal);
        } else {
            // WO-INT-4 criterion 9: the claimed user must be a candidate for this task.
            // (An unassigned task cannot match by assignee, so candidate/process-member
            // rules apply — the same rules as canClaimUserTask for a real user.)
            runtimeOperationSupport.requireOnBehalfMatchesTask(task, assignee);
        }

        // Atomic claim can still lose the race to a concurrent claimant between the check above
        // and the update → surface as 409, not a 500.
        try {
            dbService.claimUserTask(id, assignee);
        } catch (IllegalStateException e) {
            log.warn("Failed to claim user task {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Failed to claim user task");
        }
        auditLogService.record(runtimeOperationSupport.getPrincipal(), "CLAIM_USER_TASK", runtimeOperationSupport.resolveDefinitionKeyByInstance(task.getProcessInstanceId()), id.toString(), assignee);

        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    @Transactional
    @Override
    public IdDTO unclaimUserTask(UUID id) {
        Principal principal = runtimeOperationSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        UserTaskEntity task = userTaskRepository.findById(id).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found");
        }
        if (task.getCompletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User task is already completed");
        }

        if (!authorizationService.canClaimUserTask(principal, task.getProcessInstanceId(), task.getCandidateGroups())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        dbService.unclaimUserTask(id);
        auditLogService.record(runtimeOperationSupport.getPrincipal(), "UNCLAIM_USER_TASK", runtimeOperationSupport.resolveDefinitionKeyByInstance(task.getProcessInstanceId()), id.toString());

        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    @Transactional
    @Override
    public IdDTO assignUserTask(UUID id, AssignUserTaskDTO dto) {
        Principal principal = runtimeOperationSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        if (dto == null || dto.getAssignee() == null || dto.getAssignee().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignee is required");
        }

        UserTaskEntity task = userTaskRepository.findById(id).orElse(null);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found");
        }
        if (task.getCompletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User task is already completed");
        }

        // Reassign is administrative: requires owner/admin (stricter than claim's candidate check).
        if (!authorizationService.canReassignUserTask(principal, task.getProcessInstanceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // WO-INT-4: candidates are filtered in the member search — assignment itself is not
        // type-guarded (a system account is an ordinary account; the type is a marker).

        dbService.assignUserTask(id, dto.getAssignee());
        auditLogService.record(runtimeOperationSupport.getPrincipal(), "ASSIGN_USER_TASK", runtimeOperationSupport.resolveDefinitionKeyByInstance(task.getProcessInstanceId()), id.toString(), dto.getAssignee());

        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }
}
