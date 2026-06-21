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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** C8-2: a parallel multi-instance binds a per-instance inputElement and loopCounter into each instance's scope. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MultiInstanceElementIntegrationTests {

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
    void parallelMultiInstanceBindsInputElementAndLoopCounterPerInstance() throws Exception {
        // items = [10, 20, 30] (JSON list) -> 3 parallel instances; each gets a scoped item (the element)
        // and loopCounter (1-based). Without per-instance scoping these would collide on (pi, name).
        String bpmn = Files.readString(Paths.get("src/test/files/test-multi-instance-element.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable items = new ProcessVariable();
        items.setName("items");
        items.setType(ProcessVariableType.JSON);
        items.setValue("[10,20,30]");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(items));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // three instances spawned, parked as user tasks
        List<ActivityEntity> tasks = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == ActivityStatus.CREATED)
            .toList();
        assertThat(tasks).hasSize(3);

        // each instance has its own scoped item (the element) and loopCounter; the three items are 10/20/30
        List<ProcessVariableEntity> scoped = variableRepository.findByProcessInstanceId(processInstanceId).stream()
            .filter(v -> v.getScopeId() != null)
            .toList();
        assertThat(scoped).filteredOn(v -> v.getName().equals("item"))
            .extracting(ProcessVariableEntity::getTextValue)
            .containsExactlyInAnyOrder("10", "20", "30");
        assertThat(scoped).filteredOn(v -> v.getName().equals("loopCounter"))
            .extracting(ProcessVariableEntity::getTextValue)
            .containsExactlyInAnyOrder("1", "2", "3");
        // and each scope belongs to a distinct instance task
        assertThat(scoped).filteredOn(v -> v.getName().equals("item"))
            .extracting(ProcessVariableEntity::getScopeId)
            .containsExactlyInAnyOrderElementsOf(tasks.stream().map(ActivityEntity::getId).toList());

        // completing every instance finishes the multi-instance and the process
        tasks.forEach(t -> runtimeService.completeUserTask(t.getId(), List.of()));
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
    }
}
