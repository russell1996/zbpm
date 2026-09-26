package com.zorrodev.bpm.engine.deployment;

import com.zorrodev.bpm.contract.dto.DeploymentItemDTO;
import com.zorrodev.bpm.contract.dto.DeploymentResourceType;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.DeploymentService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * WO-ENG-18 criterion 5: two parallel FIRST deploys of one new key through the real
 * {@code DeploymentService.deployBatch()} must leave exactly one {@code process} row
 * and both deploys must succeed.
 *
 * <p>PG-only by construction (mirrors {@code BatchDeployConcurrencyPgIT}): the race guard
 * that serialises the deploy is the PG advisory lock inside
 * {@code ProcessDefinitionServiceImpl.addProcessDefinition} — its H2 branch is a silent
 * no-op, so H2 proves nothing about this race. Pre-fix this test is RED by construction:
 * the batch path never created a {@code process} row, so the count is 0, not 1.
 */
@Tag("pg")
public class ProcessRowRacePgIT extends PostgresIT {

    @Autowired DeploymentService deploymentService;
    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired JdbcTemplate jdbc;

    private static final String KEY = "eng18-row-race";
    private static final int RACERS = 4;

    private String bpmn;

    @BeforeEach
    void setUp() throws Exception {
        String raw = Files.readString(Path.of("src/test/files/dummy-process.bpmn"));
        bpmn = raw.replace("dummy-process", KEY).replace("Dummy Process", KEY);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM element_artifact_binding WHERE process_definition_id IN (SELECT id FROM process_definitions WHERE code = ?)", KEY);
        jdbc.update("DELETE FROM bpmn WHERE id IN (SELECT id FROM process_definitions WHERE code = ?)", KEY);
        jdbc.update("DELETE FROM process_definitions WHERE code = ?", KEY);
        jdbc.update("DELETE FROM process WHERE definition_key = ?", KEY);
    }

    private DeploymentItemDTO bpmnItem() {
        DeploymentItemDTO item = new DeploymentItemDTO();
        item.setType(DeploymentResourceType.BPMN);
        item.setContent(bpmn);
        return item;
    }

    @Test
    void concurrentFirstBatchDeploys_singleProcessRow_bothSucceed() throws Exception {
        CountDownLatch readyGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < RACERS; i++) {
            futures.add(pool.submit(() -> {
                try {
                    readyGate.await();
                    var dto = deploymentService.deployBatch(List.of(bpmnItem()), "eng18 race", "eng18");
                    return dto.getProcesses().get(0).getVersion();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        readyGate.countDown();

        // Zero failures: every racer returns (an exception fails the test via ExecutionException).
        List<Integer> versions = new ArrayList<>();
        for (Future<Integer> f : futures) {
            versions.add(f.get());
        }
        pool.shutdown();

        assertThat(versions).hasSize(RACERS);

        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM process WHERE definition_key = ?", Integer.class, KEY);
        assertThat(rows).isEqualTo(1);

        // The raced row is fully functional: archiving works on it (WO-ENG-18 criterion 2).
        assertThatCode(() -> processDefinitionService.archiveProcess(KEY))
            .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
            "SELECT archived FROM process WHERE definition_key = ?", Boolean.class, KEY))
            .isTrue();
    }
}
