package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** C8-2: a multi-instance aggregates each instance's outputElement into the outputCollection (a JSON list). */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MultiInstanceOutputIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Transactional
    @Test
    void multiInstanceAggregatesOutputElementIntoOutputCollection() throws Exception {
        // items = [10,20,30] -> 3 instances; each appends item*2 to "doubled" -> [20,40,60].
        String bpmn = Files.readString(Paths.get("src/test/files/test-multi-instance-output.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable items = new ProcessVariable();
        items.setName("items");
        items.setType(ProcessVariableType.JSON);
        items.setValue("[10,20,30]");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(items));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        List<ActivityEntity> tasks = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == ActivityStatus.CREATED)
            .toList();
        assertThat(tasks).hasSize(3);
        tasks.forEach(t -> runtimeService.completeUserTask(t.getId(), List.of()));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        ProcessVariableEntity doubled = variableRepository.findByNameAndProcessInstanceId("doubled", processInstanceId).orElseThrow();
        assertThat(doubled.getType()).isEqualTo(ProcessVariableType.JSON);
        List<Integer> values = new ObjectMapper().readValue(doubled.getTextValue(), new tools.jackson.core.type.TypeReference<List<Integer>>() {});
        assertThat(values).containsExactlyInAnyOrder(20, 40, 60);
    }
}
