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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.catchThrowable;

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
    // Criterion #4: FEEL returns non-date/duration type → EngineException
    // ----------------------------------------------------------------

    @Test
    void feelExpressionReturnsGarbage_throwsEngineException() {
        // Deploying the process itself succeeds; the exception happens at runtime when the
        // boundary timer is scheduled during user task entry.
        // deployAndStart wraps in RuntimeException, so we catch the cause.
        Throwable thrown = catchThrowable(() ->
            deployAndStart("test-boundary-feel-garbage.bpmn", null)
        );
        assertThat(thrown)
            .as("FEEL expression returning true as timeDuration should raise EngineException")
            .isNotNull()
            .isInstanceOf(RuntimeException.class)
            .hasCauseInstanceOf(EngineException.class);
        assertThat(thrown.getCause())
            .as("EngineException should contain the element id")
            .hasMessageContaining("boundary1");
    }
}
