package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-5: Assignee check on completeUserTask.
 *  #1: assignee completes own task → 200
 *  #2: other user completes → 403 FORBIDDEN
 *  #3: admin completes any → 200
 *  #4: unassigned task → 200
 *  #5: proof-of-failure was RED (200 for #2), now GREEN (403)
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssigneeCheckIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UiUserRepository userRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private UserTaskRepository userTaskRepository;

    @Autowired
    private ProcessRepository processRepository;

    @Autowired
    private ProcessMemberRepository processMemberRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String user1Token;
    private String user2Token;
    private UUID processDefinitionId;

    @BeforeAll
    void setup() throws Exception {
        // WO-TEST-1: existsByUsername guard — other test classes may have created these
        if (!userRepository.existsByUsername("user1")) {
            createUser("user1", "USER");
        }
        if (!userRepository.existsByUsername("user2")) {
            createUser("user2", "USER");
        }

        adminToken = login("admin", "admin");
        user1Token = login("user1", "pass1");
        user2Token = login("user2", "pass2");

        // Deploy assignee-task.bpmn
        String bpmn = Files.readString(Paths.get("src/test/files/assignee-task.bpmn"), StandardCharsets.UTF_8);
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

        // WO-AUD-5: add both users as process members (canCompleteUserTask requires membership)
        ProcessEntity process = processRepository.findByDefinitionKey(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText()
        ).orElseThrow();
        for (String username : List.of("user1", "user2")) {
            UiUserEntity u = userRepository.findByUsername(username).orElseThrow();
            ProcessMemberEntity pm = new ProcessMemberEntity();
            pm.setProcessId(process.getId());
            pm.setUserId(u.getId());
            pm.setRole("OWNER");
            pm.setAddedBy(u.getId());
            pm.setAddedAt(Instant.now());
            processMemberRepository.save(pm);
        }
    }

    private void createUser(String username, String role) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass" + username.charAt(username.length() - 1)));
        user.setFullName(username);
        user.setRole(role);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
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

    private UUID startProcess() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        // Find the user task created for this process instance
        UUID processInstanceId = UUID.fromString(
            mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        return userTaskRepository.findAll().stream()
            .filter(t -> t.getProcessInstanceId().equals(processInstanceId) && t.getCompletedAt() == null)
            .findFirst().orElseThrow().getId();
    }

    // --- Criterion #1: assignee completes own task → 200 ---

    @Test
    void criterion1_assigneeCompletesOwnTask_returns200() throws Exception {
        UUID taskId = startProcess();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + user1Token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- Criterion #2: other user completes → 403 ---

    @Test
    void criterion2_otherUserCompletes_returns403() throws Exception {
        UUID taskId = startProcess();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + user2Token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- Criterion #3: admin completes any → 200 ---

    @Test
    void criterion3_adminCompletesAny_returns200() throws Exception {
        UUID taskId = startProcess();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- Criterion #4: unassigned task → 200 ---

    @Test
    void criterion4_unassignedTask_anyUserCompletes_returns200() throws Exception {
        UUID taskId = startProcess();
        // Reassign to null (unassigned)
        UserTaskEntity task = userTaskRepository.findById(taskId).orElseThrow();
        task.setAssignee(null);
        userTaskRepository.save(task);

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + user2Token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
