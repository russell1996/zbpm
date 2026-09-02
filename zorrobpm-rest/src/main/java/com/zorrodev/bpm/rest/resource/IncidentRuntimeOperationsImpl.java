package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
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
public class IncidentRuntimeOperationsImpl implements IncidentRuntimeOperations {

    private final RuntimeService runtimeService;
    private final AuditLogService auditLogService;
    private final RuntimeOperationSupport runtimeOperationSupport;

    @Transactional
    @Override
    public IdDTO resolveIncident(UUID id, ResolveIncidentDTO dto) {
        // B3: enforce authorization — resolve incident → activity → process → definition_key
        String key = runtimeOperationSupport.resolveDefinitionKeyByIncident(id);
        runtimeOperationSupport.requireOperate(key, AuthorizationService.Action.COMPLETE_SERVICE_TASK);
        IdDTO result = Optional.ofNullable(runtimeService.resolveIncident(id, dto.getVariables())).map(runtimeOperationSupport::toDTO).orElseThrow();
        auditLogService.record(runtimeOperationSupport.getPrincipal(), "RESOLVE_INCIDENT", key, id.toString());
        return result;
    }
}
