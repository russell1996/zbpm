package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.AdvisoryDeployLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;

/**
 * WO-DEBT-5b — versioning + advisory-lock cluster extracted byte-for-byte from
 * {@code ProcessDefinitionServiceImpl} (WO-A-03). Add-only foundation: the original
 * still calls its own copies; delegation happens in Phase 5c. The only change vs the
 * original is {@code createNewVersionEntity} private → public (called from two places).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessDefinitionVersioning {

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final TransactionTemplate transactionTemplate;
    private final AdvisoryDeployLock advisoryDeployLock;

    /**
     * WO-ARCH-2 + WO-A-03: Creates a new process definition version inside a transaction
     * protected by advisory lock (PG xact-scoped, see AdvisoryDeployLock).
     * Uses TransactionTemplate (not @Transactional) to avoid self-invocation proxy bypass.
     * Lock auto-released on commit/rollback. Different keys → different locks.
     *
     * WO-A-03 FAIL-CLOSED: PG advisory lock failure propagates (rollback),
     * no broad catch. H2: lock is skipped (function not supported).
     *
     * Kept as the package-private entry point for the WO-A-03 fail-closed unit test;
     * the production deployment path (addProcessDefinition) runs createNewVersionEntity
     * inside its own single transaction instead (WO-REL-15).
     */
    ProcessDefinitionEntity createNewVersionWithAdvisoryLock(
            String key, String name, String sha256, UUID id, String startFormKey, String versionTag) {
        return transactionTemplate.execute(status -> {
            ProcessDefinitionEntity entity = createNewVersionEntity(key, name, sha256, id, startFormKey, versionTag);
            entity.setDeploymentState(ProcessDefinitionEntity.STATE_ACTIVE);
            return processDefinitionRepository.save(entity);
        });
    }

    /**
     * WO-REL-15: builds a new version entity (advisory lock + next version number) WITHOUT
     * saving it — the caller persists it inside the deployment transaction. The advisory lock
     * is acquired on the caller's connection and released at that transaction's commit/rollback.
     */
    public ProcessDefinitionEntity createNewVersionEntity(
            String key, String name, String sha256, UUID id, String startFormKey, String versionTag) {
        // WO-A-03 / WO-SCALE-1: single place for dialect-aware lock (fail-closed, H2 skip)
        advisoryDeployLock.acquireForKey(key);
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key).orElse(0);
        ProcessDefinitionEntity entity = new ProcessDefinitionEntity();
        entity.setId(id);
        entity.setKey(key);
        entity.setName(name);
        entity.setVersion(maxVersion + 1);
        entity.setSha256(sha256);
        entity.setCreatedAt(Instant.now());
        entity.setStartFormKey(startFormKey);
        entity.setVersionTag(versionTag);
        return entity;
    }
}
