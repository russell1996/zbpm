package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User-task query filters. NOTE: the {@code assignee} from {@code zeebe:assignmentDefinition} is parsed
 * into the model but NOT persisted onto the {@code user_tasks} row at creation, so the assignee/assigned
 * filters currently match only the "unassigned" case — these tests pin that current behaviour (partial).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UserTaskQueryIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;

    private UUID start() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-usertask-query.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private UserTaskQuery query(UUID pi) {
        UserTaskQuery q = new UserTaskQuery();
        q.setProcessInstanceId(pi);
        return q;
    }

    @Transactional
    @Test
    void filtersByCompleted() throws Exception {
        UUID pi = start();
        UserTaskQuery active = query(pi);
        active.setCompleted(false);
        assertThat(queryService.findUserTasks(active).getData()).hasSize(1);

        UserTaskQuery done = query(pi);
        done.setCompleted(true);
        assertThat(queryService.findUserTasks(done).getData()).isEmpty();
    }

    @Transactional
    @Test
    void singleGetReturnsTheTask() throws Exception {
        UUID pi = start();
        UUID taskId = queryService.findUserTasks(query(pi)).getData().get(0).getId();
        UserTask task = queryService.getUserTask(taskId);
        assertThat(task.getId()).isEqualTo(taskId);
        assertThat(task.getCode()).isEqualTo("approve");
    }

    @Transactional
    @Test
    void assignedFilterReflectsCurrentBehaviour() throws Exception {
        UUID pi = start();
        // current behaviour: assignee is not persisted, so the task counts as unassigned
        UserTaskQuery unassigned = query(pi);
        unassigned.setAssigned(false);
        assertThat(queryService.findUserTasks(unassigned).getData()).hasSize(1);

        UserTaskQuery assigned = query(pi);
        assigned.setAssigned(true);
        assertThat(queryService.findUserTasks(assigned).getData()).isEmpty();

        UserTaskQuery byAssignee = query(pi);
        byAssignee.setAssignee("alice");
        assertThat(queryService.findUserTasks(byAssignee).getData()).isEmpty();
    }
}
