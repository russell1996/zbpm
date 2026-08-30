package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateType;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three tasklist screens, each over real HTTP against the real engine and database, each in a
 * single request. This is the acceptance check for the change: if any of them needed more than one
 * call, the client could not paginate the result correctly.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class TasklistQueriesEndToEndTests {

    private static final String IIN = "901231300123";
    private static final String OTHER_IIN = "880101400456";
    private static final String ACCOUNTING = "accounting";
    private static final String MANAGERS = "managers";
    private static final String STRANGERS = "strangers";

    @Autowired private MockMvc mockMvc;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserTaskCandidateRepository candidateRepository;

    private UUID processInstanceId;

    @BeforeEach
    void seed() throws Exception {
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(
            Files.readString(Paths.get("src/test/files/user-task.bpmn")));

        ProcessInstanceEntity instance = new ProcessInstanceEntity();
        instance.setId(UUID.randomUUID());
        instance.setProcessDefinitionId(definition.getId());
        instance.setStartedAt(Instant.now());
        processInstanceRepository.save(instance);
        processInstanceId = instance.getId();

        Instant base = Instant.parse("2026-05-01T09:00:00Z");
        // mine
        task(definition.getId(), IIN, base, List.of(), List.of());
        task(definition.getId(), IIN, base.plus(1, ChronoUnit.HOURS), List.of(), List.of());
        // available to me: personally, and through each of my groups
        task(definition.getId(), null, base.plus(2, ChronoUnit.HOURS), List.of(IIN), List.of());
        task(definition.getId(), null, base.plus(3, ChronoUnit.HOURS), List.of(), List.of(ACCOUNTING));
        task(definition.getId(), null, base.plus(4, ChronoUnit.HOURS), List.of(), List.of(MANAGERS));
        // available to me but already taken by someone else
        task(definition.getId(), OTHER_IIN, base.plus(5, ChronoUnit.HOURS), List.of(), List.of(ACCOUNTING));
        // nothing to do with me
        task(definition.getId(), OTHER_IIN, base.plus(6, ChronoUnit.HOURS), List.of(), List.of(STRANGERS));
    }

    @Test
    void myTasks_inOneRequest() throws Exception {
        perform(get("/user-tasks")
            .param("processInstanceId", processInstanceId.toString())
            .param("relatedToUser", IIN)
            .param("relation", "ASSIGNEE")
            .param("completed", "false"))
            .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void tasksAvailableToMe_inOneRequest() throws Exception {
        perform(get("/user-tasks")
            .param("processInstanceId", processInstanceId.toString())
            .param("relatedToUser", IIN)
            .param("relatedToGroups", ACCOUNTING)
            .param("relatedToGroups", MANAGERS)
            .param("relation", "CANDIDATE")
            .param("assigned", "false"))
            .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void everythingConcerningMe_sortedNewestFirst_inOneRequest() throws Exception {
        perform(get("/user-tasks")
            .param("processInstanceId", processInstanceId.toString())
            .param("relatedToUser", IIN)
            .param("relatedToGroups", ACCOUNTING)
            .param("relatedToGroups", MANAGERS)
            .param("relation", "ANY")
            .param("completed", "false")
            .param("sort", "createdAt")
            .param("direction", "DESC"))
            .andExpect(jsonPath("$.totalElements").value(6))
            .andExpect(jsonPath("$.data[0].createdAt").value("2026-05-01T14:00:00Z"))
            .andExpect(jsonPath("$.data[5].createdAt").value("2026-05-01T09:00:00Z"));
    }

    private ResultActions perform(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    private void task(UUID processDefinitionId, String assignee, Instant createdAt,
                      List<String> candidateUsers, List<String> candidateGroups) {
        UserTaskEntity task = new UserTaskEntity();
        task.setId(UUID.randomUUID());
        task.setBpmnElementId("approveTask");
        task.setProcessInstanceId(processInstanceId);
        task.setProcessDefinitionId(processDefinitionId);
        task.setCreatedAt(createdAt);
        task.setAssignee(assignee);
        userTaskRepository.save(task);

        candidateUsers.forEach(value -> candidate(task.getId(), UserTaskCandidateType.USER, value));
        candidateGroups.forEach(value -> candidate(task.getId(), UserTaskCandidateType.GROUP, value));
    }

    private void candidate(UUID taskId, UserTaskCandidateType type, String value) {
        UserTaskCandidateEntity candidate = new UserTaskCandidateEntity();
        candidate.setId(UUID.randomUUID());
        candidate.setTaskId(taskId);
        candidate.setCandidateType(type);
        candidate.setCandidateValue(value);
        candidateRepository.save(candidate);
    }
}
