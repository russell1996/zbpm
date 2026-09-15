package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-INT-6: reassign (assign) user-task hardening.
 *  - assign with empty body → 400 (was: NPE → 500)
 *  - assign by a non-owner process member → 403 (was: 200, candidate/member level)
 *  - assign by process OWNER / super-admin → 200, assignee set
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssignUserTaskIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String ownerToken;        // ProcessMember role OWNER
    private String plainMemberToken;  // ProcessMember role VIEWER (not owner/designer)
    private UUID processDefinitionId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        UiUserEntity owner = createAndSaveUser("assignownerr");
        UiUserEntity plain = createAndSaveUser("assignplainr");
        ownerToken = login("assignownerr", "passr");
        plainMemberToken = login("assignplainr", "passr");

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
        ProcessEntity process = processRepository.findByDefinitionKey(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText()).orElseThrow();

        addMember(process.getId(), owner.getId(), "OWNER");
        addMember(process.getId(), plain.getId(), "VIEWER");
    }

    // --- assign with empty body → 400 (POF: was NPE → 500) ---
    @Test
    void assign_emptyBody_returns400() throws Exception {
        UUID taskId = startTask();
        mockMvc.perform(post("/user-tasks/" + taskId + "/assign")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content("{}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assign_blankAssignee_returns400() throws Exception {
        UUID taskId = startTask();
        mockMvc.perform(post("/user-tasks/" + taskId + "/assign")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content("{\"assignee\":\"  \"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // --- assign by non-owner member → 403 (POF: was 200) ---
    @Test
    void assign_nonOwnerMember_returns403() throws Exception {
        UUID taskId = startTask();
        mockMvc.perform(post("/user-tasks/" + taskId + "/assign")
                        .header("Authorization", "Bearer " + plainMemberToken)
                        .content("{\"assignee\":\"petrov\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getAssignee()).isNotEqualTo("petrov");
    }

    // --- assign by OWNER → 200, assignee set ---
    @Test
    void assign_owner_returns200_andAssigneeSet() throws Exception {
        UUID taskId = startTask();
        mockMvc.perform(post("/user-tasks/" + taskId + "/assign")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content("{\"assignee\":\"petrov\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getAssignee()).isEqualTo("petrov");
    }

    // --- assign by super-admin → 200 ---
    @Test
    void assign_superAdmin_returns200() throws Exception {
        UUID taskId = startTask();
        mockMvc.perform(post("/user-tasks/" + taskId + "/assign")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"assignee\":\"ivanov\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- Helpers ---

    private UUID startTask() throws Exception {
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
        MvcResult taskResult = mockMvc.perform(get("/user-tasks?processInstanceId=" + processInstanceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
            mapper.readTree(taskResult.getResponse().getContentAsString())
                .get("data").get(0).get("id").asText());
    }

    private void addMember(UUID processId, UUID userId, String role) {
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(processId);
        pm.setUserId(userId);
        pm.setRole(role);
        pm.setAddedBy(userId);
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);
    }

    private UiUserEntity createAndSaveUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass" + username.charAt(username.length() - 1)));
        user.setFullName(username);
        user.setRole("USER");
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
