package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-31 F23: Multi-instance cardinality must use {@code intValueExact()} (no silent
 * truncation), enforce an upper limit, and raise an incident on any invalid or over-limit
 * value — never a silent skip and never unbounded spawn.
 *
 * <pre>
 * RED (pre-fix):  tests 1-4 FAIL — old code silently truncates/wraps/skips/spawns
 * GREEN (post-fix): all 6 pass — invalid values raise incidents, valid values spawn correctly
 * </pre>
 *
 * Fixture: {@code test-rel31-mi-cardinality.bpmn} — start → MI userTask(=count) → endEvent.
 * The {@code count} variable is provided at start via {@link StartProcessInstanceDTO#variables}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MultiInstanceCardinalityValidationIntegrationTests {

    /** Engine upper limit constant — must match {@code MultiInstanceExecutor.MAX_MI_CARDINALITY}. */
    private static final int MAX_MI_CARDINALITY = 1_000;

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;

    private ProcessDefinition deployBpmn() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-rel31-mi-cardinality.bpmn"));
        return processDefinitionService.addProcessDefinition(bpmn);
    }

    private UUID startWithCount(Object countValue, ProcessVariableType type) throws Exception {
        ProcessDefinition model = deployBpmn();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        ProcessVariable count = new ProcessVariable();
        count.setName("count");
        count.setType(type);
        count.setValue(countValue.toString());
        dto.getVariables().add(count);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> miTasks(UUID pi, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == status)
            .toList();
    }

    private IncidentEntity assertMiIncident(UUID pi, String messagePart) {
        List<ActivityEntity> errorActivities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("miTask"))
            .filter(a -> a.getStatus() == ActivityStatus.ERROR)
            .toList();
        assertThat(errorActivities).as(
            "Expected an ERROR activity on miTask element for process instance %s", pi)
            .hasSize(1);
        List<IncidentEntity> incidents = incidentRepository.findAll().stream()
            .filter(i -> i.getActivityId().equals(errorActivities.get(0).getId()))
            .toList();
        assertThat(incidents).as(
            "Expected exactly one incident on the ERROR activity").hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .as("Incident message should contain '%s'", messagePart)
            .contains(messagePart);
        return incidents.get(0);
    }

    // ─── RED (pre-fix) — 4 cases that must FAIL on the old code ────────────────

    /**
     * F23 RED #1 — fractional cardinality: old {@code Number.intValue()} silently truncates
     * 2.5 → 2, spawning 2 instances. After fix: {@code intValueExact()} throws → incident.
     */
    @Transactional
    @Test
    void fractionalCardinalityRaisesIncidentInsteadOfTruncating() throws Exception {
        UUID pi = startWithCount("2.5", ProcessVariableType.DOUBLE);

        // RED (pre-fix): old code spawns 2 tasks (truncated from 2.5) — no ERROR, no incident.
        // GREEN (post-fix): incident raised on miTask, no instances spawned.
        assertThat(miTasks(pi, ActivityStatus.CREATED)).isEmpty();
        assertMiIncident(pi, "cardinality is not a valid whole number");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
    }

    /**
     * F23 RED #2 — negative cardinality: old {@code intValue()} returns -1, which hits the
     * existing {@code count <= 0} → "zero instances, skipping" → process completes silently.
     * After fix: negative is an invalid cardinality → incident.
     */
    @Transactional
    @Test
    void negativeCardinalityRaisesIncidentInsteadOfSilentSkip() throws Exception {
        UUID pi = startWithCount("-1", ProcessVariableType.LONG);

        // RED (pre-fix): process completes silently (skip path) — completedAt != null.
        // GREEN (post-fix): incident raised, process still alive.
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
        assertMiIncident(pi, "cardinality must be non-negative");
    }

    /**
     * F23 RED #3 — value beyond int range: {@code 3_000_000_000} exceeds {@code Integer.MAX_VALUE}.
     * Old {@code Number.intValue()} wraps silently to −1 294 967 296 → count ≤ 0 → silent skip.
     * After fix: {@code intValueExact()} throws ArithmeticException → incident.
     */
    @Transactional
    @Test
    void cardinalityBeyondIntRangeRaisesIncidentInsteadOfOverflowWrap() throws Exception {
        UUID pi = startWithCount("3000000000", ProcessVariableType.LONG);

        // RED (pre-fix): process completes silently (wrapped negative → skip).
        // GREEN (post-fix): incident raised.
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
        assertMiIncident(pi, "cardinality is not a valid whole number");
    }

    /**
     * F23 RED #4 — above engine limit: count=1001 exceeds {@code MAX_MI_CARDINALITY=1000}.
     * Old code spawns 1001 instances (unbounded). After fix: incident.
     */
    @Transactional
    @Test
    void cardinalityAboveEngineLimitRaisesIncidentInsteadOfUnboundedSpawn() throws Exception {
        UUID pi = startWithCount("1001", ProcessVariableType.LONG);

        // RED (pre-fix): old code spawns 1001 tasks — CREATED has size 1001.
        // GREEN (post-fix): incident, no instances.
        assertThat(miTasks(pi, ActivityStatus.CREATED)).isEmpty();
        assertMiIncident(pi, "exceeds the engine limit");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
    }

    // ─── GREEN (regression/positive) — valid values that must work ──────────────

    /**
     * F23 GREEN #1 — zero: must remain a valid "zero instances, skipping" path.
     * The flow proceeds to endEvent and the process completes.
     */
    @Transactional
    @Test
    void zeroCardinalitySkipsTheMultiInstanceAndContinues() throws Exception {
        UUID pi = startWithCount("0", ProcessVariableType.LONG);

        // zero → skip → endEvent → process completes
        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt()).isNotNull();
        assertThat(miTasks(pi, ActivityStatus.CREATED)).isEmpty();
        // no ERROR activities for miTask — the element was skipped, not errored
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("miTask"))
            .filter(a -> a.getStatus() == ActivityStatus.ERROR)
            .count()).isZero();
    }

    /**
     * F23 GREEN #2 — boundary valid value: exactly {@code MAX_MI_CARDINALITY} spawns that many
     * user-task instances, all CREATED. No incident. Process stays alive (waiting for completions).
     */
    @Transactional
    @Test
    void cardinalityAtEngineLimitSpawnsExactlyThatMany() throws Exception {
        UUID pi = startWithCount(Integer.toString(MAX_MI_CARDINALITY), ProcessVariableType.LONG);

        // exactly MAX: all spawned as CREATED user-task activities
        assertThat(miTasks(pi, ActivityStatus.CREATED)).hasSize(MAX_MI_CARDINALITY);
        // no ERROR activities for miTask
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("miTask"))
            .filter(a -> a.getStatus() == ActivityStatus.ERROR)
            .count()).isZero();
        // process still alive (needs MAX completions to finish)
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
    }
}
