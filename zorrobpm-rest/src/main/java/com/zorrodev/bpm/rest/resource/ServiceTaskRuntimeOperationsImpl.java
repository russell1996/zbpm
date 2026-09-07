package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.AdHocJobResultDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceTaskRuntimeOperationsImpl implements ServiceTaskRuntimeOperations {

    private final RuntimeService runtimeService;
    private final AuditLogService auditLogService;
    private final RuntimeOperationSupport runtimeOperationSupport;

    @Transactional
    @Override
    public IdDTO completeServiceTask(UUID id, CompleteTaskDTO dto) {
        String key = runtimeOperationSupport.resolveDefinitionKeyByServiceTask(id);
        runtimeOperationSupport.requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        IdDTO result = Optional.ofNullable(runtimeService.completeServiceTask(id, dto.getVariables())).map(runtimeOperationSupport::toDTO).orElseThrow();
        auditLogService.record(runtimeOperationSupport.getPrincipal(), "COMPLETE_SERVICE_TASK", key, id.toString());
        return result;
    }

    @Transactional
    @Override
    public IdDTO completeAdHocScopeJob(UUID id, AdHocJobResultDTO dto) {
        String key = runtimeOperationSupport.resolveDefinitionKeyByServiceTask(id);
        runtimeOperationSupport.requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        IdDTO result = Optional.ofNullable(runtimeService.completeAdHocScopeJob(id, dto)).map(runtimeOperationSupport::toDTO).orElseThrow();
        auditLogService.record(runtimeOperationSupport.getPrincipal(), "COMPLETE_AD_HOC_SCOPE_JOB", key, id.toString());
        return result;
    }

    @Transactional
    @Override
    public IdDTO failServiceTask(UUID id, FailServiceTaskDTO dto) {
        String key = runtimeOperationSupport.resolveDefinitionKeyByServiceTask(id);
        runtimeOperationSupport.requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        IdDTO result = Optional.ofNullable(runtimeService.failServiceTask(id, dto.getMessage(), dto.getRetries())).map(runtimeOperationSupport::toDTO).orElseThrow();
        auditLogService.record(runtimeOperationSupport.getPrincipal(), "FAIL_SERVICE_TASK", key, id.toString());
        return result;
    }
}
