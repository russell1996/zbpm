package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ParallelGatewayRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-7: MI join-bookkeeping isolation between loop iterations.
 * <p>
 * PROD-BUG 52e6b64c: {@code parallel_gateways} join bookkeeping used the static BPMN element ID
 * ({@code miTask}) as the key across ALL loop iterations. When stray arrival rows persisted or
 * external events fired between iterations, iteration N+1's counts were polluted →
 * {@code done=true} fired prematurely → concurrent side-effect hazard.
 * <p>
 * Fix: each {@code MultiInstanceExecutor.enter()} generates a unique batch UUID and uses it
 * as a composite key ({@code miId + "::" + batchUuid}) for all parallel-gateway operations.
 * Different iterations NEVER share the same key, so data from prior iterations is invisible.
 * <p>
 * Tagged {@code @Tag("pg")} via PostgresIT — excluded from default CI runs.
 */
public class MiLoopReentryRacePgIT extends PostgresIT {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ParallelGatewayRepository parallelGatewayRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    // ─── helpers ─────────────────────────────────────────────────────

    private void ensureTx() {
        if (tx == null) tx = new TransactionTemplate(txManager);
    }

    /** Deploys and starts a BPMN process in a transaction. */
    private UUID startProcess(String bpmnFile, List<ProcessVariable> variables) {
        ensureTx();
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                if (variables != null) dto.setVariables(variables);
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Finds an active (CREATED or IN_PROGRESS) activity by BPMN element id. */
    private ActivityEntity findActivity(UUID piId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .findFirst().orElseThrow(() -> new AssertionError("No active " + bpmnElementId));
    }

    /** Lists activities by BPMN element id and status. */
    private List<ActivityEntity> listActivities(UUID piId, String bpmnElementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId))
            .filter(a -> a.getStatus() == status)
            .toList();
    }

    // ─── Tests ───────────────────────────────────────────────────────

    @Test
    void keyIsolation_samePiDifferentBatchesUseDifferentCompositeKeys() throws Exception {
        /*
         * WO-ENG-7 criterion #1 (happy path / key isolation):
         * When MI instances complete and the flow loops back for a second iteration,
         * the new enter() call uses a DIFFERENT composite key than the first iteration.
         *
         * Each composite key = miId + "::" + batchUuid.
         * The batch UUID is stored as a process variable _mi_batch_<miId>.
         */
        ProcessVariable executors = new ProcessVariable();
        executors.setName("executors");
        executors.setType(ProcessVariableType.JSON);
        executors.setValue("[\"user1\",\"user2\"]");
        ProcessVariable needReturn = new ProcessVariable();
        needReturn.setName("needReturn");
        needReturn.setType(ProcessVariableType.BOOLEAN);
        needReturn.setValue("true");
        ProcessVariable dueDate = new ProcessVariable();
        dueDate.setName("dueDate");
        dueDate.setType(ProcessVariableType.STRING);
        dueDate.setValue("2099-01-01T00:00:00Z");

        UUID piId = startProcess("test-mi-reenter.bpmn", List.of(executors, needReturn, dueDate));

        // Complete srvSetup → fork → miTask (2 MI instances)
        tx.execute(status -> {
            runtimeService.completeServiceTask(findActivity(piId, "srvSetup").getId(), List.of());
            return null;
        });

        // Read batch UUID from variable -- set by MultiInstanceExecutor.enter()
        String batchUuid1 = jdbc.queryForObject(
            "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL",
            String.class, piId, "_mi_batch_miTask");
        assertThat(batchUuid1).as("Iteration 1 batch UUID variable exists").isNotNull();

        String iter1Key = "miTask::" + batchUuid1;

        // Verify expected_count row exists under composite key
        Integer expected1 = parallelGatewayRepository.findExpectedCounts(piId, iter1Key).size();
        assertThat(expected1).as("Iteration 1 has expected count under composite key").isEqualTo(1);

        // Complete both MI instances with action=CLOSE
        ProcessVariable actionClose = new ProcessVariable();
        actionClose.setName("action");
        actionClose.setType(ProcessVariableType.STRING);
        actionClose.setValue("CLOSE");

        List<ActivityEntity> iter1 = listActivities(piId, "miTask", ActivityStatus.CREATED);
        assertThat(iter1).hasSize(2);

        tx.execute(status -> {
            for (ActivityEntity mi : iter1) {
                runtimeService.completeUserTask(mi.getId(), List.of(actionClose));
            }
            return null;
        });

        // After all MI instances complete, parallel_gateways rows under iter1Key should be cleared
        int rowsIter1 = parallelGatewayRepository.findEnteredFlows(piId, iter1Key).size()
            + parallelGatewayRepository.findExpectedCounts(piId, iter1Key).size();
        assertThat(rowsIter1).as("Iteration 1 parallel_gateways rows cleared after done=true").isEqualTo(0);

        // Complete the single srvParentClose to loop back (only the last MI proceeds to downstream
        // when all instances are done; there's no completionCondition to short-circuit the join)
        ActivityEntity parentClose = findActivity(piId, "srvParentClose");
        tx.execute(status -> {
            runtimeService.completeServiceTask(parentClose.getId(), List.of());
            return null;
        });

        // Verify new MI instances spawned (iteration 2)
        List<ActivityEntity> iter2 = listActivities(piId, "miTask", ActivityStatus.CREATED);
        assertThat(iter2).as("Second iteration should spawn new MI instances").isNotEmpty();

        // Read batch UUID for iteration 2 -- should be DIFFERENT from iter1
        String batchUuid2 = jdbc.queryForObject(
            "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL",
            String.class, piId, "_mi_batch_miTask");
        assertThat(batchUuid2).as("Iteration 2 batch UUID variable exists").isNotNull();
        assertThat(batchUuid2).as("Iteration 2 has DIFFERENT batch UUID than iteration 1").isNotEqualTo(batchUuid1);

        String iter2Key = "miTask::" + batchUuid2;

        // Iteration 2 data is stored under iter2Key, NOT iter1Key
        int rowsIter2 = parallelGatewayRepository.findEnteredFlows(piId, iter2Key).size()
            + parallelGatewayRepository.findExpectedCounts(piId, iter2Key).size();
        assertThat(rowsIter2).as("Iteration 2 has parallel_gateways rows under its own composite key").isGreaterThan(0);

        // No rows under the OLD key
        int rowsOldKey = parallelGatewayRepository.findEnteredFlows(piId, iter1Key).size()
            + parallelGatewayRepository.findExpectedCounts(piId, iter1Key).size();
        assertThat(rowsOldKey).as("No rows remain under iteration 1's key").isEqualTo(0);
    }

    @Test
    void staleArrivalWithOldKeyFormatDoesNotAffectNewIteration() {
        /*
         * POF test (WO-ENG-7 criterion #2):
         * WITHOUT the fix: parallel_gateways rows under the old format key "miTask"
         * would be found by iteration 2 (which also uses "miTask") → counts polluted.
         * WITH the fix: iteration 2 uses composite key "miTask::<newBatchUuid>",
         * so stale rows inserted under "miTask" (old format) are invisible.
         *
         * Steps:
         * 1. Complete iteration 1 (all data cleared by clearParallelGatewayArrivals).
         * 2. Manually INSERT a stale arrival row with old-format gatewayElementId = "miTask".
         * 3. Complete srvParentClose → loop back → enter() for iteration 2.
         * 4. Query parallel_gateways for iteration 2's composite key vs old key.
         */
        ProcessVariable executors = new ProcessVariable();
        executors.setName("executors");
        executors.setType(ProcessVariableType.JSON);
        executors.setValue("[\"user1\",\"user2\"]");
        ProcessVariable needReturn = new ProcessVariable();
        needReturn.setName("needReturn");
        needReturn.setType(ProcessVariableType.BOOLEAN);
        needReturn.setValue("true");
        ProcessVariable dueDate = new ProcessVariable();
        dueDate.setName("dueDate");
        dueDate.setType(ProcessVariableType.STRING);
        dueDate.setValue("2099-01-01T00:00:00Z");
        ProcessVariable actionClose = new ProcessVariable();
        actionClose.setName("action");
        actionClose.setType(ProcessVariableType.STRING);
        actionClose.setValue("CLOSE");

        UUID piId = startProcess("test-mi-reenter.bpmn", List.of(executors, needReturn, dueDate));

        // ── Iteration 1 ──────────────────────────────────────────────

        tx.execute(status -> {
            runtimeService.completeServiceTask(findActivity(piId, "srvSetup").getId(), List.of());
            return null;
        });

        List<ActivityEntity> iter1 = listActivities(piId, "miTask", ActivityStatus.CREATED);
        assertThat(iter1).hasSize(2);

        // Complete all MI instances for iteration 1
        tx.execute(status -> {
            for (ActivityEntity mi : iter1) {
                runtimeService.completeUserTask(mi.getId(), List.of(actionClose));
            }
            return null;
        });

        // ── Verify parallel_gateways is clean for the composite key ────
        String batchUuid1 = jdbc.queryForObject(
            "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL",
            String.class, piId, "_mi_batch_miTask");
        assertThat(batchUuid1).isNotNull();
        String iter1Key = "miTask::" + batchUuid1;

        int rowsUnderIter1Key = parallelGatewayRepository.findEnteredFlows(piId, iter1Key).size()
            + parallelGatewayRepository.findExpectedCounts(piId, iter1Key).size();
        assertThat(rowsUnderIter1Key).as("Iteration 1 rows cleared after done=true").isEqualTo(0);

        // ── Inject stale arrival with OLD-format key ──────────────────
        jdbc.update(
            "INSERT INTO parallel_gateways (id, process_instance_id, gateway_element_id, entered_flow_id, created_at) "
            + "VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), piId,
            "miTask",                     // ← OLD format WITHOUT batch UUID
            "stale-flow",
            Timestamp.from(Instant.now()));

        // ── Complete ONE srvParentClose → loop back → iteration 2
        // (Completing both would cause 2 re-entries → 4 MI instances, complicating assertions.)
        ActivityEntity pcToComplete = findActivity(piId, "srvParentClose");
        tx.execute(status -> {
            runtimeService.completeServiceTask(pcToComplete.getId(), List.of());
            return null;
        });

        List<ActivityEntity> iter2 = listActivities(piId, "miTask", ActivityStatus.CREATED);
        assertThat(iter2).as("Iteration 2 spawns 2 MI instances").hasSize(2);

        // ── Verify key isolation ─────────────────────────────────────
        String batchUuid2 = jdbc.queryForObject(
            "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL",
            String.class, piId, "_mi_batch_miTask");
        assertThat(batchUuid2).isNotNull();
        assertThat(batchUuid2).isNotEqualTo(batchUuid1);

        String iter2Key = "miTask::" + batchUuid2;

        // Stale row with old format "miTask" still exists
        Long staleCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM parallel_gateways WHERE process_instance_id = ? AND gateway_element_id = ?",
            Long.class, piId, "miTask");
        assertThat(staleCount).as("Stale row with old-format 'miTask' still exists").isEqualTo(1);

        // Iteration 2 data is under iter2Key, NOT "miTask"
        int rowsUnderIter2Key = parallelGatewayRepository.findEnteredFlows(piId, iter2Key).size()
            + parallelGatewayRepository.findExpectedCounts(piId, iter2Key).size();
        assertThat(rowsUnderIter2Key).as("Iteration 2 has data under its own composite key").isGreaterThan(0);

        // Verify that no rows appear under iter1Key (old key format)
        int rowsUnderOldKey = parallelGatewayRepository.findEnteredFlows(piId, iter1Key).size()
            + parallelGatewayRepository.findExpectedCounts(piId, iter1Key).size();
        assertThat(rowsUnderOldKey).as("No rows under iteration 1's key").isEqualTo(0);
    }

    @Test
    void staleArrivalWithFragmentKeyIsolatedByCompositeKey() {
        /*
         * RED/GREEN POF (WO-ENG-7 criterion #3):
         * Even when a stale row has a KEY THAT IS A PREFIX of the current key
         * (e.g. "miTask" is a prefix of "miTask::<batchUuid>"), it must NOT match.
         * The composite key uses "miTask::<batchUuid>" as an EXACT match.
         *
         * Without the fix (key = "miTask"), a stale row with key "miTask" IS counted.
         * With the fix (key = "miTask::<batchUuid>"), the stale "miTask" row is NOT a match.
         *
         * We verify by directly querying parallel_gateways:
         * - stale "miTask" row exists
         * - iteration 2 data is under "miTask::<newBatchUuid>"
         * - findEnteredFlows(piId, "miTask") returns 1 (the stale row)
         * - findEnteredFlows(piId, "miTask::<batchUuid>") returns the correct data
         *
         * This proves isolation even for fragment-key collision.
         */
        ProcessVariable executors = new ProcessVariable();
        executors.setName("executors");
        executors.setType(ProcessVariableType.JSON);
        executors.setValue("[\"user1\",\"user2\"]");
        ProcessVariable needReturn = new ProcessVariable();
        needReturn.setName("needReturn");
        needReturn.setType(ProcessVariableType.BOOLEAN);
        needReturn.setValue("true");
        ProcessVariable dueDate = new ProcessVariable();
        dueDate.setName("dueDate");
        dueDate.setType(ProcessVariableType.STRING);
        dueDate.setValue("2099-01-01T00:00:00Z");
        ProcessVariable actionClose = new ProcessVariable();
        actionClose.setName("action");
        actionClose.setType(ProcessVariableType.STRING);
        actionClose.setValue("CLOSE");

        UUID piId = startProcess("test-mi-reenter.bpmn", List.of(executors, needReturn, dueDate));

        // Iteration 1
        tx.execute(status -> {
            runtimeService.completeServiceTask(findActivity(piId, "srvSetup").getId(), List.of());
            return null;
        });
        List<ActivityEntity> iter1 = listActivities(piId, "miTask", ActivityStatus.CREATED);
        assertThat(iter1).hasSize(2);
        tx.execute(status -> {
            for (ActivityEntity mi : iter1) runtimeService.completeUserTask(mi.getId(), List.of(actionClose));
            return null;
        });

        // Get batch UUID for iteration 1
        String batchUuid1 = jdbc.queryForObject(
            "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL",
            String.class, piId, "_mi_batch_miTask");
        assertThat(batchUuid1).isNotNull();

        // Inject stale arrival with old format "miTask" (a PREFIX of composite key)
        jdbc.update(
            "INSERT INTO parallel_gateways (id, process_instance_id, gateway_element_id, entered_flow_id, created_at) "
            + "VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), piId, "miTask", "stale-flow", Timestamp.from(Instant.now()));

        // Loop back via FIRST srvParentClose only (using only 1 avoids double-reentry
        // which would create 4 MI instances instead of 2)
        ActivityEntity pc1 = findActivity(piId, "srvParentClose");
        tx.execute(status -> {
            runtimeService.completeServiceTask(pc1.getId(), List.of());
            return null;
        });

        List<ActivityEntity> iter2 = listActivities(piId, "miTask", ActivityStatus.CREATED);
        assertThat(iter2).hasSize(2);

        String batchUuid2 = jdbc.queryForObject(
            "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL",
            String.class, piId, "_mi_batch_miTask");
        assertThat(batchUuid2).isNotNull();

        // ── POF assertions ──────────────────────────────────────────
        // RED scenario (without fix): findEnteredFlows(piId, "miTask") would see
        // stale row + real arrival, which could contribute to wrong counts.
        // GREEN scenario (with fix): iteration 2 uses "miTask::<batchUuid2>"
        // which is DIFFERENT from old "miTask" — stale row is invisible.

        // findEnteredFlows for old key returns 1 (the stale row we injected)
        int staleArrivalRows = parallelGatewayRepository.findEnteredFlows(piId, "miTask").size();
        assertThat(staleArrivalRows)
            .as("Stale row under old key 'miTask' is still present (not removed by fix)")
            .isEqualTo(1);

        // findEnteredFlows for composite key returns only REAL data (0 at this point
        // since no MI instances of iter2 have been completed yet)
        String iter2Key = "miTask::" + batchUuid2;
        int realArrivalRows = parallelGatewayRepository.findEnteredFlows(piId, iter2Key).size();
        assertThat(realArrivalRows)
            .as("No real arrivals under iteration 2's composite key (no MI completed yet)")
            .isEqualTo(0);

        // findExpectedCounts for composite key returns 1 (set by enter())
        int expectedRows = parallelGatewayRepository.findExpectedCounts(piId, iter2Key).size();
        assertThat(expectedRows)
            .as("Expected count row exists under iteration 2's composite key")
            .isEqualTo(1);

        // findExpectedCounts for old key returns 0 (completely unrelated)
        int oldExpectedRows = parallelGatewayRepository.findExpectedCounts(piId, "miTask").size();
        assertThat(oldExpectedRows)
            .as("No expected count under old key 'miTask'")
            .isEqualTo(0);

        // ── Complete 1/2 of iteration 2 ─────────────────────────────
        tx.execute(status -> {
            runtimeService.completeUserTask(iter2.get(0).getId(), List.of(actionClose));
            return null;
        });

        // After 1/2 completed, arrivals under composite key = 1
        int arrivalsAfterOne = parallelGatewayRepository.findEnteredFlows(piId, iter2Key).size();
        assertThat(arrivalsAfterOne)
            .as("After 1st MI completion, 1 arrival under composite key")
            .isEqualTo(1);

        // Stale row still under "miTask" with count = 1
        int staleAfterOne = parallelGatewayRepository.findEnteredFlows(piId, "miTask").size();
        assertThat(staleAfterOne)
            .as("Stale row under old key 'miTask' still at 1 (not double-counted)")
            .isEqualTo(1);
    }
}
