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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-13 (R-03): timer jobs must be transactionally isolated — one failing job must not roll
 * back the whole poll batch.
 *
 * Old behaviour (RED, proven by POF): {@code processBatch} was @Transactional and each
 * {@code fire} joined that outer transaction (REQUIRED). A single exception marked the whole
 * transaction rollback-only: every other job of the batch was rolled back too (or the poll
 * failed with UnexpectedRollbackException).
 *
 * New behaviour (GREEN): the poll loop is non-transactional; candidate selection runs in its own
 * short transaction; each fire runs in REQUIRES_NEW. A failing job rolls back only itself and is
 * recorded per-job (attempts++/last_error), so it is retried by the next poll instead of being
 * silently lost. Double execution of one job under two concurrent pollers is still impossible
 * (SKIP LOCKED selection + atomic CAS claim).
 *
 * Real PostgreSQL required (transaction semantics and SKIP LOCKED are not reproducible on H2).
 */
public class TimerBatchIsolationPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired TimerBatchProcessor timerBatchProcessor;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    /**
     * This class drives processBatch() explicitly. Park the background TimerScheduler poller
     * (1 h interval instead of 5 s) so it cannot grab the due test jobs and make criteria 1/2
     * non-deterministic. Criterion 3 still exercises real 2-thread concurrency on its own.
     */
    @DynamicPropertySource
    static void parkBackgroundPoller(DynamicPropertyRegistry registry) {
        registry.add("zorrobpm.engine.timer-poll-interval-ms", () -> "3600000");
    }

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // children before parents (no ON DELETE CASCADE in the schema)
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
        jdbc.execute("DELETE FROM process_definitions WHERE code LIKE 'rel13-%'");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Deploys test-timer.bpmn under a unique key (start → timer catch PT5M → end). */
    private UUID deployTimerProcess() {
        try {
            String bpmn = Files.readString(Paths.get("src/test/files/test-timer.bpmn"))
                .replace("test-timer", "rel13-" + UUID.randomUUID().toString().substring(0, 8));
            return processDefinitionService.addProcessDefinition(bpmn).getId();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID startInstance(UUID definitionId) {
        // RuntimeServiceImpl has no @Transactional of its own — start inside a transaction
        // (mirrors the production REST path and other PG-ITs).
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definitionId);
        return tx.execute(status -> runtimeService.startProcessInstance(dto).getId());
    }

    /** Forces the instance's timer job due (now − 10s) and returns its id. */
    private UUID makeDue(UUID instanceId) {
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM timer_jobs WHERE activity_id IN (SELECT id FROM activities WHERE process_instance_id = ?)",
            UUID.class, instanceId);
        assertThat(ids).hasSize(1);
        UUID jobId = ids.get(0);
        jdbc.update("UPDATE timer_jobs SET due_at = now() - interval '10 seconds' WHERE id = ?", jobId);
        return jobId;
    }

    private boolean fired(UUID jobId) {
        Boolean v = jdbc.queryForObject("SELECT fired FROM timer_jobs WHERE id = ?", Boolean.class, jobId);
        return Boolean.TRUE.equals(v);
    }

    private String activityStatus(UUID instanceId) {
        return jdbc.queryForObject(
            "SELECT status FROM activities WHERE process_instance_id = ? AND bpmn_element_id = 'timer1'",
            String.class, instanceId);
    }

    private Map<String, Object> jobRow(UUID jobId) {
        return jdbc.queryForMap("SELECT fired, attempts, last_error FROM timer_jobs WHERE id = ?", jobId);
    }

    // ------------------------------------------------------------------
    // criteria
    // ------------------------------------------------------------------

    /**
     * Criterion #1: batch of 3 jobs, middle one throws → the 2 healthy jobs are committed,
     * the failing one is rolled back and recorded (attempts=1, last_error).
     */
    @Test
    void criterion1_middleJobFails_otherJobsCommitIndependently() {
        UUID def = deployTimerProcess();
        UUID pi1 = startInstance(def);
        UUID pi2 = startInstance(def);
        UUID pi3 = startInstance(def);
        UUID j1 = makeDue(pi1);
        UUID j2 = makeDue(pi2);
        UUID j3 = makeDue(pi3);

        // break the middle job: point it at a non-existent activity → fire() throws inside its
        // REQUIRES_NEW transaction (DBService.getActivity → orElseThrow), claim rolls back.
        jdbc.update("UPDATE timer_jobs SET activity_id = ? WHERE id = ?", UUID.randomUUID(), j2);

        timerBatchProcessor.processBatch();

        // healthy jobs committed independently
        assertThat(fired(j1)).as("job 1 must be committed").isTrue();
        assertThat(fired(j3)).as("job 3 must be committed").isTrue();
        assertThat(activityStatus(pi1)).isEqualTo("COMPLETED");
        assertThat(activityStatus(pi3)).isEqualTo("COMPLETED");

        // failing job rolled back and recorded, not silently lost
        Map<String, Object> row2 = jobRow(j2);
        assertThat(row2.get("fired")).isEqualTo(false);
        assertThat(((Number) row2.get("attempts")).intValue()).isEqualTo(1);
        assertThat((String) row2.get("last_error")).isNotBlank();
        assertThat(activityStatus(pi2)).isEqualTo("CREATED");
    }

    /**
     * Criterion #2: a failing job is not lost silently — it stays unfired, accumulates
     * attempts++/last_error and is picked up again by the next poll (retry).
     */
    @Test
    void criterion2_failedJobRetried_attemptsIncrementAndErrorRecorded() {
        UUID def = deployTimerProcess();
        UUID pi = startInstance(def);
        UUID job = makeDue(pi);
        jdbc.update("UPDATE timer_jobs SET activity_id = ? WHERE id = ?", UUID.randomUUID(), job);

        timerBatchProcessor.processBatch(); // attempt 1 → fails
        timerBatchProcessor.processBatch(); // attempt 2 → fails again (still due, fired=false)

        Map<String, Object> row = jobRow(job);
        assertThat(row.get("fired")).isEqualTo(false);
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(2);
        assertThat((String) row.get("last_error")).isNotBlank();
    }

    /**
     * Criterion #3: two concurrent processBatch pollers must not fire the same job twice —
     * SKIP LOCKED still works after the transaction-boundary change. 5 due jobs, 2 real threads
     * starting simultaneously: every job ends fired=true and every instance has exactly one
     * COMPLETED activity (the second poller either skips the row via SKIP LOCKED or loses the
     * atomic CAS claim, so the side effect runs at most once).
     */
    @Test
    void criterion3_twoConcurrentPollers_noDoubleExecution() throws Exception {
        UUID def = deployTimerProcess();
        int n = 5;
        UUID[] instances = new UUID[n];
        UUID[] jobs = new UUID[n];
        for (int i = 0; i < n; i++) {
            instances[i] = startInstance(def);
            jobs[i] = makeDue(instances[i]);
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> err1 = new AtomicReference<>();
        AtomicReference<Throwable> err2 = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                timerBatchProcessor.processBatch();
            } catch (Throwable t) {
                err1.set(t);
            } finally {
                done.countDown();
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                timerBatchProcessor.processBatch();
            } catch (Throwable t) {
                err2.set(t);
            } finally {
                done.countDown();
            }
        });

        t1.start();
        t2.start();
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        t1.join(5000);
        t2.join(5000);

        assertThat(err1.get()).isNull();
        assertThat(err2.get()).isNull();

        // all jobs fired exactly once (fired=true in DB is set by a single CAS claim)
        for (UUID job : jobs) {
            assertThat(fired(job)).as("job %s must be fired exactly once", job).isTrue();
        }
        // exactly one endEvent per instance — the timer side effect (signal → endEvent) ran at most once
        for (UUID pi : instances) {
            Integer endEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activities WHERE process_instance_id = ? "
                    + "AND bpmn_element_id = 'endEvent' AND status = 'COMPLETED'",
                Integer.class, pi);
            assertThat(endEvents).as("instance %s must have exactly one completed endEvent", pi).isEqualTo(1);
        }
    }
}
