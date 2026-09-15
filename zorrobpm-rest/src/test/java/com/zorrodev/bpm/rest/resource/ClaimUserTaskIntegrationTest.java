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
 * WO-INT-5: claim / unclaim / reassign user-task authorization and lifecycle.
 *
 * Authz (canClaimUserTask, default DENY):
 *  - candidate-group member claims a group-restricted task → 200 (even if not a process member)
 *  - process member who is NOT a candidate claims a group-restricted task → 403  (POF: candidate check)
 *  - non-member claims a no-candidate task → 403                                 (POF: cross-tenant)
 *  - process member claims a no-candidate task → 200
 * Lifecycle:
 *  - claim already-assigned → 409; second concurrent-style claim → 409 (atomic guard)
 *  - unclaim returns task to the pool (assignee = null)
 *  - X-On-Behalf-Of sets that assignee
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaimUserTaskIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserGroupRepository userGroupRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String memberToken;      // process member, NOT in group "managers"
    private String candidateToken;   // in group "managers", NOT a process member
    private String nonMemberToken;   // neither
    private UUID processDefinitionId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        UiUserEntity member = createAndSaveUser("claimmemberr");
        UiUserEntity candidate = createAndSaveUser("claimcandr");
        UiUserEntity nonMember = createAndSaveUser("claimouterr");
        memberToken = login("claimmemberr", "passr");
        candidateToken = login("claimcandr", "passr");
        nonMemberToken = login("claimouterr", "passr");

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

        // member → process member (no group); candidate → group "managers" (no membership)
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(process.getId());
        pm.setUserId(member.getId());
        pm.setRole("OWNER");
        pm.setAddedBy(member.getId());
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);

        UserGroupEntity ug = new UserGroupEntity();
        ug.setUserId(candidate.getId());
        ug.setGroupName("managers");
        userGroupRepository.save(ug);
    }

    // --- authz: candidate group member claims group-restricted task → 200 ---
    @Test
    void claim_candidateGroupMember_returns200_andAssigneeSet() throws Exception {
        UUID taskId = startTask("managers");
        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isOk());
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getAssignee()).isNotNull();
    }

    // --- POF (candidate check): process member who is NOT a candidate → 403 ---
    // Without the candidate check (old code returned true for any member) this was 200.
    @Test
    void claim_processMemberNotCandidate_returns403() throws Exception {
        UUID taskId = startTask("managers");
        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getAssignee()).isNull();
    }

    // --- POF (cross-tenant): non-member claims a no-candidate task → 403 ---
    @Test
    void claim_nonMember_noCandidateGroups_returns403() throws Exception {
        UUID taskId = startTask(null);
        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden());
    }

    // --- process member claims a no-candidate task (open pool) → 200 ---
    @Test
    void claim_member_noCandidateGroups_returns200() throws Exception {
        UUID taskId = startTask(null);
        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getAssignee()).isNotNull();
    }

    // --- claim already-assigned → 409 ---
    @Test
    void claim_alreadyAssigned_returns409() throws Exception {
        UUID taskId = startTask(null);
        setAssignee(taskId, "someoneElse");
        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isConflict());
    }

    // --- second claim after a successful one → 409 (atomic guard, not overwrite) ---
    @Test
    void claim_secondClaim_returns409_firstAssigneeKept() throws Exception {
        UUID taskId = startTask(null);
        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        String firstAssignee = userTaskRepository.findById(taskId).orElseThrow().getAssignee();

        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
        // First claimant is not overwritten.
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getAssignee()).isEqualTo(firstAssignee);
    }

    // --- unclaim returns task to the pool ---
    @Test
    void unclaim_returnsTaskToPool() throws Exception {
        UUID taskId = startTask(null);
        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/user-tasks/" + taskId + "/unclaim")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getAssignee()).isNull();
    }

    // --- X-On-Behalf-Of sets that assignee (WO-INT-4 #11: only system keys; the claimed
    // name must exist and be a candidate; assignee is stored as a clean username) ---
    @Test
    void claim_withOnBehalfOf_setsThatAssignee() throws Exception {
        UUID taskId = startTask("managers");
        // The claimed name must exist and be a candidate for the task
        UUID petrovId = createUserViaHttp("petrov", "HUMAN");
        UserGroupEntity petrovGroup = new UserGroupEntity();
        petrovGroup.setUserId(petrovId);
        petrovGroup.setGroupName("managers");
        userGroupRepository.save(petrovGroup);

        // System key — the only principal allowed to send X-On-Behalf-Of
        UUID systemId = createUserViaHttp("claimsys1", "SYSTEM");
        String systemKey = createApiKeyForUser(systemId);
        addMemberToProcess(systemId, "OWNER");
        setGrantsFull(systemId, "assignee-process");

        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", "petrov"))
                .andExpect(status().isOk());
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getAssignee()).isEqualTo("petrov");
    }

    // --- super-admin claims → 200 ---
    @Test
    void claim_superAdmin_returns200() throws Exception {
        UUID taskId = startTask("managers");
        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // --- Helpers ---

    /** Starts a process instance and prepares an unassigned task with the given candidate groups. */
    private UUID startTask(String candidateGroups) throws Exception {
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
        UUID taskId = UUID.fromString(
            mapper.readTree(taskResult.getResponse().getContentAsString())
                .get("data").get(0).get("id").asText());
        // bpmn seeds assignee="user1" → clear it so the task is claimable; set candidate groups.
        UserTaskEntity task = userTaskRepository.findById(taskId).orElseThrow();
        task.setAssignee(null);
        task.setCandidateGroups(candidateGroups);
        userTaskRepository.save(task);
        return taskId;
    }

    private void setAssignee(UUID taskId, String assignee) {
        UserTaskEntity task = userTaskRepository.findById(taskId).orElseThrow();
        task.setAssignee(assignee);
        userTaskRepository.save(task);
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

    private UUID createUserViaHttp(String username, String userType) throws Exception {
        String body = "{\"username\":\"" + username
            + "\",\"fullName\":\"" + username
            + "\",\"email\":\"" + username + "@zorrodev.test"
            + "\",\"role\":\"SUPER_ADMIN\""
            + ",\"active\":true"
            + ",\"password\":\"MyStr0ng!P@ssw0rd\""
            + ",\"userType\":\"" + userType + "\"}";
        MvcResult result = mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        int status = result.getResponse().getStatus();
        if (status == 409) {
            return userRepository.findByUsername(username).orElseThrow().getId();
        }
        if (status != 200 && status != 201) {
            throw new IllegalStateException("createUserViaHttp(" + username + ") failed: " + status
                + " " + result.getResponse().getContentAsString());
        }
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String createApiKeyForUser(UUID userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/users/" + userId + "/api-key")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void addMemberToProcess(UUID userId, String role) {
        ProcessEntity process = processRepository.findByDefinitionKey("assignee-process").orElseThrow();
        ProcessMemberId id = new ProcessMemberId(process.getId(), userId);
        if (processMemberRepository.existsById(id)) {
            return;
        }
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(process.getId());
        pm.setUserId(userId);
        pm.setRole(role);
        pm.setAddedBy(userId);
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);
    }

    private void setGrantsFull(UUID userId, String processKey) throws Exception {
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"full\":true}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
