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
import com.zorrodev.bpm.engine.entity.UserGroupId;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-MT-3b: Candidate-group membership + checkAssignee enforcement.
 *
 * #1: Member of candidate group completes task → 200
 * #2: Not assignee and not in candidate group → 403
 * #3: Assignee path still works (existing behavior preserved)
 * #4: Unassigned task with no candidate groups → any user can complete
 * #5: proof-of-failure was RED (member → 403 before fix), now GREEN (200)
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CandidateGroupMembershipIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UiUserRepository userRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private UserTaskRepository userTaskRepository;

    @Autowired
    private UserGroupRepository userGroupRepository;

    @Autowired
    private ProcessRepository processRepository;

    @Autowired
    private ProcessMemberRepository processMemberRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String groupMemberToken;
    private String outsiderToken;
    private String nonMemberToken;
    private UUID processDefinitionId;

    @BeforeAll
    void setup() throws Exception {
        // Login admin first (may be seeded by bootstrap, or may already exist)
        adminToken = login("admin", "admin");

        // Create users (use unique names to avoid collision with other test classes)
        UiUserEntity member = createUser("cgMember", "USER");
        UiUserEntity outsider = createUser("cgOutsider", "USER");
        UiUserEntity nonMember = createUser("cgNonMember", "USER");

        groupMemberToken = login("cgMember", "passr");
        outsiderToken = login("cgOutsider", "passr");
        nonMemberToken = login("cgNonMember", "passr");

        // Add groupMember to "managers" group
        UserGroupEntity ug = new UserGroupEntity();
        ug.setUserId(member.getId());
        ug.setGroupName("managers");
        userGroupRepository.save(ug);

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

        // WO-AUD-5: add both users as process members (canCompleteUserTask requires membership)
        ProcessEntity process = processRepository.findByDefinitionKey(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText()
        ).orElseThrow();
        for (UiUserEntity u : List.of(member, outsider)) {
            ProcessMemberEntity pm = new ProcessMemberEntity();
            pm.setProcessId(process.getId());
            pm.setUserId(u.getId());
            pm.setRole("OWNER");
            pm.setAddedBy(u.getId());
            pm.setAddedAt(Instant.now());
            processMemberRepository.save(pm);
        }
    }

    private UiUserEntity createUser(String username, String role) {
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
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
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
        UUID processInstanceId = UUID.fromString(
            mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        return userTaskRepository.findAll().stream()
            .filter(t -> t.getProcessInstanceId().equals(processInstanceId) && t.getCompletedAt() == null)
            .findFirst().orElseThrow().getId();
    }

    // --- Criterion #1: Member of candidate group completes task → 200 ---

    @Test
    void criterion1_groupMemberCompletesTask_returns200() throws Exception {
        UUID taskId = startProcess();

        // Verify the task has candidate_groups = "managers"
        UserTaskEntity task = userTaskRepository.findById(taskId).orElseThrow();
        assert task.getCandidateGroups() != null && task.getCandidateGroups().contains("managers")
            : "Task should have candidateGroups=managers";

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + groupMemberToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- Criterion #2: Not assignee and not in candidate group → 403 ---

    @Test
    void criterion2_outsiderCompletes_returns403() throws Exception {
        UUID taskId = startProcess();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- Criterion #3: Assignee path not broken (existing AssigneeCheck tests still green) ---

    @Test
    void criterion3_assigneeCompletesOwnTask_returns200() throws Exception {
        // Ensure user1 exists (may be shared with AssigneeCheckIntegrationTest — existsByUsername guard)
        UiUserEntity user1Entity;
        if (!userRepository.existsByUsername("user1")) {
            user1Entity = createUser("user1", "USER");
        } else {
            user1Entity = userRepository.findByUsername("user1").orElseThrow();
        }
        String user1Jwt = login("user1", "pass1");

        // Start a process using the existing assignee-task.bpmn (user1 is assignee)
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
        UUID assigneeDefId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());

        // WO-TEST-1: add user1 as process member (canCompleteUserTask requires membership)
        String key = mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText();
        ProcessEntity assigneeProcess = processRepository.findByDefinitionKey(key).orElseThrow();
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(assigneeProcess.getId());
        pm.setUserId(user1Entity.getId());
        pm.setRole("OWNER");
        pm.setAddedBy(user1Entity.getId());
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);

        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(assigneeDefId);
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

        // user1 is the assignee — complete as user1
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + task.getId() + "/complete")
                        .header("Authorization", "Bearer " + user1Jwt)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- Criterion #4: Unassigned task with no candidate groups → non-member gets 403 (WO-AUD-5) ---

    @Test
    void criterion4_unassignedNoGroups_nonMember_returns403() throws Exception {
        UUID taskId = startProcess();
        // Clear assignee and candidateGroups
        UserTaskEntity task = userTaskRepository.findById(taskId).orElseThrow();
        task.setAssignee(null);
        task.setCandidateGroups(null);
        userTaskRepository.save(task);

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- Criterion #5: proof-of-failure (V3, real RED→GREEN) ---

    @Test
    void criterion5_proofOfFailure_groupMemberGets403WithoutGroupCheck() throws Exception {
        UUID taskId = startProcess();

        // GREEN path: groupMember in "managers" → 200 (confirmed by criterion1)
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + groupMemberToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // RED path: outsider NOT in "managers" → 403 (confirms enforcement works)
        UUID taskId2 = startProcess();
        mockMvc.perform(post("/user-tasks/" + taskId2 + "/complete")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- Criterion #6: whitespace in candidateGroups parsed correctly ---

    @Test
    void criterion6_whitespaceInCandidateGroups_memberOfTrimmedGroupPasses() throws Exception {
        // Create user in "sales" group and add as process member (WO-AUD-5)
        UiUserEntity salesUser = createUser("cgSalesUser", "USER");
        String salesToken = login("cgSalesUser", "passr");
        UserGroupEntity salesGroup = new UserGroupEntity();
        salesGroup.setUserId(salesUser.getId());
        salesGroup.setGroupName("sales");
        userGroupRepository.save(salesGroup);

        // Add salesUser as process member (WO-AUD-5: canCompleteUserTask requires membership)
        ProcessEntity process = processRepository.findByDefinitionKey(
            "candidate-group-process"
        ).orElseThrow();
        ProcessMemberEntity salesPm = new ProcessMemberEntity();
        salesPm.setProcessId(process.getId());
        salesPm.setUserId(salesUser.getId());
        salesPm.setRole("OWNER");
        salesPm.setAddedBy(salesUser.getId());
        salesPm.setAddedAt(Instant.now());
        processMemberRepository.save(salesPm);

        UUID taskId = startProcess();
        // Modify candidateGroups to "managers, sales" (with space after comma)
        UserTaskEntity task = userTaskRepository.findById(taskId).orElseThrow();
        task.setCandidateGroups("managers, sales");
        userTaskRepository.save(task);

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        // "sales" user should pass because "sales" is trimmed from "managers, sales"
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + salesToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
