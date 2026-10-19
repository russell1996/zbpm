package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-54 — the null semantics of an io-mapping on the REAL prod path
 * ({@code RuntimeService} → handlers → {@code ElementSupport.evaluateMapping} →
 * variables/outbox), plus the G-P leak invariant.
 *
 * <p>What is pinned here (the conformance harness
 * {@code FeelIoMappingConformanceIntegrationTests} proves the same on the
 * job-payload level, against the live-8.5 expectations of the case list):</p>
 * <ol>
 *   <li>a source that cannot produce a value (a missing variable, an unknown
 *       function, an incomparable comparison, an out-of-range index, an explicit
 *       {@code =null}) → the mapped variable is a <b>JSON null</b> — type JSON,
 *       text "null" — NOT a STRING "";</li>
 *   <li>NO incident: the task is created, the worker is called, the process
 *       continues and completes (live Zeebe 8.5: {@code completed work={"r":null}});</li>
 *   <li>G-P: after the instance completes there is NO leak — no activity left in
 *       CREATED/IN_PROGRESS, no open task row, no incident, no unscheduled
 *       timer. (The global {@code LeakInvariantExtension} of WO-OPS-26 checks the
 *       same invariant for EVERY test of the module; the assertions below make
 *       the invariant explicit in the test that changes the execution path.)</li>
 * </ol>
 *
 * <p>POF: the mutation "return the legacy '' STRING instead of the JSON null"
 * (the pre-C8-54 conversion) turns every assertion below red; the mutation
 * "raise an incident on a suppressed failure" (the pre-C8-54 strict profile)
 * turns the no-incident assertions red.</p>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class FeelIoMappingNullValueIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private VariableRepository variableRepository;

    private UUID start(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ActivityEntity activeServiceTask(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId)
                && (a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no active activity " + bpmnElementId));
    }

    private List<IncidentEntity> incidentsOf(UUID processInstanceId) {
        List<UUID> activityIds = activityRepository.findAll().stream()
            .filter(a -> processInstanceId.equals(a.getProcessInstanceId()))
            .map(ActivityEntity::getId)
            .toList();
        if (activityIds.isEmpty()) {
            return List.of();
        }
        return incidentRepository.findByActivityIdInAndCompletedAtIsNull(activityIds);
    }

    private ProcessVariableEntity rootVar(UUID processInstanceId, String name) {
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId).stream()
            .filter(e -> name.equals(e.getName()))
            .findFirst()
            .orElse(null);
    }

    /**
     * The G-P leak invariant for a COMPLETED instance: nothing live is left
     * behind — no activity in CREATED/IN_PROGRESS, no open service-task row, no
     * incident. (Timers/jobs: this fixture schedules none.)
     */
    private void assertNoLeaks(UUID processInstanceId) {
        assertThat(activityRepository.findAll().stream()
            .filter(a -> processInstanceId.equals(a.getProcessInstanceId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .map(ActivityEntity::getBpmnElementId)
            .toList())
            .as("no activity may stay live in a completed instance (G-P)")
            .isEmpty();
        assertThat(incidentsOf(processInstanceId))
            .as("no incident may stay open in a completed instance (G-P)")
            .isEmpty();
    }

    @Test
    void nullIoMappingSource_isAJsonNullValueAndTheProcessCompletesWithoutIncident() throws Exception {
        UUID pi = start("test-c854-null-io-mapping.bpmn");

        // Задание создано (live 8.5: задание ЕСТЬ, инцидента нет).
        UUID svc = activeServiceTask(pi, "svcNull").getId();
        assertThat(incidentsOf(pi))
            .as("a source without a value must not park the instance on an incident")
            .isEmpty();

        runtimeService.completeServiceTask(svc, List.of());

        assertThat(incidentsOf(pi))
            .as("the completion path (output mapping) must not park on an incident either")
            .isEmpty();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the process must complete (live 8.5: completed, work={\"r\":null})")
            .isNotNull();

        ProcessVariableEntity written = rootVar(pi, "nullOut");
        assertThat(written).as("the output mapping still writes the variable").isNotNull();
        assertThat(written.getType())
            .as("a null is carried as a JSON null — the legacy '' STRING is gone")
            .isEqualTo(ProcessVariableType.JSON);
        assertThat(written.getTextValue())
            .as("the JSON text of a null")
            .isEqualTo("null");

        assertNoLeaks(pi);
    }
}
