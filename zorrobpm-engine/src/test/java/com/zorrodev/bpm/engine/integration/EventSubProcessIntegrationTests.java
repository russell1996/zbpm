package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
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
public class EventSubProcessIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private ActivityRepository activityRepository;

    private List<ActivityEntity> activitiesOf(UUID processInstanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
    }

    private UUID start(UUID definitionId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definitionId);
        return runtimeService.startProcessInstance(dto).getId();
    }

    @Transactional
    @Test
    void interruptingMessageEventSubProcessCancelsMainFlowAndRunsHandler() throws Exception {
        // the main flow parks at mainTask; correlating "cancelOrder" triggers the interrupting event
        // sub-process, which cancels the main flow and runs to its own end, completing the instance.
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-subprocess.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId());
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        activityService.correlateMessage("cancelOrder", processInstanceId, List.of());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        // main task was interrupted; the handler ran to its end; the main end was never reached
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("mainTask") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("evEnd") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("mainEnd"));
    }

    @Transactional
    @Test
    void nonInterruptingMessageEventSubProcessRunsAlongsideMainFlowAndCanFireRepeatedly() throws Exception {
        // the main flow keeps running; each "ping" spawns a handler in its own scope. The subscription is
        // not consumed, so the handler can fire more than once. The main flow still completes normally.
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-subprocess-noninterrupting.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId());

        // fire the non-interrupting handler twice; the main flow must stay alive (instance not completed)
        activityService.correlateMessage("ping", processInstanceId, List.of());
        activityService.correlateMessage("ping", processInstanceId, List.of());
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        // the main task was never interrupted; completing it finishes the instance normally
        ActivityEntity mainTask = activitiesOf(processInstanceId).stream()
            .filter(a -> a.getBpmnElementId().equals("mainTask") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
        runtimeService.completeUserTask(mainTask.getId(), List.of());

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        // the handler ran twice (two evEnd activities) and the main flow reached its own end
        assertThat(activities).filteredOn(a -> a.getBpmnElementId().equals("evEnd") && a.getStatus() == ActivityStatus.COMPLETED).hasSize(2);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("mainEnd") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void mainFlowCompletesNormallyWhenNoTriggeringMessageArrives() throws Exception {
        // without the triggering message the event sub-process stays dormant and the main flow completes
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-subprocess.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId());

        ActivityEntity mainTask = activitiesOf(processInstanceId).stream()
            .filter(a -> a.getBpmnElementId().equals("mainTask") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
        runtimeService.completeUserTask(mainTask.getId(), List.of());

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("mainEnd") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("evEnd"));
    }
}
