package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import com.zorrodev.bpm.engine.repository.AuditLogRepository;
import com.zorrodev.bpm.engine.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public void record(Principal principal, String action, String processKey, String targetId) {
        record(principal, action, processKey, targetId, null);
    }

    public void record(Principal principal, String action, String processKey, String targetId, String onBehalfOf) {
        if (principal == null) return;

        AuditLogEntity entry = new AuditLogEntity();
        entry.setId(UUID.randomUUID());
        entry.setAt(Instant.now());
        entry.setAction(action);
        entry.setProcessKey(processKey);
        entry.setTargetId(targetId);
        entry.setOnBehalfOf(onBehalfOf);

        if (principal instanceof Principal.UserPrincipal u) {
            entry.setPrincipalType("USER");
            entry.setPrincipalId(u.userId().toString());
            entry.setOwnerUserId(u.userId());
        } else if (principal instanceof Principal.ServicePrincipal sa) {
            entry.setPrincipalType("API_KEY");
            entry.setPrincipalId(sa.apiKeyId().toString());
            entry.setOwnerUserId(sa.ownerUserId());
        }

        auditLogRepository.save(entry);
        log.debug("Audit: action={} process={} target={} principal={} onBehalfOf={}",
            action, processKey, targetId, entry.getPrincipalId(), onBehalfOf);
    }
}
