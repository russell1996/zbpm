package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormArtifactService;
import com.zorrodev.bpm.engine.service.FormValidator;
import com.zorrodev.bpm.engine.service.RuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProcessInstanceRuntimeOperationsImpl implements ProcessInstanceRuntimeOperations {

    private final RuntimeService runtimeService;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final FormArtifactService formArtifactService;
    private final DBService dbService;
    private final AuditLogService auditLogService;
    private final RuntimeOperationSupport runtimeOperationSupport;

    @Transactional
    @Override
    public IdDTO startProcessInstance(StartProcessInstanceDTO dto) {
        String definitionKey = dto.getProcessDefinitionKey();
        if (definitionKey == null && dto.getProcessDefinitionId() != null) {
            ProcessDefinitionEntity pd = processDefinitionRepository.findById(dto.getProcessDefinitionId()).orElse(null);
            if (pd != null) definitionKey = pd.getKey();
        }
        runtimeOperationSupport.requireOperate(definitionKey, AuthorizationService.Action.START);

        ProcessDefinitionEntity targetDefinition = runtimeOperationSupport.resolveTargetDefinition(dto);

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

        if (onBehalfOf != null) {
            String claimed = "[claimed] " + onBehalfOf;
            processInstanceRepository.findById(result.getId()).ifPresent(pi -> {
                pi.setInitiator(claimed);
                processInstanceRepository.save(pi);
            });
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

        dbService.cancelActiveActivities(id);
        dbService.deleteTimerJobsByProcessInstanceId(id);
        dbService.deleteMessageSubscriptionsByProcessInstanceId(id);
        dbService.cancelProcessInstance(id);
        auditLogService.record(runtimeOperationSupport.getPrincipal(), "CANCEL", key, id.toString());
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }
}
