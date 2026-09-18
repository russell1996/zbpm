package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.BpmnRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DEBT-5a / WO-A-03 — advisory lock race characterization on REAL PostgreSQL.
 * Two threads deploy the SAME processKey with DIFFERENT content at the same time.
 * The pg_advisory_xact_lock serializes them: both must succeed with DISTINCT versions
 * (1 and 2), never two rows with the same version number (uk on (code, version) would
 * reject the loser if the lock did not serialize). H2 cannot prove this (lock skipped).
 */
@Tag("pg")
class ProcessDefinitionDeployRacePgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService service;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private BpmnRepository bpmnRepository;
    @Autowired private DBService dbService;

    private final List<UUID> cleanupPdIds = new ArrayList<>();
    private String cleanupKey;

    @AfterEach
    void cleanup() {
        if (cleanupKey != null) {
            try {
                dbService.deleteMessageStartSubscriptionsByKey(cleanupKey);
            } catch (Exception ignored) {
            }
            try {
                dbService.deleteTimerStartJobsByKey(cleanupKey);
            } catch (Exception ignored) {
            }
        }
        for (UUID id : cleanupPdIds) {
            bpmnRepository.deleteById(id);
            processDefinitionRepository.deleteById(id);
        }
        cleanupPdIds.clear();
        cleanupKey = null;
    }

    private static String plainBpmn(String key, String name) throws Exception {
        return Files.readString(Path.of("src/test/files/test1.bpmn"))
            .replace("BPMNPlane_1\" bpmnElement=\"test1", "BPMNPlane_1\" bpmnElement=\"" + key)
            .replace("id=\"test1\" name=\"test1\"", "id=\"" + key + "\" name=\"" + name + "\"");
    }

    @Test
    void concurrentDeploysSameKey_getDistinctVersions() throws Exception {
        String key = "race-" + UUID.randomUUID().toString().substring(0, 8);
        cleanupKey = key;
        String bpmnA = plainBpmn(key, key + "-A");
        String bpmnB = plainBpmn(key, key + "-B");

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<ProcessDefinition> results = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Thread t0 = new Thread(() -> {
            try {
                start.await();
                results.add(service.addProcessDefinition(bpmnA));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        });
        Thread t1 = new Thread(() -> {
            try {
                start.await();
                results.add(service.addProcessDefinition(bpmnB));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        });
        t0.start();
        t1.start();
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).as("both deploys finished").isTrue();

        // WO-A-03: the advisory lock serializes same-key deploys — no loser, no dup version
        assertThat(errors).as("no deploy failed: %s", errors).isEmpty();
        assertThat(results).hasSize(2);
        assertThat(results).extracting(ProcessDefinition::getVersion)
            .containsExactlyInAnyOrder(1, 2);
        List<ProcessDefinitionEntity> rows = processDefinitionRepository.findAll().stream()
            .filter(e -> key.equals(e.getKey())).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ProcessDefinitionEntity::getDeploymentState)
            .containsExactlyInAnyOrder(
                ProcessDefinitionEntity.STATE_ACTIVE, ProcessDefinitionEntity.STATE_ACTIVE);
        assertThat(processDefinitionRepository.findMaxByKey(key)).hasValue(2);
        for (ProcessDefinition r : results) {
            cleanupPdIds.add(r.getId());
        }
    }
}
