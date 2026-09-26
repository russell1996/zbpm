package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;


/**
 * WO-ENG-2: FEEL expressions in timer event definitions.
 * <p>
 * Verifies that {@code =dueDate}, {@code =duration("PT10M")} and similar FEEL expressions are
 * evaluated before parsing the timer expression, rather than being passed directly to
 * {@link java.time.Instant#parse}/{@link java.time.Duration#parse} which throws
 * {@link java.time.format.DateTimeParseException}.
 * <p>
 * Criteria:
 * <ol>
 *   <li>Boundary timer with {@code timeDate="=dueDate"} → timer_job at variable's date.</li>
 *   <li>Catch timer with {@code timeDuration="=duration(\"PT10M\")"} → timer_job ~10 min ahead.</li>
 *   <li>Literal ISO-8601 expressions ({@code PT5M}, ISO date) still work (no regression).</li>
 *   <li>FEEL returning non-date/duration type → {@link EngineException} with element id.</li>
 * </ol>
 */
public class FeelTimerPgIT extends PostgresIT {

    @Autowired
    ProcessDefinitionService processDefinitionService;
    @Autowired
    RuntimeService runtimeService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        // WO-DIFF-7: this class commits instances+incidents (soft-fail assertions need
        // committed rows); wipe children-before-parents so later PgIT classes in the
        // shared-PG suite never see our residue (same order as RepeatingBoundaryTimerPgIT —
        // user_tasks/service_tasks BEFORE activities, they FK into process_instances).
        jdbc.execute("DELETE FROM timer_jobs");
        jdbc.execute("DELETE FROM message_subscriptions");
        jdbc.execute("DELETE FROM signal_subscriptions");
        jdbc.execute("DELETE FROM incidents");
        jdbc.execute("DELETE FROM user_tasks");
        jdbc.execute("DELETE FROM service_tasks");
        jdbc.execute("DELETE FROM variables");
        jdbc.execute("DELETE FROM activities");
        jdbc.execute("DELETE FROM tokens");
        jdbc.execute("DELETE FROM events");
        jdbc.execute("DELETE FROM process_instances");
        jdbc.execute("DELETE FROM process_definitions WHERE code LIKE 'test-%'");
    }

    // ----------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------

    private UUID deployAndStart(String bpmnFile, List<ProcessVariable> variables) {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                if (variables != null) {
                    dto.setVariables(variables);
                }
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<UUID> findTimerJobs(UUID activityId) {
        return jdbc.queryForList(
            "SELECT id FROM timer_jobs WHERE activity_id = ? ORDER BY created_at",
            UUID.class, activityId);
    }

    /** Queries timer_jobs for a process instance by joining through activities. */
    private List<UUID> findTimerJobsForInstance(UUID processInstanceId) {
        return jdbc.queryForList(
            "SELECT tj.id FROM timer_jobs tj " +
            "JOIN activities a ON a.id = tj.activity_id " +
            "WHERE a.process_instance_id = ? ORDER BY tj.created_at",
            UUID.class, processInstanceId);
    }

    private Timestamp findTimerJobDueAt(UUID jobId) {
        return jdbc.queryForObject(
            "SELECT due_at FROM timer_jobs WHERE id = ?",
            Timestamp.class, jobId);
    }

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    // ----------------------------------------------------------------
    // Criterion #1: boundary timer with timeDate="=dueDate"
    // ----------------------------------------------------------------

    @Test
    void feelTimeDate_boundaryTimer_createsJobAtVariableDate() {
        Instant expectedDue = Instant.parse("2027-06-15T12:00:00Z");

        UUID pi = deployAndStart("test-boundary-feel-date.bpmn", List.of(
            var("dueDate", ProcessVariableType.STRING, expectedDue.toString())
        ));

        List<UUID> jobs = findTimerJobsForInstance(pi);
        assertThat(jobs)
            .as("Should create a timer job for the FEEL timeDate boundary")
            .hasSize(1);

        Timestamp dueAt = findTimerJobDueAt(jobs.get(0));
        assertThat(dueAt.toInstant())
            .as("timeDate=\"=dueDate\" should resolve to the variable's date")
            .isCloseTo(expectedDue, within(1, ChronoUnit.SECONDS));
    }

    // ----------------------------------------------------------------
    // Criterion #2: catch timer with timeDuration="=duration(\"PT10M\")"
    // ----------------------------------------------------------------

    @Test
    void feelTimeDuration_catchTimer_createsJobAtComputedDuration() {
        UUID pi = deployAndStart("test-catch-feel-duration.bpmn", null);

        List<UUID> jobs = findTimerJobsForInstance(pi);
        assertThat(jobs)
            .as("Should create a timer job for the FEEL timeDuration catch")
            .hasSize(1);

        Timestamp dueAt = findTimerJobDueAt(jobs.get(0));
        assertThat(dueAt.toInstant())
            .as("timeDuration=\"=duration(\\\"PT10M\\\")\" should resolve to ~10 min from now")
            .isCloseTo(Instant.now().plus(Duration.ofMinutes(10)), within(2, ChronoUnit.MINUTES));
    }

    // ----------------------------------------------------------------
    // Criterion #3: literal ISO-8601 expressions still work (regression)
    // ----------------------------------------------------------------

    @Test
    void literalDuration_boundaryTimer_stillWorks() {
        // Use test-boundary.bpmn which has literal PT10M
        UUID pi = deployAndStart("test-boundary.bpmn", null);

        List<UUID> jobs = findTimerJobsForInstance(pi);
        assertThat(jobs)
            .as("Should create a timer job for literal PT10M boundary")
            .hasSize(1);

        Timestamp dueAt = findTimerJobDueAt(jobs.get(0));
        assertThat(dueAt.toInstant())
            .as("Literal PT10M should resolve to ~10 min from now (regression)")
            .isCloseTo(Instant.now().plus(Duration.ofMinutes(10)), within(2, ChronoUnit.MINUTES));
    }

    // ----------------------------------------------------------------
    // Criterion #4 (WO-DIFF-7 redefined): FEEL eval failure → incident, not 422.
    // ----------------------------------------------------------------
    // WO-DIFF-7 (Raxon finding #12, S-055): a boundary timer whose FEEL expression
    // fails to evaluate used to throw EngineException out of startProcessInstance
    // (HTTP 422 ENGINE_ERROR, no instance at all). Zeebe instead creates the
    // instance and parks an incident on the arming point. The old test
    // `feelExpressionReturnsGarbage_throwsEngineException` pinned the throw; it is
    // superseded by the test below (same BPMN fixture, inverted expectation —
    // G20: old name named explicitly here as deliberately dropped).
    // ----------------------------------------------------------------

    @Test
    void feelExpressionReturnsGarbage_boundaryTimer_raisesIncidentInsteadOfThrowing() {
        // Deploy succeeds; start must NOT throw — the instance must exist with an
        // open incident on the boundary timer, host task alive, no timer job armed.
        UUID pi = deployAndStart("test-boundary-feel-garbage.bpmn", null);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Long.class, pi))
            .as("process instance must exist after FEEL timer eval failure (no 422)")
            .isEqualTo(1L);

        List<String> messages = jdbc.queryForList(
            "SELECT i.message FROM incidents i " +
            "JOIN activities a ON a.id = i.activity_id " +
            "WHERE a.process_instance_id = ? AND i.completed_at IS NULL",
            String.class, pi);
        assertThat(messages)
            .as("exactly one open incident for the failed boundary timer")
            .hasSize(1);
        assertThat(messages.get(0))
            .as("incident message must name the failing timer element")
            .contains("boundary1");

        assertThat(findTimerJobsForInstance(pi))
            .as("no timer job may be armed when the FEEL expression failed")
            .isEmpty();

        String hostStatus = jdbc.queryForObject(
            "SELECT status FROM activities WHERE process_instance_id = ? AND bpmn_element_id = 'userTask1'",
            String.class, pi);
        assertThat(hostStatus)
            .as("host user task must stay alive (Zeebe: incident on arming point, host completable)")
            .isNotEqualTo("ERROR");
    }

    // ----------------------------------------------------------------
    // WO-DIFF-7 (S-055 verbatim): catch timer with timeDuration="=missingVar"
    // ----------------------------------------------------------------

    @Test
    void feelMissingVar_catchTimer_raisesIncidentInsteadOfThrowing() {
        // S-055 shape: intermediate catch event, FEEL reference to a variable that
        // does not exist. Zeebe: instance created + EXTRACT_VALUE_ERROR-style
        // incident on the `wait` element. Zorro before fix: 422, no instance.
        UUID pi = deployAndStart("test-catch-feel-missing-var.bpmn", null);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_instances WHERE id = ?", Long.class, pi))
            .as("process instance must exist after FEEL timer eval failure (no 422)")
            .isEqualTo(1L);

        List<String> messages = jdbc.queryForList(
            "SELECT i.message FROM incidents i " +
            "JOIN activities a ON a.id = i.activity_id " +
            "WHERE a.process_instance_id = ? AND i.completed_at IS NULL " +
            "AND a.bpmn_element_id = 'timerCatch1'",
            String.class, pi);
        assertThat(messages)
            .as("exactly one open incident parked on the failed catch timer element")
            .hasSize(1);
        assertThat(messages.get(0))
            .as("incident message must name the failing timer element")
            .contains("timerCatch1");

        String timerStatus = jdbc.queryForObject(
            "SELECT status FROM activities WHERE process_instance_id = ? AND bpmn_element_id = 'timerCatch1'",
            String.class, pi);
        assertThat(timerStatus)
            .as("failed catch timer activity must be parked as ERROR")
            .isEqualTo("ERROR");

        assertThat(findTimerJobsForInstance(pi))
            .as("no timer job may be armed when the FEEL expression failed")
            .isEmpty();
    }

    // ----------------------------------------------------------------
    // WO-DIFF-7: resolve with the missing variable re-arms the timer
    // (Zeebe re-evaluation gateway: setVariables + resolve → timer armed).
    // ----------------------------------------------------------------

    @Test
    void feelMissingVar_catchTimer_resolveWithFix_rearmsTimer() {
        UUID pi = deployAndStart("test-catch-feel-missing-var.bpmn", null);

        UUID incidentId = jdbc.queryForObject(
            "SELECT i.id FROM incidents i " +
            "JOIN activities a ON a.id = i.activity_id " +
            "WHERE a.process_instance_id = ? AND i.completed_at IS NULL " +
            "AND a.bpmn_element_id = 'timerCatch1'",
            UUID.class, pi);

        tx.execute(s ->
            runtimeService.resolveIncident(incidentId,
                List.of(var("missingVar", ProcessVariableType.STRING, "PT5M"))));

        List<UUID> jobs = findTimerJobsForInstance(pi);
        assertThat(jobs)
            .as("resolve with the missing variable must re-arm the catch timer (re-evaluation)")
            .hasSize(1);
        assertThat(findTimerJobDueAt(jobs.get(0)).toInstant())
            .as("re-armed timer must resolve the supplied duration")
            .isCloseTo(Instant.now().plus(Duration.ofMinutes(5)), within(2, ChronoUnit.MINUTES));
    }
}
