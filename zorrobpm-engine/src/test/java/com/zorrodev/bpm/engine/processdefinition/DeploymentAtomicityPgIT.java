package com.zorrodev.bpm.engine.processdefinition;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
 */
public class DeploymentAtomicityPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired JdbcTemplate jdbc;

    private static final String MSG_KEY = "rel15-msg-deploy";
    private static final String MSG_NAME = "rel15-msg-received";
    private static final String TIMER_KEY = "rel15-timer-deploy";

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

        // Timer-start process: timer_start_jobs row with a future due_at must exist.
        var timer = processDefinitionService.addProcessDefinition(timerBpmn);
        Integer timerJobs = jdbc.queryForObject(
            "SELECT count(*) FROM timer_start_jobs WHERE process_definition_id = ?", Integer.class, timer.getId());
        assertThat(timerJobs).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM timer_start_jobs WHERE process_definition_id = ? AND due_at > NOW()",
            Integer.class, timer.getId())).isEqualTo(1);

        // Redeploy of the same file: dedup by sha256 → same id, no churn, no duplicate artifacts.
        var again = processDefinitionService.addProcessDefinition(msgBpmn);
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(countProcessDefinitions(MSG_KEY)).isEqualTo(1);
        assertThat(countMessageStartSubscriptions(MSG_KEY)).isEqualTo(1);
    }

    // ==================== helpers ====================

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

    private void cleanup(String key) {
        jdbc.update("DELETE FROM process_instances WHERE process_definition_id IN (SELECT id FROM process_definitions WHERE code = ?)", key);
        jdbc.update("DELETE FROM message_start_subscriptions WHERE process_key = ?", key);
        jdbc.update("DELETE FROM timer_start_jobs WHERE process_key = ?", key);
        jdbc.update("DELETE FROM signal_start_subscriptions WHERE process_key = ?", key);
        jdbc.update("DELETE FROM element_artifact_binding WHERE process_definition_id IN (SELECT id FROM process_definitions WHERE code = ?)", key);
        jdbc.update("DELETE FROM bpmn WHERE id IN (SELECT id FROM process_definitions WHERE code = ?)", key);
        jdbc.update("DELETE FROM process_definitions WHERE code = ?", key);
    }
}
