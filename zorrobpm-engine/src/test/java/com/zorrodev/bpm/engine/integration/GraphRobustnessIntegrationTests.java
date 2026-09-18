package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
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

        List<IncidentEntity> incidents = incidentRepository.findAll().stream()
            .filter(i -> i.getActivityId().equals(s1.getId()))
            .toList();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains("ghost").contains("not found");
    }
}
