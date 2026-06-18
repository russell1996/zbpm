package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MessageStartIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Transactional
    @Test
    void correlatingMessageStartsNewInstance() throws Exception {
        // msgStart (message "order-received") -> endEvent; no plain start. Correlating the message
        // with no target instance must create and run a new instance.
        String bpmn = Files.readString(Paths.get("src/test/files/test-message-start.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        long before = processInstanceRepository.findAll().stream()
            .filter(pi -> pi.getProcessDefinitionId().equals(model.getId())).count();

        activityService.correlateMessage("order-received", null, List.of());

        List<ProcessInstanceEntity> instances = processInstanceRepository.findAll().stream()
            .filter(pi -> pi.getProcessDefinitionId().equals(model.getId())).toList();
        assertThat(instances).hasSize((int) before + 1);
        assertThat(instances).allMatch(pi -> pi.getCompletedAt() != null);

        // the instance ran from the message start through to the end
        var instanceId = instances.get(0).getId();
        var activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(instanceId)).toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("msgStart") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }
}
