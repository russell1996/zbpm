package com.zorrodev.bpm.engine.processdefinition;

import com.zorrodev.bpm.engine.PgItIsolation;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * WO-REL-15 (R-05): deployment must be atomic — version row + bpmn model row + start
 * subscriptions + timer start jobs + element bindings are created inside ONE transaction.
 * A failure between version creation and artifact creation must roll back everything
 * (no half-deployed version), and a redeploy of the same sha256 must REPAIR a deployment
 * whose state is not ACTIVE instead of bailing out with "already exists".
 *
 * POF (G-K/G-N): all tests drive the REAL ProcessDefinitionService.addProcessDefinition();
 * the fault is injected at the database level (BEFORE INSERT trigger that raises), so the
 * full production wiring (JPA + TransactionTemplate + services) is exercised, not a mock.
 *
 * <p>WO-QW-13 (isolation): the background {@code TimerScheduler} is parked for this class (it owns
 * the timer tables while it runs — a 5-minute timer-start job left by an earlier method is fired by
 * the live poller and starts an instance mid-test, which is what broke this class in
 * {@code test:pg}); the cleanup runs children-before-parents through
 * {@link PgItIsolation#deleteDeployment}; and criterion 3 compares the registered job against a
 * reference instant the TEST owns instead of the database wall clock.
 */
public class DeploymentAtomicityPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired JdbcTemplate jdbc;

    /** WO-QW-13: no second writer while this class inspects the timer/deployment tables. */
    @DynamicPropertySource
    static void parkBackgroundTimerPollers(DynamicPropertyRegistry registry) {
        PgItIsolation.parkBackgroundTimerPollers(registry);
    }

    private static final String MSG_KEY = "rel15-msg-deploy";
    private static final String MSG_NAME = "rel15-msg-received";
    private static final String TIMER_KEY = "rel15-timer-deploy";

    /** {@code src/test/files/test-rel15-timer-deploy.bpmn} declares {@code PT5M} on its timer start. */
    private static final Duration TIMER_START_DURATION = Duration.ofMinutes(5);

    /** Deploy→assert budget on a loaded runner; generous, and it still pins the 5-minute duration. */
    private static final Duration CLOCK_SLACK = Duration.ofMinutes(1);

    private String msgBpmn;
    private String timerBpmn;

    @BeforeEach
    void setUp() throws Exception {
        msgBpmn = Files.readString(Path.of("src/test/files/test-rel15-msg-deploy.bpmn"));
        timerBpmn = Files.readString(Path.of("src/test/files/test-rel15-timer-deploy.bpmn"));
        cleanup(MSG_KEY);
        cleanup(TIMER_KEY);
    }

    // ==================== Criterion 1: fault injection → no half-deployed version ====================

    /**
     * Inject a failure right where the OLD code was outside the transaction: the INSERT of the
     * message start subscription. Old behavior: the version row is already COMMITTED, so it
     * survives the failure → half-deployed version visible via API. New behavior: everything
     * rolls back → process_definitions contains nothing for this key.
     */
    @Test
    void deployFailsOnArtifactCreation_nothingPersisted_noHalfDeployedVersion() {
        // DB-level fault injection: every INSERT into message_start_subscriptions raises.
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION rel15_fail_msg_start() RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'rel15-injected-failure';
            END;
            $$ LANGUAGE plpgsql
            """);
        jdbc.execute("""
            CREATE TRIGGER rel15_fail_msg_start_tg
            BEFORE INSERT ON message_start_subscriptions FOR EACH ROW
            EXECUTE FUNCTION rel15_fail_msg_start()
            """);

        try {
            try {
                processDefinitionService.addProcessDefinition(msgBpmn);
                org.assertj.core.api.Assertions.fail("deployment must fail when artifact creation fails");
            } catch (RuntimeException expected) {
                // fail-CLOSED: the injected failure must propagate, not be swallowed
            }

            // Criterion 1: NO "half-deployed" version — the whole deployment rolled back.
            assertThat(countProcessDefinitions(MSG_KEY)).isZero();
            assertThat(countBpmnModels(MSG_KEY)).isZero();
            assertThat(countMessageStartSubscriptions(MSG_KEY)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS rel15_fail_msg_start_tg ON message_start_subscriptions");
            jdbc.execute("DROP FUNCTION IF EXISTS rel15_fail_msg_start()");
        }
    }

    // ==================== Criterion 2: redeploy of the same sha256 repairs ====================

    /**
     * Simulate a crashed previous deployment: version row exists but no bpmn model row and no
     * subscriptions, deployment_state NOT ACTIVE (PENDING). Redeploying the same file must
     * REPAIR (re-assemble artifacts, flip to ACTIVE) — NOT return "already exists".
     */
    @Test
    void redeploySameSha256_repairsIncompleteDeployment() throws Exception {
        UUID halfDeployedId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO process_definitions (id, code, version, name, sha256, created_at, deployment_state)
            VALUES (?, ?, 1, ?, ?, NOW(), 'PENDING')
            """, halfDeployedId, MSG_KEY, "rel15-msg-deploy", sha256(msgBpmn));

        processDefinitionService.addProcessDefinition(msgBpmn);

        // Same row, flipped to ACTIVE — not a new version, not "already exists".
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT deployment_state, version FROM process_definitions WHERE code = ?", MSG_KEY);
        assertThat(row.get("deployment_state")).isEqualTo("ACTIVE");
        assertThat(row.get("version")).isEqualTo(1);
        assertThat(countProcessDefinitions(MSG_KEY)).isEqualTo(1);

        // All artifacts re-assembled.
        assertThat(countBpmnModels(MSG_KEY)).isEqualTo(1);
        assertThat(countMessageStartSubscriptions(MSG_KEY)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT message_name FROM message_start_subscriptions WHERE process_definition_id = ?",
            String.class, halfDeployedId)).isEqualTo(MSG_NAME);
    }

    // ==================== Criterion 3: normal deployment unchanged ====================

    @Test
    void normalDeploy_createsAllArtifacts_secondSameShaIsNoop() {
        var first = processDefinitionService.addProcessDefinition(msgBpmn);
        assertThat(first.getKey()).isEqualTo(MSG_KEY);
        assertThat(first.getVersion()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT deployment_state FROM process_definitions WHERE code = ?", MSG_KEY);
        assertThat(row.get("deployment_state")).isEqualTo("ACTIVE");
        assertThat(countBpmnModels(MSG_KEY)).isEqualTo(1);
        assertThat(countMessageStartSubscriptions(MSG_KEY)).isEqualTo(1);

        // Timer-start process: exactly one timer_start_jobs row for the definition this deploy created,
        // scheduled PT5M (the BPMN's timeDuration) after the deployment itself.
        //
        // WO-QW-13: the old assertion was `due_at > NOW()` — a JVM-stamped naive `timestamp` compared
        // against the DATABASE clock. Correctness therefore depended on the two clocks agreeing and on
        // the session timezone that renders NOW() into the naive frame: state this test does not own
        // (pipelines 175956 / 175964, `expected: 1 but was: 0` at :146, ~60 ms after the deploy, with
        // a locally reproduced row that always satisfies `due_at = deployedAt + PT5M`). The reference
        // instant is now taken by the test around the deploy, so both ends of the comparison live on
        // one clock, and the assertion is strictly stronger — it pins the duration as well.
        Instant deployedAt = Instant.now();
        var timer = processDefinitionService.addProcessDefinition(timerBpmn);
        List<TimerStartJobRow> jobs = timerStartJobRows(TIMER_KEY, timer.getId());
        assertThat(jobs).as("exactly one timer-start job for the deployed definition").hasSize(1);
        TimerStartJobRow job = jobs.get(0);
        assertThat(job.elementId()).isEqualTo("rel15TimerStart");
        assertThat(job.fired()).as("a freshly registered timer-start job must not be fired").isFalse();
        assertThat(job.dueAt())
            .as("timer-start job must be scheduled PT5M after this deployment, not at an absolute wall clock")
            .isCloseTo(deployedAt.plus(TIMER_START_DURATION), within(CLOCK_SLACK));

        // Redeploy of the same file: dedup by sha256 → same id, no churn, no duplicate artifacts.
        var again = processDefinitionService.addProcessDefinition(msgBpmn);
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(countProcessDefinitions(MSG_KEY)).isEqualTo(1);
        assertThat(countMessageStartSubscriptions(MSG_KEY)).isEqualTo(1);
    }

    // ==================== helpers ====================

    /** One row of {@code timer_start_jobs} as the test needs to reason about it. */
    private record TimerStartJobRow(String processKey, UUID processDefinitionId, String elementId,
                                   Instant dueAt, Instant createdAt, boolean fired) {
    }

    /**
     * The timer-start jobs of ONE definition, keyed by process key AND id (WO-QW-13: never by a
     * whole-table count) and read back through the JVM clock, so the comparison never mixes the
     * database wall clock with the timestamp the deployment wrote.
     */
    private List<TimerStartJobRow> timerStartJobRows(String processKey, UUID processDefinitionId) {
        return jdbc.query(
            "SELECT process_key, process_definition_id, element_id, due_at, created_at, fired "
                + "FROM timer_start_jobs WHERE process_key = ? AND process_definition_id = ? "
                + "ORDER BY due_at",
            (rs, i) -> new TimerStartJobRow(
                rs.getString("process_key"),
                (UUID) rs.getObject("process_definition_id"),
                rs.getString("element_id"),
                rs.getTimestamp("due_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("fired")),
            processKey, processDefinitionId);
    }

    private static String sha256(String bpmn) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return Base64.getEncoder().encodeToString(digest.digest(bpmn.getBytes(StandardCharsets.UTF_8)));
    }

    private int countProcessDefinitions(String key) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM process_definitions WHERE code = ?", Integer.class, key);
    }

    private int countBpmnModels(String key) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM bpmn WHERE id IN (SELECT id FROM process_definitions WHERE code = ?)",
            Integer.class, key);
    }

    private int countMessageStartSubscriptions(String key) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM message_start_subscriptions WHERE process_definition_id IN "
                + "(SELECT id FROM process_definitions WHERE code = ?)",
            Integer.class, key);
    }

    /**
     * WO-QW-13: shared, ordered cleanup (children before parents, scoped to this key) —
     * see {@link PgItIsolation#deleteDeployment}. The hand-written order this replaces deleted the
     * instances first but nothing else, so a single instance left by the live poller turned the final
     * {@code DELETE FROM process_definitions} into a foreign-key violation on
     * {@code fk_process_instances__process_definition_id} (reproduced live, see the WO report).
     */
    private void cleanup(String key) {
        PgItIsolation.deleteDeployment(jdbc, key);
    }
}
