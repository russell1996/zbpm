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

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    @Transactional
    @Test
    void parallelMultiInstanceFromZeebeInputCollection() throws Exception {
        // Camunda 8 style: the instance count comes from <zeebe:loopCharacteristics inputCollection="=[1,2,3]">
        // (the collection's size), not loopCardinality.
        String bpmn = Files.readString(Paths.get("src/test/files/test-multi-instance-zeebe.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        List<ActivityEntity> parked = miTasks(processInstanceId, ActivityStatus.CREATED);
        assertThat(parked).hasSize(3);

        for (ActivityEntity task : parked) {
            runtimeService.completeUserTask(task.getId(), List.of());
        }

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        assertThat(miTasks(processInstanceId, ActivityStatus.COMPLETED)).hasSize(3);
    }

    @Transactional
    @Test
    void sequentialMultiInstanceRunsOneInstanceAtATime() throws Exception {
        // sequential loopCardinality 3: exactly one instance is active at any moment; the next is created
        // only when the current one completes.
        String bpmn = Files.readString(Paths.get("src/test/files/test-multi-instance-sequential.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        Set<UUID> done = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            List<ActivityEntity> pending = activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
                .filter(a -> a.getBpmnElementId().equals("miTask") && !done.contains(a.getId()))
                .toList();
            // only one instance exists at a time (would be 3 at once if parallel)
            assertThat(pending).hasSize(1);
            assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
            runtimeService.completeUserTask(pending.get(0).getId(), List.of());
            done.add(pending.get(0).getId());
        }

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        assertThat(miTasks(processInstanceId, ActivityStatus.COMPLETED)).hasSize(3);
    }

    @Transactional
    @Test
    void completionConditionEndsMultiInstanceEarly() throws Exception {
        // sequential loopCardinality 5 with completionCondition "stop = true": completing the second
        // instance with stop=true ends the multi-instance early (only 2 of 5 ran).
        String bpmn = Files.readString(Paths.get("src/test/files/test-multi-instance-completion.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        Set<UUID> done = new HashSet<>();
        // first instance: stop=false -> a second instance is started
        UUID first = nextInstance(processInstanceId, done);
        runtimeService.completeUserTask(first, List.of(bool("stop", false)));
        done.add(first);
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        // second instance: stop=true -> completion condition holds, multi-instance ends early
        UUID second = nextInstance(processInstanceId, done);
        runtimeService.completeUserTask(second, List.of(bool("stop", true)));
        done.add(second);

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        // only two instances ran (not five); the flow reached the end
        assertThat(miTasks(processInstanceId, ActivityStatus.COMPLETED)).hasSize(2);
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED)).isTrue();
    }

    // --- WO-ENG-8: completionCondition with completedInstances/totalInstances ---
    // BPMN: start → MI userTask(2 parallel, completionCondition="=completedInstances = totalInstances") → endEvent
    // RED (without fix): undefined vars → null=null → true → first completion ends MI, process completes prematurely
    // GREEN (with fix): injected completedInstances/totalInstances → 1=2 → false → wait for second completion

    @Transactional
    @Test
    void completionConditionWithStandardMiVars_waitsForAllInstances() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-eng-8-completioncondition.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // Two parallel MI user tasks should be active
        List<ActivityEntity> tasks = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == ActivityStatus.CREATED)
            .toList();
        assertThat(tasks).hasSize(2);
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        // Complete first MI task — with the fix this should NOT complete the process
        runtimeService.completeUserTask(tasks.get(0).getId(), List.of());

        // After first completion: process instance must NOT be completed
        // (RED without fix: null=null → true → instance completed; GREEN with fix: 1=2 → false → stays alive)
        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNull();

        // Complete second MI task — after both done, MI should finish
        List<ActivityEntity> remaining = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == ActivityStatus.CREATED)
            .toList();
        assertThat(remaining).hasSize(1);
        runtimeService.completeUserTask(remaining.get(0).getId(), List.of());

        // After second completion: both miTask instances done, process reaches endEvent and completes
        ProcessInstance pi2 = queryService.getProcessInstance(processInstanceId);
        assertThat(pi2.getCompletedAt()).isNotNull();

        // Both MI tasks should be completed
        long completed = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == ActivityStatus.COMPLETED)
            .count();
        assertThat(completed).isEqualTo(2);
    }

    private UUID nextInstance(UUID processInstanceId, Set<UUID> done) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && !done.contains(a.getId()))
            .map(ActivityEntity::getId)
            .findFirst().orElseThrow();
    }

    private ProcessVariable bool(String name, boolean value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.BOOLEAN);
        v.setValue(Boolean.toString(value));
        return v;
    }
}
