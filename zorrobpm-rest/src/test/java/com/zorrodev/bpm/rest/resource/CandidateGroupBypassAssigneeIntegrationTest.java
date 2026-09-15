package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.entity.UserGroupEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-56: candidate-group membership must NOT bypass a personal assignee.
 *
 * #1 (POF): assignee=alice, candidateGroups=reviewers; bob (member of reviewers, != alice) → 403
 * #2: same task, alice (the assignee) → 200 — assignee path preserved
 * #3: no assignee, candidateGroups=reviewers; bob (member of reviewers) → 200 — group path preserved
 * #4: no assignee, no candidateGroups; process member → 200 — open pool preserved
 * #5: SUPER_ADMIN completes a personally assigned task → 200 — admin path unchanged
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CandidateGroupBypassAssigneeIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserGroupRepository userGroupRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String aliceToken;
    private String bobToken;
    private UUID processDefinitionId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        UiUserEntity alice = createUser("sec56Alice", "USER");
        UiUserEntity bob = createUser("sec56Bob", "USER");
        aliceToken = login("sec56Alice", "secret56");
        bobToken = login("sec56Bob", "secret56");

        // bob → member of group "reviewers" (candidate pool member who must NOT be able to
        // complete a task personally assigned to alice — the WO-SEC-56 bypass).
        UserGroupEntity ug = new UserGroupEntity();
        ug.setUserId(bob.getId());
        ug.setGroupName("reviewers");
        userGroupRepository.save(ug);
        // alice → also member of "reviewers": canCompleteUserTask (AuthorizationService:104-110)
        // requires candidate-group membership BEFORE checkAssignee runs, so the assignee must
        // be a group member for her own task to be completable (WO-MT-3b layer, out of scope).
        UserGroupEntity aliceUg = new UserGroupEntity();
        aliceUg.setUserId(alice.getId());
        aliceUg.setGroupName("reviewers");
        userGroupRepository.save(aliceUg);

        // Deploy candidate-group-task.bpmn
        String bpmn = Files.readString(
            Paths.get("src/test/files/candidate-group-task.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        processDefinitionId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());

        // WO-AUD-5: both users must be process members (canCompleteUserTask requires membership)
        ProcessEntity process = processRepository.findByDefinitionKey(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText()
        ).orElseThrow();
        for (UiUserEntity u : List.of(alice, bob)) {
            ProcessMemberEntity pm = new ProcessMemberEntity();
            pm.setProcessId(process.getId());
            pm.setUserId(u.getId());
            pm.setRole("OWNER");
            pm.setAddedBy(u.getId());
            pm.setAddedAt(Instant.now());
            processMemberRepository.save(pm);
        }
    }

    // --- #1 POF: group member must NOT complete a task personally assigned to someone else ---
    @Test
    void criterion1_groupMemberCannotCompletePersonallyAssignedTask_returns403() throws Exception {
        UUID taskId = startTask("sec56Alice", "reviewers");
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + bobToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // Task stays open — not completed by the wrong user
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getCompletedAt()).isNull();
    }

    // --- #2: the assignee herself completes → 200 (assignee path preserved) ---
    @Test
    void criterion2_assigneeCompletesOwnTask_returns200() throws Exception {
        UUID taskId = startTask("sec56Alice", "reviewers");
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + aliceToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- #3: unassigned task with candidate groups — group member completes → 200 (group path preserved) ---
    @Test
    void criterion3_groupMemberCompletesUnassignedGroupTask_returns200() throws Exception {
        UUID taskId = startTask(null, "reviewers");
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + bobToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- #4: unassigned task without candidate groups — process member completes → 200 (open pool preserved) ---
    @Test
    void criterion4_processMemberCompletesOpenTask_returns200() throws Exception {
        UUID taskId = startTask(null, null);
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + bobToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- #5: SUPER_ADMIN completes a personally assigned task → 200 (admin path unchanged) ---
    @Test
    void criterion5_superAdminCompletesPersonallyAssignedTask_returns200() throws Exception {
        UUID taskId = startTask("sec56Alice", "reviewers");
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- Helpers ---

    /** Starts a process instance and sets the task's assignee/candidateGroups explicitly. */
    private UUID startTask(String assignee, String candidateGroups) throws Exception {
        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(processDefinitionId);
        startDto.setVariables(List.of());
        MvcResult startResult = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(startDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID processInstanceId = UUID.fromString(
            mapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());
        UserTaskEntity task = userTaskRepository.findAll().stream()
            .filter(t -> t.getProcessInstanceId().equals(processInstanceId) && t.getCompletedAt() == null)
            .findFirst().orElseThrow();
        task.setAssignee(assignee);
        task.setCandidateGroups(candidateGroups);
        userTaskRepository.save(task);
        return task.getId();
    }

    private UiUserEntity createUser(String username, String role) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("secret56"));
        user.setFullName(username);
        user.setRole(role);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private String login(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
