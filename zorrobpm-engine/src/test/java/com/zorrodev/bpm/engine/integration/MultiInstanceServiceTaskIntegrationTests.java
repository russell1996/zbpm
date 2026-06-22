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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** C8-2: multi-instance on a service task — per-instance jobs, inputElement binding and outputCollection. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MultiInstanceServiceTaskIntegrationTests {

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
    void multiInstanceServiceTaskSpawnsJobsAndAggregatesOutput() throws Exception {
        // items = [10,20,30] -> 3 service-task jobs, each with its own item; completing them all aggregates
        // item*2 into doubled = [20,40,60].
        String bpmn = Files.readString(Paths.get("src/test/files/test-multi-instance-service.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable items = new ProcessVariable();
        items.setName("items");
        items.setType(ProcessVariableType.JSON);
        items.setValue("[10,20,30]");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(items));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        List<ActivityEntity> jobs = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miService") && a.getStatus() == ActivityStatus.CREATED)
            .toList();
        assertThat(jobs).hasSize(3);

        // each instance has its own scoped item
        List<ProcessVariableEntity> scopedItems = variableRepository.findByProcessInstanceId(processInstanceId).stream()
            .filter(v -> v.getScopeId() != null && v.getName().equals("item"))
            .toList();
        assertThat(scopedItems).extracting(ProcessVariableEntity::getTextValue)
            .containsExactlyInAnyOrder("10", "20", "30");

        jobs.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
        ProcessVariableEntity doubled = variableRepository.findByNameAndProcessInstanceId("doubled", processInstanceId).orElseThrow();
        List<Integer> values = new ObjectMapper().readValue(doubled.getTextValue(), new TypeReference<List<Integer>>() {});
        assertThat(values).containsExactlyInAnyOrder(20, 40, 60);
    }
}
