package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
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
public class EventBasedGatewayIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Transactional
    @Test
    void messageWinsTheRaceAndTimerBranchIsCancelled() throws Exception {
        // start -> event-based gateway arming a message catch ("approve") and a 1h timer catch. The
        // message arrives first: its branch proceeds and the timer branch is cancelled.
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-based-gateway.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        // both catch events armed, nothing fired yet
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        activityService.correlateMessage("approve", processInstanceId, List.of());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("msgCatch") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endApprove") && a.getStatus() == ActivityStatus.COMPLETED);
        // the losing timer catch is cancelled and its branch never reached its end
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("timerCatch") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("endTimeout"));
    }
}
