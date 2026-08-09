package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * WO-REL-14 (R-04): repeating timers must respect the real cycle interval and a bounded
 * ({@code R<n>/...}) cycle must actually exhaust after n occurrences.
 *
 * Old behaviour (RED, proven by POF):
 * - Defect 1: {@code TimerJobExecutor} re-armed an intermediate cycle timer with a hardcoded
 *   {@code "R/PT0S"} from {@code Instant.now()} instead of the real expression from the
 *   previous {@code dueAt} — every remaining repetition fired back-to-back (burst) instead of
 *   respecting the interval.
 * - Defect 2: {@code TimerStartJobExecutor} recomputed {@code remainingCount} from the BPMN
 *   model's {@code repeatCount} on every fire instead of reading a persisted value — a bounded
 *   cycle (e.g. R3/PT1H) was reset to "2 remaining" every time and therefore never exhausted.
 *
 * New behaviour (GREEN): the original expression is persisted on {@code timer_jobs.expression}
 * at scheduling time and used verbatim (with the previous {@code dueAt} as reference) on re-arm;
 * {@code timer_start_jobs.remaining_count} is persisted and decremented, not recomputed.
 *
 * Real PostgreSQL required (this exercises the same REQUIRES_NEW transaction boundaries as
 * WO-REL-13, not reproducible on H2 — see pg-vs-h2-divergence).
 */
public class RepeatingTimerSemanticsPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired TimerBatchProcessor timerBatchProcessor;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    /** Park the background poller so it cannot race the explicit processBatch() calls below. */
    @DynamicPropertySource
    static void parkBackgroundPoller(DynamicPropertyRegistry registry) {
        registry.add("zorrobpm.engine.timer-poll-interval-ms", () -> "3600000");
    }

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // children before parents (no ON DELETE CASCADE in the schema)
        jdbc.execute("DELETE FROM timer_start_jobs WHERE process_key LIKE 'rel14-%'");
        jdbc.execute("DELETE FROM timer_jobs");
        jdbc.execute("DELETE FROM message_subscriptions");
        jdbc.execute("DELETE FROM signal_subscriptions");
        jdbc.execute("DELETE FROM parallel_gateways");
        jdbc.execute("DELETE FROM incidents");
        jdbc.execute("DELETE FROM user_tasks");
        jdbc.execute("DELETE FROM service_tasks");
        jdbc.execute("DELETE FROM variables");
        jdbc.execute("DELETE FROM activities");
        jdbc.execute("DELETE FROM tokens");
        jdbc.execute("DELETE FROM events");
        jdbc.execute("DELETE FROM process_instances");
        jdbc.execute("DELETE FROM process_definitions WHERE code LIKE 'rel14-%'");
    }

    // ------------------------------------------------------------------
    // Defect 1: intermediate catch timer, bounded cycle R3/PT1S
    // ------------------------------------------------------------------

    /**
     * Criterion #1: R3/PT1S fires exactly 3 times, each re-arm computed from the persisted
     * expression + the PREVIOUS dueAt (not "now + 0s"), and the cycle ends (no 4th re-arm) —
     * the host activity signals through to endEvent after the 3rd fire.
     */
    @Test
    void intermediateCatchBoundedCycle_firesExactlyThreeTimes_intervalNotBurst() throws Exception {
        String key = "rel14-cycle-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = Files.readString(Paths.get("src/test/files/test-rel14-timer-cycle-bounded.bpmn"))
            .replace("test-rel14-timer-cycle-bounded", key);
        UUID defId = processDefinitionService.addProcessDefinition(bpmn).getId();

        UUID instanceId = startInstance(defId);
        UUID job1 = timerJobIdForInstance(instanceId);

        // The expression is persisted at INITIAL scheduling time already, not only on re-arm.
        Map<String, Object> row1 = timerJobRow(job1);
        assertThat(row1.get("expression")).isEqualTo("R3/PT1S");
        assertThat(row1.get("remaining_count")).isEqualTo(2);

        // Force job1 due 10s in the past. If re-arm used Instant.now() (the pre-fix bug), the
        // next dueAt would land near "now" (far LATER than job1's forced dueAt). The fix must
        // instead compute next = job1.dueAt + 1s (still ~9s in the past).
        Instant forcedDueAt = Instant.now().minusSeconds(10);
        jdbc.update("UPDATE timer_jobs SET due_at = ? WHERE id = ?", java.sql.Timestamp.from(forcedDueAt), job1);

        timerBatchProcessor.processBatch();
        assertThat(fired(job1)).as("job1 must be fired").isTrue();

        UUID job2 = timerJobIdForInstance(instanceId);
        assertThat(job2).isNotEqualTo(job1);
        Map<String, Object> row2 = timerJobRow(job2);
        Instant dueAt2 = toInstant(row2.get("due_at"));
        assertThat(dueAt2)
            .as("re-arm must use the persisted expression from the PREVIOUS dueAt, not Instant.now() "
                + "(pre-fix: next would land near real-now, i.e. ~10s AFTER forcedDueAt)")
            .isCloseTo(forcedDueAt.plusSeconds(1), within(Duration.ofMillis(2000)));
        assertThat(dueAt2).isBefore(Instant.now().minusSeconds(5));
        assertThat(row2.get("remaining_count")).isEqualTo(1);
        assertThat(row2.get("expression")).isEqualTo("R3/PT1S");

        // fire job2 (still due — dueAt2 is in the past) -> re-arm to job3, remaining=0
        timerBatchProcessor.processBatch();
        assertThat(fired(job2)).isTrue();
        UUID job3 = timerJobIdForInstance(instanceId);
        assertThat(job3).isNotEqualTo(job2);
        assertThat(timerJobRow(job3).get("remaining_count")).isEqualTo(0);

        // fire job3 (remaining=0) -> cycle ends, no re-arm, activity completes to endEvent
        timerBatchProcessor.processBatch();
        assertThat(fired(job3)).isTrue();

        List<UUID> pendingAfter = jdbc.queryForList(
            "SELECT id FROM timer_jobs WHERE activity_id = (SELECT id FROM activities "
                + "WHERE process_instance_id = ? AND bpmn_element_id = 'timer1') AND fired = false",
            UUID.class, instanceId);
        assertThat(pendingAfter).as("no 4th re-arm after remaining_count reached 0").isEmpty();

        String endEventStatus = jdbc.queryForObject(
            "SELECT status FROM activities WHERE process_instance_id = ? AND bpmn_element_id = 'endEvent'",
            String.class, instanceId);
        assertThat(endEventStatus).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------
    // Defect 2: timer start event, bounded cycle R3/PT1S
    // ------------------------------------------------------------------

    /**
     * Criterion #2: a timer-start R3/PT1S starts exactly 3 process instances, then stops —
     * remaining_count is read from the persisted row and decremented, never recomputed from the
     * BPMN model (which would reset it to 2 on every single fire and start instances forever).
     */
    @Test
    void timerStartBoundedCycle_startsExactlyThreeInstances_thenStops() throws Exception {
        String key = "rel14-start-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = Files.readString(Paths.get("src/test/files/test-rel14-timer-start-cycle-bounded.bpmn"))
            .replace("test-rel14-timer-start-cycle-bounded", key);
        UUID defId = processDefinitionService.addProcessDefinition(bpmn).getId();

        // Deployment already registered the first timer-start job with remaining_count=2 (R3-1).
        Map<String, Object> firstRow = jdbc.queryForMap(
            "SELECT id, remaining_count FROM timer_start_jobs WHERE process_definition_id = ?", defId);
        assertThat(firstRow.get("remaining_count")).isEqualTo(2);

        // Force-due and poll repeatedly (bounded loop, not real sleeps): each poll fires at most
        // one due job and may create a fresh (not-yet-due) one; forcing it due drains the whole
        // bounded cycle deterministically regardless of the real 1s interval.
        for (int i = 0; i < 5; i++) {
            List<UUID> pending = jdbc.queryForList(
                "SELECT id FROM timer_start_jobs WHERE process_definition_id = ? AND fired = false",
                UUID.class, defId);
            if (pending.isEmpty()) {
                break;
            }
            jdbc.update("UPDATE timer_start_jobs SET due_at = now() - interval '10 seconds' WHERE id = ?", pending.get(0));
            timerBatchProcessor.processBatch();
        }

        Integer startedInstances = jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE process_definition_id = ?", Integer.class, defId);
        assertThat(startedInstances).as("R3 cycle must start EXACTLY 3 instances, not more").isEqualTo(3);

        List<UUID> stillPending = jdbc.queryForList(
            "SELECT id FROM timer_start_jobs WHERE process_definition_id = ? AND fired = false",
            UUID.class, defId);
        assertThat(stillPending).as("cycle must stop rescheduling once remaining_count reaches 0").isEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UUID startInstance(UUID definitionId) {
        // RuntimeServiceImpl has no @Transactional of its own — start inside a transaction
        // (mirrors the production REST path and other PG-ITs).
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definitionId);
        return tx.execute(status -> runtimeService.startProcessInstance(dto).getId());
    }

    private UUID timerJobIdForInstance(UUID instanceId) {
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM timer_jobs WHERE activity_id = (SELECT id FROM activities "
                + "WHERE process_instance_id = ? AND bpmn_element_id = 'timer1') AND fired = false",
            UUID.class, instanceId);
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private Map<String, Object> timerJobRow(UUID jobId) {
        return jdbc.queryForMap("SELECT expression, remaining_count, due_at FROM timer_jobs WHERE id = ?", jobId);
    }

    private boolean fired(UUID jobId) {
        Boolean v = jdbc.queryForObject("SELECT fired FROM timer_jobs WHERE id = ?", Boolean.class, jobId);
        return Boolean.TRUE.equals(v);
    }

    private static Instant toInstant(Object dbValue) {
        if (dbValue instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        return (Instant) dbValue;
    }
}
