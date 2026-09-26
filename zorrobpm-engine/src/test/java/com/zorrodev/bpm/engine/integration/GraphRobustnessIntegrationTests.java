package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
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

/** C8-7 (graph null-safety): a sequence flow whose target element is missing raises a clear incident, not an NPE. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class GraphRobustnessIntegrationTests {

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

    @Transactional
    @Test
    void danglingSequenceFlowTargetRaisesInformativeIncident() throws Exception {
        // s1's outgoing flow f2 targets "ghost", which is not in the model: the engine must park a clear
        // incident naming the missing target instead of throwing a bare NullPointerException.
        String bpmn = Files.readString(Paths.get("src/test/files/test-dangling-flow.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        ActivityEntity s1 = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("s1"))
            .findFirst().orElseThrow();

        // WO-ENG-23: s1 already COMPLETED before its outgoing navigation failed, so the
        // incident parks on a fresh fallback activity for (token, s1) — not on s1's own
        // COMPLETED row (flipping it to ERROR would corrupt history). The guarantee of
        // this test is the informative message, not the binding row.
        //
        // WO-URGENT-1 (NEW2-06): scope the scan to THIS test's instance via
        // the activity-subquery spec — never a global findAll(). A sibling
        // @Transactional test (IncidentContextIntegrationTests,
        // deletedInstance_incidentStillReturned_withEmptyContext) leaves a
        // committed orphan incident behind (H2 SET REFERENTIAL_INTEGRITY TRUE
        // commits the open tx, so the row survives that test's rollback), and
        // a global scan trips over it (orElseThrow on its missing activity) —
        // red or green depending on class order. The spec excludes
        // foreign/orphan rows in SQL, so this test is order-independent.
        // The leak source itself is left untouched (out of scope, V7 — needs
        // its own WO if cleanup is wanted).
        List<IncidentEntity> incidents = incidentRepository.findAll(
                IncidentRepository.byProcessInstanceId(processInstanceId)).stream()
            .filter(i -> {
                ActivityEntity parked = activityRepository.findById(i.getActivityId()).orElseThrow();
                return parked.getBpmnElementId().equals("s1")
                    && parked.getToken().equals(s1.getToken());
            })
            .toList();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains("ghost").contains("not found");
        assertThat(incidents.get(0).getActivityId())
            .as("incident must park on a fresh fallback row, not on the completed s1 row")
            .isNotEqualTo(s1.getId());
        assertThat(s1.getStatus())
            .as("the completed s1 row must stay COMPLETED (history preserved)")
            .isEqualTo(ActivityStatus.COMPLETED);
        ActivityEntity parked = activityRepository.findById(incidents.get(0).getActivityId()).orElseThrow();
        assertThat(parked.getStatus())
            .as("the fallback row carries the parked failure")
            .isEqualTo(ActivityStatus.ERROR);
    }
}
