package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
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

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MultiInstanceIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private List<ActivityEntity> miTasks(UUID processInstanceId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == status)
            .toList();
    }

    @Transactional
    @Test
    void parallelMultiInstanceSpawnsNInstancesAndJoinsWhenAllComplete() throws Exception {
        // loopCardinality 3 -> three parallel user-task instances; the flow continues only after the last
        // one completes.
        String bpmn = Files.readString(Paths.get("src/test/files/test-multi-instance.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // three instances are parked
        List<ActivityEntity> parked = miTasks(processInstanceId, ActivityStatus.CREATED);
        assertThat(parked).hasSize(3);
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        // completing the first two does not advance the flow (instance still running)
        runtimeService.completeUserTask(parked.get(0).getId(), List.of());
        runtimeService.completeUserTask(parked.get(1).getId(), List.of());
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        // completing the third (last) instance fires the join and the flow reaches the end
        runtimeService.completeUserTask(parked.get(2).getId(), List.of());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        assertThat(miTasks(processInstanceId, ActivityStatus.COMPLETED)).hasSize(3);
        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }
}
