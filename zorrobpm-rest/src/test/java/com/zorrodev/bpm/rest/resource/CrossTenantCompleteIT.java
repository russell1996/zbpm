package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-AUD-5 F18: Cross-tenant unassigned task completion prevention.
 *
 * #1: Non-member completes unassigned task of foreign process → 403
 * #2: Process member completes unassigned task → 200
 * #3: Assignee completes own task → 200 (covered by AssigneeCheckIntegrationTest.criterion1)
 * #4: Candidate group member completes → 200 (not broken)
 * #5: Super-admin completes → 200 (not broken)
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossTenantCompleteIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserGroupRepository userGroupRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String memberToken;
    private String nonMemberToken;
    private UUID processDefinitionId;
    private UUID processId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        // Create a member and a non-member
        UiUserEntity member = createAndSaveUser("aud5member", "USER");
        UiUserEntity nonMember = createAndSaveUser("aud5outsider", "USER");
        memberToken = login("aud5member", "passr");
        nonMemberToken = login("aud5outsider", "passr");

        // Deploy BPMN with a user task
        String bpmn = Files.readString(
            Paths.get("src/test/files/assignee-task.bpmn"), StandardCharsets.UTF_8);
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

        // Get processId (needed for membership check)
        ProcessEntity process = processRepository.findByDefinitionKey(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText()
        ).orElseThrow();
        processId = process.getId();

        // Add member to process
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(processId);
        pm.setUserId(member.getId());
        pm.setRole("OWNER");
        pm.setAddedBy(member.getId());
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);
    }

    // --- #1: Non-member completes unassigned task → 403 (proof-of-failure §1b) ---

    @Test
    void criterion1_nonMemberCompletesUnassignedTask_returns403() throws Exception {
        UUID taskId = startProcessAndReturnTask();
        unassignTask(taskId);

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- #2: Process member completes unassigned task → 200 ---

    @Test
    void criterion2_memberCompletesUnassignedTask_returns200() throws Exception {
        UUID taskId = startProcessAndReturnTask();
        unassignTask(taskId);

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + memberToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- #3: Assignee completes own task → 200 ---
    // (Assignee path covered by AssigneeCheckIntegrationTest.criterion1)

    // --- #4: Candidate group member completes → 200 (not broken) ---

    @Test
    void criterion4_candidateGroupMemberCompletes_returns200() throws Exception {
        // Create a user and add to group "managers"
        UiUserEntity cgMember = createAndSaveUser("aud5cgmember", "USER");
        String cgMemberToken = login("aud5cgmember", "passr");

        // Add user to group "managers"
        UserGroupEntity ug = new UserGroupEntity();
        ug.setUserId(cgMember.getId());
        ug.setGroupName("managers");
        userGroupRepository.save(ug);

        // Start process and get task
        UUID taskId = startProcessAndReturnTask();
        UserTaskEntity task = userTaskRepository.findById(taskId).orElseThrow();

        // WO-SEC-56: candidate-group membership must NOT bypass a personal assignee —
        // assignee-task.bpmn seeds assignee="user1", so clear it here to exercise the pure
        // candidate-group path (unassigned task), which stays allowed (criterion #3 of WO-SEC-56).
        task.setAssignee(null);
        // Set candidate groups on the task
        task.setCandidateGroups("managers");
        userTaskRepository.save(task);

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + cgMemberToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- #5: Super-admin completes → 200 ---

    @Test
    void criterion5_superAdminCompletes_returns200() throws Exception {
        UUID taskId = startProcessAndReturnTask();
        unassignTask(taskId);

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }



    // --- Helpers ---

    private UUID startProcessAndReturnTask() throws Exception {
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

        // Query for the user task
        MvcResult taskResult = mockMvc.perform(get("/user-tasks?processInstanceId=" + processInstanceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
            mapper.readTree(taskResult.getResponse().getContentAsString())
                .get("data").get(0).get("id").asText());
    }

    private void unassignTask(UUID taskId) throws Exception {
        UserTaskEntity task = userTaskRepository.findById(taskId).orElseThrow();
        task.setAssignee(null);
        task.setCandidateGroups(null);
        userTaskRepository.save(task);
    }

    private UiUserEntity createAndSaveUser(String username, String role) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass" + username.charAt(username.length() - 1)));
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
