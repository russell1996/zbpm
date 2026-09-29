package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.MessagePublishResultDTO;
import com.zorrodev.bpm.contract.dto.PublishMessageDTO;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MessageRuntimeOperationsImpl implements MessageRuntimeOperations {

    private final RuntimeService runtimeService;
    private final AuditLogService auditLogService;
    private final RuntimeOperationSupport runtimeOperationSupport;

    /**
     * WO-DIFF-5: two authz gates, fail-closed both ways (G-C design approved 2026-09-21,
     * variant (i) — no silent partial effect across tenants).
     * <ul>
     *   <li>Instance-scoped (processInstanceId set): {@code CORRELATE_MESSAGE} on the
     *       instance's definition key (mirror of the task paths' per-key gate). This also
     *       covers the key+instance combination — the key only narrows within the instance.</li>
     *   <li>Global (name-only, incl. key-only and message-start): SUPER_ADMIN only. A global
     *       publish fans out across instances of every tenant (and may start new ones), so a
     *       per-subscription grant intersection would report a counter that silently excludes
     *       foreign tenants — a lying counter is worse than a strict gate.</li>
     * </ul>
     */
    @Transactional
    @Override
    public MessagePublishResultDTO publishMessage(PublishMessageDTO dto) {
        if (dto.getProcessInstanceId() != null) {
            String key = runtimeOperationSupport.resolveDefinitionKeyByInstance(dto.getProcessInstanceId());
            runtimeOperationSupport.requireOperate(key, AuthorizationService.Action.CORRELATE_MESSAGE);
            MessagePublishResultDTO result = Optional.ofNullable(runtimeService.publishMessage(dto)).orElseThrow();
            auditLogService.record(runtimeOperationSupport.getPrincipal(), "CORRELATE_MESSAGE", key,
                dto.getProcessInstanceId().toString());
            return result;
        }
        Principal principal = runtimeOperationSupport.getPrincipal();
        if (principal == null || !principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        MessagePublishResultDTO result = Optional.ofNullable(runtimeService.publishMessage(dto)).orElseThrow();
        auditLogService.record(principal, "CORRELATE_MESSAGE", null, dto.getMessageName());
        return result;
    }
}
