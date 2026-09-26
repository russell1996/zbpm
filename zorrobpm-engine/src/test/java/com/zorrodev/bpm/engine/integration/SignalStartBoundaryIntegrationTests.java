package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
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
public class SignalStartBoundaryIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Transactional
    @Test
    void interruptingSignalBoundaryCancelsHostAndTakesBoundaryPath() throws Exception {
        // parallel branches: gate1 (host with interrupting signal boundary "cancel") and gate2 ->
        // signal throw "cancel". Both user tasks park; completing gate2 throws the signal, which fires
        // the boundary on gate1 (interrupting): gate1 is cancelled and flow goes via the boundary path.
        String bpmn = Files.readString(Paths.get("src/test/files/test-signal-boundary.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        UserTaskQuery userTaskQuery = new UserTaskQuery();
        userTaskQuery.setProcessInstanceId(processInstanceId);
        PagedDataDTO<UserTask> userTasks = queryService.findUserTasks(userTaskQuery, null);
        assertThat(userTasks.getData()).hasSize(2);
        UUID gate2Id = userTasks.getData().stream()
            .filter(t -> "gate2".equals(t.getName()))
            .findFirst().orElseThrow().getId();

        // completing gate2 throws "cancel", which interrupts gate1 via its signal boundary
        runtimeService.completeUserTask(gate2Id, List.of());

        ProcessInstance processInstance = queryService.getProcessInstance(processInstanceId);
        assertThat(processInstance.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("gate1") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("boundaryEnd") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("endA"));
    }

    @Transactional
    @Test
    void signalThrowStartsSubscribedDefinitionViaSignalStart() throws Exception {
        // deploy a receiver started by signal "kickoff", then a starter that throws "kickoff". Running
        // the starter must broadcast the signal and start a fresh instance of the receiver definition.
        String receiverBpmn = Files.readString(Paths.get("src/test/files/test-signal-start-receiver.bpmn"));
        ProcessDefinition receiver = processDefinitionService.addProcessDefinition(receiverBpmn);
        String starterBpmn = Files.readString(Paths.get("src/test/files/test-signal-start-starter.bpmn"));
        ProcessDefinition starter = processDefinitionService.addProcessDefinition(starterBpmn);

        // no receiver instances exist before the signal is thrown
        assertThat(processInstanceRepository.findAll(ProcessInstanceRepository.byProcessDefinitionId(receiver.getId()))).isEmpty();

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(starter.getId());
        runtimeService.startProcessInstance(dto);

        // the thrown signal started exactly one receiver instance, which ran to completion
        List<ProcessInstanceEntity> receiverInstances =
            processInstanceRepository.findAll(ProcessInstanceRepository.byProcessDefinitionId(receiver.getId()));
        assertThat(receiverInstances).hasSize(1);
        assertThat(receiverInstances.get(0).getCompletedAt()).isNotNull();
    }
}
