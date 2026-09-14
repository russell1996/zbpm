package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.handler.CancelingPhaseService;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormArtifactService;
import com.zorrodev.bpm.engine.service.FormValidator;
import com.zorrodev.bpm.engine.service.ProcessInstanceLifecycleService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.RuntimeSupportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WO-DEBT-7 S10 — thin facade over {@link ProcessInstanceLifecycleService}:
 * auth checks + delegation. All JPA (definition lookup for the START key
 * and the single {@code .save()} for the initiator) lives in the service,
 * inside this class' transaction (no {@code @Transactional} on the service —
 * same as the original layout, proven by
 * {@code ProcessInstanceRuntimeTransactionalIT}: audit failure rolls the save
 * back). Zero direct persistence imports.
 */
@Service
@RequiredArgsConstructor
public class ProcessInstanceRuntimeOperationsImpl implements ProcessInstanceRuntimeOperations {

    private final RuntimeService runtimeService;
    private final ProcessInstanceLifecycleService processInstanceLifecycleService;
    private final FormArtifactService formArtifactService;
    private final DBService dbService;
    private final CancelingPhaseService cancelingPhaseService;
    private final AuditLogService auditLogService;
    private final RuntimeSupportService runtimeSupportService;
    private final RuntimeOperationSupport runtimeOperationSupport;

    @Transactional
    @Override
    public IdDTO startProcessInstance(StartProcessInstanceDTO dto) {
        // Resolve definitionKey from DTO (JPA inside the service for the id→key path)
        String definitionKey = processInstanceLifecycleService.resolveDefinitionKey(dto);
        runtimeOperationSupport.requireOperate(definitionKey, AuthorizationService.Action.START);

        // WO-ENG-10: validate against the EXACT definition that will actually be started, not
        // always "latest by key" — mirrors RuntimeServiceImpl.startProcessInstance's own
        // resolution order (id > key+version > key+maxVersion), so a start pinned to an older
        // version is validated against that version's form/schema, not a newer one's.
        var targetDefinition = runtimeSupportService.resolveTargetDefinition(dto);

        // ADR-6 §D9: form validation via FormArtifactService facade
        if (targetDefinition != null && targetDefinition.getStartFormKey() != null) {
            List<FormValidator.ValidationError> errors = formArtifactService.validateFormIfApplicable(
                targetDefinition.getStartFormKey(), dto.getVariables());
            if (!errors.isEmpty()) {
                String errorDetails = errors.stream()
                    .map(e -> e.field() + ": " + e.message())
                    .reduce((a, b) -> a + "; " + b).orElse("Validation failed");
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorDetails);
            }
        }

        String onBehalfOf = runtimeOperationSupport.checkedOnBehalfOf();
        IdDTO result = Optional.ofNullable(runtimeService.startProcessInstance(dto)).map(runtimeOperationSupport::toDTO).orElseThrow();

        // WO-INT-2: persist initiator on process instance
        if (onBehalfOf != null) {
            // WO-SEC-28: the initiator is a claimed, unverified attribution — keep the marker.
            String claimed = "[claimed] " + onBehalfOf;
            processInstanceLifecycleService.recordInitiator(result.getId(), claimed);
            auditLogService.record(runtimeOperationSupport.getPrincipal(), "START", definitionKey, result.getId().toString(), claimed);
        } else {
            auditLogService.record(runtimeOperationSupport.getPrincipal(), "START", definitionKey, result.getId().toString(), null);
        }
        return result;
    }

    @Transactional
    @Override
    public IdDTO cancelProcessInstance(UUID id) {
        var pi = dbService.getProcessInstance(id);
        if (pi.getCompletedAt() != null || pi.isCancelled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Process instance already completed or cancelled");
        }

        String key = runtimeOperationSupport.resolveDefinitionKeyByInstance(id);
        runtimeOperationSupport.requireOperate(key, AuthorizationService.Action.DELETE_PROCESS);

        // WO-C8-28: canceling listeners observe the cancellation; while any phase is
        // open the process-cancel tail below waits (the last-closing listener runs it
        // from the canceling resume branch). Snapshot BEFORE cancelling (only live
        // tasks qualify), open AFTER (eligibility requires CANCELLED status).
        // The audit record fires now — it records the requested operation, whose
        // terminal effect lands asynchronously, the same eventual-consistency
        // contract as every other parked transition.
        List<UUID> cancelCandidates = cancelingPhaseService.activeUserTaskIdsInInstance(id);
        dbService.cancelActiveActivities(id);
        boolean cancelPhasesOpen = cancelingPhaseService.openForInstanceSnapshot(id, cancelCandidates);
        dbService.deleteTimerJobsByProcessInstanceId(id);
        dbService.deleteMessageSubscriptionsByProcessInstanceId(id);
        if (!cancelPhasesOpen) {
            dbService.cancelProcessInstance(id);
        }
        auditLogService.record(runtimeOperationSupport.getPrincipal(), "CANCEL", key, id.toString());
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }
}
