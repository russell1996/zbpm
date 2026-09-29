package com.zorrodev.bpm.engine.processdefinition;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ARCH-2: Concurrent deployment test on real PostgreSQL.
 * Calls the REAL ProcessDefinitionService.addProcessDefinition() from two parallel threads.
 * Both BPMNs share the same process key ("test1") but have different content (different sha256).
 * This means both pass the sha256 dedup check and both enter version creation — the advisory
 * lock must serialize them to produce sequential versions (1 and 2), not duplicate version 1.
 *
 * POF (G-N): commenting out pg_advisory_xact_lock in the service → RED (unique violation on code+version).
 * Restoring → GREEN.
 */
public class ProcessDefinitionPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired JdbcTemplate jdbc;

    private String bpmnV1;
    private String bpmnV2; // Same key, different content → different sha256
    private String processKey;

    @BeforeEach
    void setUp() throws Exception {
        bpmnV1 = Files.readString(Path.of("src/test/files/test1.bpmn"));
        // Second BPMN: same key "test1", but add a different task → different sha256.
        // WO-REL-18: the extra serviceTask needs a real job (zeebe:taskDefinition), otherwise deploy
        // validation rejects it — so the zeebe namespace is added alongside the injected task.
        bpmnV2 = bpmnV1
            .replace(
                "xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"",
                "xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\"")
            .replace(
                "<bpmn:endEvent id=\"endEvent\"",
                "<bpmn:serviceTask id=\"svc1\" name=\"extra\"><bpmn:extensionElements>"
                    + "<zeebe:taskDefinition type=\"svc1\" /></bpmn:extensionElements></bpmn:serviceTask>"
                    + "<bpmn:endEvent id=\"endEvent\"");
        processKey = "test1";
        // Clean up existing versions
        jdbc.update("DELETE FROM process_definitions WHERE code = ?", processKey);
    }

    @Test
    void concurrentDeploy_sameKey_differentVersions_noViolation() throws Exception {
        CountDownLatch readyGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Thread 1: deploy bpmnV1 via REAL service
        Future<?> f1 = pool.submit(() -> {
            try {
                readyGate.await();
                processDefinitionService.addProcessDefinition(bpmnV1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Thread 2: deploy bpmnV2 (same key, different sha256) via REAL service
        Future<?> f2 = pool.submit(() -> {
            try {
                readyGate.await();
                processDefinitionService.addProcessDefinition(bpmnV2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        readyGate.countDown();

        f1.get();
        f2.get();
        pool.shutdown();

        // Verify: two versions exist, sequential (1 and 2)
        var versions = jdbc.queryForList(
            "SELECT version FROM process_definitions WHERE code = ? ORDER BY version", processKey);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version")).isEqualTo(1);
        assertThat(versions.get(1).get("version")).isEqualTo(2);

        // Cleanup
        jdbc.update("DELETE FROM process_definitions WHERE code = ?", processKey);
    }
}
