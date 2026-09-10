package com.zorrodev.bpm.engine.deployment;

import com.zorrodev.bpm.contract.dto.DeploymentItemDTO;
import com.zorrodev.bpm.contract.dto.DeploymentResourceType;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.DeploymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SCALE-4: concurrent batch deploys ({@code POST /deployments} path) of identical
 * content on real PostgreSQL. Calls the REAL {@code DeploymentService.deployBatch()}
 * from N parallel threads — the batch equivalent of {@code DmnDeployConcurrencyPgIT}.
 *
 * <p>Root cause (soak WO-SCALE-3, 451/467 rounds): {@code findBySha256} ran BEFORE the
 * advisory lock, so every racer passed the dedup check, serialized on the key lock inside
 * {@code createNewVersionEntity}, and the losers died on
 * {@code uk_process_definitions__sha256} → raw 500. The fix acquires the key lock BEFORE
 * the check (double-checked dedup): losers see the winner's row and return it.
 *
 * <p>The fixture carries message-start + signal-start + timer-start + embedded user-task
 * form so the same race also exercises the registrar paths (criterion 5): subscriptions
 * and form rows must exist exactly once afterwards.
 *
 * <p>POF (G-N): commenting out the {@code acquireForKey} line in
 * {@code ProcessDefinitionServiceImpl.addProcessDefinition} → RED (500/unique violation
 * on at least one racer); restoring → GREEN. H2 proves nothing here: the H2 branch of
 * the lock is a silent no-op.
 */
@Tag("pg")
public class BatchDeployConcurrencyPgIT extends PostgresIT {

    @Autowired DeploymentService deploymentService;
    @Autowired JdbcTemplate jdbc;

    private static final String KEY = "scale4-race";
    private static final int RACERS = 8;

    private String bpmn;

    @BeforeEach
    void setUp() throws Exception {
        bpmn = Files.readString(Path.of("src/test/files/test-scale4-batch-race.bpmn"));
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        // Children first (FK-safe order, mirrors DeploymentAtomicityPgIT).
        jdbc.update("DELETE FROM message_start_subscriptions WHERE process_key = ?", KEY);
        jdbc.update("DELETE FROM signal_start_subscriptions WHERE process_key = ?", KEY);
        jdbc.update("DELETE FROM timer_start_jobs WHERE process_key = ?", KEY);
        jdbc.update("DELETE FROM element_artifact_binding WHERE process_definition_id IN (SELECT id FROM process_definitions WHERE code = ?)", KEY);
        jdbc.update("DELETE FROM bpmn WHERE id IN (SELECT id FROM process_definitions WHERE code = ?)", KEY);
        jdbc.update("DELETE FROM process_definitions WHERE code = ?", KEY);
        jdbc.update("DELETE FROM form WHERE form_key = ?", "camunda-forms:bpmn:userTaskForm_race-form");
    }

    private DeploymentItemDTO bpmnItem() {
        DeploymentItemDTO item = new DeploymentItemDTO();
        item.setType(DeploymentResourceType.BPMN);
        item.setContent(bpmn);
        return item;
    }

    @Test
    void concurrentBatchDeploy_identicalContent_no500_singleVersion() throws Exception {
        CountDownLatch readyGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < RACERS; i++) {
            futures.add(pool.submit(() -> {
                try {
                    readyGate.await();
                    var dto = deploymentService.deployBatch(List.of(bpmnItem()), "scale4 race", "scale4");
                    return dto.getProcesses().get(0).getVersion();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        readyGate.countDown();

        // Zero 500s: every racer returns (an exception here fails the test via ExecutionException).
        List<Integer> versions = new ArrayList<>();
        for (Future<Integer> f : futures) {
            versions.add(f.get());
        }
        pool.shutdown();

        // Dedup: all racers see the same single version, exactly one version row exists.
        assertThat(versions).hasSize(RACERS);
        assertThat(versions).allSatisfy(v -> assertThat(v).isEqualTo(1));
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_definitions WHERE code = ?", Integer.class, KEY);
        assertThat(rows).isEqualTo(1);

        // Criterion 5: registrar artifacts exist exactly once (no dupes from the race).
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM message_start_subscriptions WHERE process_key = ?", Integer.class, KEY))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_start_jobs WHERE process_key = ?", Integer.class, KEY))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM signal_start_subscriptions WHERE process_key = ?", Integer.class, KEY))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM form WHERE form_key = ?", Integer.class,
            "camunda-forms:bpmn:userTaskForm_race-form"))
            .isEqualTo(1);
    }

    @Test
    void sequentialRepeatBatchDeploy_returnsExistingVersion_noNewRow() {
        var first = deploymentService.deployBatch(List.of(bpmnItem()), "scale4 first", "scale4");
        var second = deploymentService.deployBatch(List.of(bpmnItem()), "scale4 repeat", "scale4");

        assertThat(first.getProcesses().get(0).getVersion())
            .isEqualTo(second.getProcesses().get(0).getVersion());
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_definitions WHERE code = ?", Integer.class, KEY);
        assertThat(rows).isEqualTo(1);
    }
}
