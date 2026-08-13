package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.scheduler.TimerBatchProcessor;
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
 * WO-REL-17 (R-04, boundary-timer half of WO-REL-14): a bounded {@code R<n>/...} cycle on a
 * non-interrupting boundary timer must exhaust after exactly n fires, and the re-arm must be
 * driven by the PERSISTED timer-job state (remaining count, cycle expression, previous dueAt) —
 * never recomputed from the BPMN model.
 *
 * Old behaviour (RED, proven by POF): {@code EventTrigger.rearmRepeatingBoundaryTimer} recomputed
 * {@code remaining = repeatCount - 1} from the model on EVERY fire, so an R3 cycle never reached
 * 0 and fired forever; it also scheduled the next occurrence with a 4-arg {@code createTimerJob}
 * (no persisted expression) and from a fresh model-based dueAt instead of the fired job's dueAt.
 * (Since WO-PERF-3 the re-arm uses the 6-arg overload that also persists the processInstanceId.)
 *
 * New behaviour (GREEN): the first job of a cycle carries {@code remaining = repeatCount - 1}
 * and the raw expression (BoundaryScheduler, same convention as TimerCatchHandler); the re-arm
 * reads the last fired job, computes {@code next = firstOccurrence(expression, job.dueAt, zone)},
 * persists {@code remaining - 1} (or {@code null} for unbounded cycles) and stops at 0.
 *
 * Real PostgreSQL required: same REQUIRES_NEW transaction boundaries as WO-REL-13/REL-14
 * (drift and burst are not reproducible on H2 — see pg-vs-h2-divergence).
 */
public class RepeatingBoundaryTimerPgIT extends PostgresIT {

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
        jdbc.execute("DELETE FROM timer_start_jobs");
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
        jdbc.execute("DELETE FROM process_definitions WHERE code LIKE 'rel17-%'");
    }

    // ------------------------------------------------------------------
    // Criterion #1/#2: bounded boundary cycle R3/PT1S — exactly 3 fires, real intervals
    // ------------------------------------------------------------------

    /**
     * Criterion #1: R3 on a non-interrupting boundary timer fires EXACTLY 3 times and then
     * stops (no 4th re-arm). Criterion #2: each re-arm is computed from the fired job's
     * persisted expression + PREVIOUS dueAt (job.dueAt + 1s), not from Instant.now() — so a
     * forced-past dueAt keeps the whole cycle in the past (no burst, no drift), and the
     * persisted remaining_count is decremented 2 → 1 → 0, never recomputed from the model.
     */
    @Test
    void boundaryBoundedCycle_firesExactlyThreeTimes_intervalsFromPreviousDueAt() throws Exception {
        String key = "rel17-boundary-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = Files.readString(Paths.get("src/test/files/test-rel17-boundary-cycle-bounded.bpmn"))
            .replace("test-rel17-boundary-cycle-bounded", key);
        UUID defId = processDefinitionService.addProcessDefinition(bpmn).getId();

        UUID instanceId = startInstance(defId);
        UUID hostId = hostActivityId(instanceId);

        // The FIRST job of the cycle already carries the persisted expression + remaining (R3 -> 2).
        UUID job1 = boundaryJobId(instanceId);
        Map<String, Object> row1 = boundaryJobRow(job1);
        assertThat(row1.get("expression")).isEqualTo("R3/PT1S");
        assertThat(row1.get("remaining_count")).isEqualTo(2);

        // Force job1 due 10s in the past. If re-arm used Instant.now() (the pre-fix bug), the
        // next dueAt would land near "now" (far LATER than job1's forced dueAt). The fix must
        // instead compute next = job1.dueAt + 1s (still ~9s in the past).
        Instant forcedDueAt = Instant.now().minusSeconds(10);
        jdbc.update("UPDATE timer_jobs SET due_at = ? WHERE id = ?", java.sql.Timestamp.from(forcedDueAt), job1);

        // fire #1 -> re-arm to job2, remaining=1, due = job1.dueAt + 1s
        timerBatchProcessor.processBatch();
        assertThat(fired(job1)).as("job1 must be fired").isTrue();

        UUID job2 = boundaryJobId(instanceId);
        assertThat(job2).isNotEqualTo(job1);
        Map<String, Object> row2 = boundaryJobRow(job2);
        Instant dueAt2 = toInstant(row2.get("due_at"));
        assertThat(dueAt2)
            .as("re-arm must use the persisted expression from the PREVIOUS dueAt, not Instant.now() "
                + "(pre-fix: next would land near real-now, i.e. ~10s AFTER forcedDueAt)")
            .isCloseTo(forcedDueAt.plusSeconds(1), within(Duration.ofMillis(2000)));
        assertThat(dueAt2).isBefore(Instant.now().minusSeconds(5));
        assertThat(row2.get("remaining_count")).isEqualTo(1);
        assertThat(row2.get("expression")).isEqualTo("R3/PT1S");

        // fire #2 (still due) -> re-arm to job3, remaining=0
        timerBatchProcessor.processBatch();
        assertThat(fired(job2)).isTrue();
        UUID job3 = boundaryJobId(instanceId);
        assertThat(job3).isNotEqualTo(job2);
        assertThat(boundaryJobRow(job3).get("remaining_count")).isEqualTo(0);

        // fire #3 (remaining=0) -> cycle ends: no re-arm, no 4th job, boundary fired exactly 3 times
        timerBatchProcessor.processBatch();
        assertThat(fired(job3)).isTrue();

        Integer firedBoundaryJobs = jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE activity_id = ? AND boundary_element_id = 'boundary1' AND fired = true",
            Integer.class, hostId);
        assertThat(firedBoundaryJobs).as("R3 boundary cycle must fire EXACTLY 3 timer jobs, not more").isEqualTo(3);

        List<UUID> pendingAfter = jdbc.queryForList(
            "SELECT id FROM timer_jobs WHERE activity_id = ? AND boundary_element_id = 'boundary1' AND fired = false",
            UUID.class, hostId);
        assertThat(pendingAfter).as("no 4th re-arm after remaining_count reached 0").isEmpty();

        // and the boundary branch actually executed 3 times (one COMPLETED remind per fire)
        Integer remindCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE process_instance_id = ? AND bpmn_element_id = 'remind' AND status = 'COMPLETED'",
            Integer.class, instanceId);
        assertThat(remindCount).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Criterion #3: unbounded boundary cycle R/PT1S keeps re-arming as before
    // ------------------------------------------------------------------

    /**
     * Criterion #3 (regression): an unbounded cycle ({@code R/PT1S}) keeps re-arming forever —
     * primary placement stores remaining=null + the expression, every re-arm persists the same
     * null remaining (infinite) and the real 1s interval from the previous dueAt.
     */
    @Test
    void boundaryInfiniteCycle_keepsRearming_withPersistedExpression() throws Exception {
        String key = "rel17-boundary-inf-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = Files.readString(Paths.get("src/test/files/test-rel17-boundary-cycle-infinite.bpmn"))
            .replace("test-rel17-boundary-cycle-infinite", key);
        UUID defId = processDefinitionService.addProcessDefinition(bpmn).getId();

        UUID instanceId = startInstance(defId);
        UUID hostId = hostActivityId(instanceId);

        UUID job1 = boundaryJobId(instanceId);
        Map<String, Object> row1 = boundaryJobRow(job1);
        assertThat(row1.get("expression")).isEqualTo("R/PT1S");
        assertThat(row1.get("remaining_count")).isNull();

        Instant forcedDueAt = Instant.now().minusSeconds(10);
        jdbc.update("UPDATE timer_jobs SET due_at = ? WHERE id = ?", java.sql.Timestamp.from(forcedDueAt), job1);

        // fire #1 -> re-arm with the same infinite semantics
        timerBatchProcessor.processBatch();
        UUID job2 = boundaryJobId(instanceId);
        assertThat(job2).isNotEqualTo(job1);
        Map<String, Object> row2 = boundaryJobRow(job2);
        assertThat(row2.get("remaining_count")).as("infinite cycle keeps null remaining").isNull();
        assertThat(row2.get("expression")).isEqualTo("R/PT1S");
        assertThat(toInstant(row2.get("due_at")))
            .isCloseTo(forcedDueAt.plusSeconds(1), within(Duration.ofMillis(2000)));

        // fire #2 -> still re-arming (infinite cycle never ends while the host runs)
        timerBatchProcessor.processBatch();
        UUID job3 = boundaryJobId(instanceId);
        assertThat(job3).isNotEqualTo(job2);
        assertThat(boundaryJobRow(job3).get("expression")).isEqualTo("R/PT1S");
        assertThat(boundaryJobRow(job3).get("remaining_count")).isNull();

        // the host is still alive (non-interrupting) and the boundary branch executed twice
        String hostStatus = jdbc.queryForObject(
            "SELECT status FROM activities WHERE id = ?", String.class, hostId);
        assertThat(hostStatus).isNotIn("COMPLETED", "CANCELLED");
        Integer remindCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE process_instance_id = ? AND bpmn_element_id = 'remind' AND status = 'COMPLETED'",
            Integer.class, instanceId);
        assertThat(remindCount).isEqualTo(2);
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

    private UUID hostActivityId(UUID instanceId) {
        return jdbc.queryForObject(
            "SELECT id FROM activities WHERE process_instance_id = ? AND bpmn_element_id = 'host'",
            UUID.class, instanceId);
    }

    private UUID boundaryJobId(UUID instanceId) {
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM timer_jobs WHERE activity_id = (SELECT id FROM activities "
                + "WHERE process_instance_id = ? AND bpmn_element_id = 'host') "
                + "AND boundary_element_id = 'boundary1' AND fired = false",
            UUID.class, instanceId);
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private Map<String, Object> boundaryJobRow(UUID jobId) {
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
