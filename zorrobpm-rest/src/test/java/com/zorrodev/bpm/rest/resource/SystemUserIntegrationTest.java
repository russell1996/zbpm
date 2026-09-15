package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AssignUserTaskDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.entity.UserGroupEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-INT-4: system user + its key.
 *
 * Criteria (rewritten WO — the type is a marker, not a special right):
 *  #1  every account has a type; default is HUMAN, existing accounts unchanged
 *  #3  a system account never appears in candidates (member search filter, not an engine guard)
 *  #4  authorization does NOT depend on the account type: same grants → same answers
 *  #5  two keys of one account work simultaneously; revoking one does not break the second
 *  #6  expired and revoked keys give 401
 *  #7  lastUsedAt is updated on use
 *  #9  X-On-Behalf-Of with a foreign user -> 403; with the real assignee/candidate -> 200
 *  #10 the on-behalf rule holds for ANY key, regardless of the account type
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemUserIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private UserGroupRepository userGroupRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private UUID systemUserId;
    private UUID humanOwnerId;
    private UUID managerId;
    private UUID processDefinitionId;
    private String processKey;
    private UUID candidateProcessDefinitionId;
    private String candidateProcessKey;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        // Human users needed by the process definitions / candidates
        humanOwnerId = createUser("int4owner", "HUMAN", "MyStr0ng!P@ssw0rd");
        managerId = createUser("int4manager", "HUMAN", "MyStr0ng!P@ssw0rd");
        createUser("sysuser1", "HUMAN", "MyStr0ng!P@ssw0rd"); // assignee-task-sys.bpmn assigns sysuser1

        UserGroupEntity group = new UserGroupEntity();
        group.setUserId(managerId);
        group.setGroupName("managers");
        userGroupRepository.save(group);

        // System account
        systemUserId = createUser("int4sys", "SYSTEM", "ignore-me");

        // user1 is referenced by assignee-task.bpmn (assignee=user1) — must exist
        // REMOVED: duplicate createUser("sysuser1", "HUMAN", "passr") — was causing fixture collision

        // Deploy assignee-task-sys.bpmn (assignee=sysuser1) — plain user task
        processDefinitionId = deploy("assignee-task-sys.bpmn");
        processKey = "assignee-process-sys";

        // Deploy candidate-group-task.bpmn (candidateGroups=managers)
        candidateProcessDefinitionId = deploy("candidate-group-task.bpmn");
        candidateProcessKey = "candidate-group-process";
    }

    // --- #1: password login for a system account is impossible ---

    @Test
    void criterion1_systemAccount_loginByPassword_returns401() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("int4sys");
        dto.setPassword("ignore-me");
        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // --- #2: forcePasswordChange does not apply to a system account ---

    @Test
    void criterion2_systemAccount_forcePasswordChangeFalse_evenAfterUpdateWithPassword() throws Exception {
        // Created without forcePasswordChange
        mockMvc.perform(get("/users/" + systemUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("SYSTEM"))
                .andExpect(jsonPath("$.forcePasswordChange").value(false));

        // Even an update that would set a password for a human must NOT touch a system
        // account — the password is ignored entirely (WO-INT-4: no password at all).
        mockMvc.perform(put("/users/" + systemUserId)
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"password\":\"MyStr0ng!P@ssw0rd2\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(get("/users/" + systemUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forcePasswordChange").value(false));

        // And the password still does not work
        LoginDTO dto = new LoginDTO();
        dto.setUsername("int4sys");
        dto.setPassword("MyStr0ng!P@ssw0rd2");
        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // --- #1b: the default account type is HUMAN when not specified (criterion 1) ---

    @Test
    void criterion1b_accountTypeDefaultsToHuman_whenNotSpecified() throws Exception {
        String body = "{\"username\":\"int4default\",\"fullName\":\"Default Human\","
            + "\"email\":\"int4default@zorrodev.test\",\"role\":\"USER\",\"active\":true,"
            + "\"password\":\"MyStr0ng!P@ssw0rd\"}";
        MvcResult created = mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        assertThat(userRepository.findById(id).orElseThrow().getUserType()).isEqualTo("HUMAN");
    }

    // --- #4 (WO criterion): authorization does NOT depend on the account type ---
    // The same grants must give the same answers to a human and to a system. If someone
    // later adds `if (isSystem)` to a guard, this pair must go red (WO proof-of-failure #1).

    @Test
    void criterion4_authzIndependentOfType_humanAndSystemSameGrantsSameAnswers() throws Exception {
        addMember(processKey, humanOwnerId, "OWNER");
        String humanKey = createApiKeyForUser(humanOwnerId);
        setGrantsFull(humanOwnerId, processKey);

        addMember(processKey, systemUserId, "OWNER");
        String systemKey = createApiKeyForUser(systemUserId);
        setGrantsFull(systemUserId, processKey);

        // Same data read
        mockMvc.perform(get("/user-tasks").header("Authorization", "Bearer " + humanKey))
                .andExpect(status().isOk());
        mockMvc.perform(get("/user-tasks").header("Authorization", "Bearer " + systemKey))
                .andExpect(status().isOk());

        // Same runtime write: complete the assignee task (no X-On-Behalf-Of)
        UUID taskId = startTaskAndGetId();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + humanKey)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        UUID taskId2 = startTaskAndGetId();
        mockMvc.perform(post("/user-tasks/" + taskId2 + "/complete")
                        .header("Authorization", "Bearer " + systemKey)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- #3: system account is never offered as a candidate ---
    // The member-search filter hides SYSTEM accounts (WO-INT-4 criterion 3). Assignment
    // itself is NOT type-guarded — the type is a marker, not a special right.

    @Test
    void criterion3_systemNotInCandidates() throws Exception {
        // Not offered as a member candidate in the add-member dialog
        MvcResult candidates = mockMvc.perform(get("/processes/" + processKey + "/members/candidates")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("q", "int4sys"))
                .andExpect(status().isOk())
                .andReturn();
        String body = candidates.getResponse().getContentAsString();
        assertThat(body).doesNotContain("int4sys");

        // Assignment is NOT type-guarded (criterion 3 is a candidates filter, not an
        // engine ban): assigning the system account to a user task succeeds.
        UUID taskId = startTaskAndGetId();
        AssignUserTaskDTO assignDto = new AssignUserTaskDTO();
        assignDto.setAssignee("int4sys");
        mockMvc.perform(post("/user-tasks/" + taskId + "/assign")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(assignDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- #5: two keys of one account work; revoking one does not break the other ---

    @Test
    void criterion5_twoKeysOfOneAccount_workTogether_revokeOneKeepsOther() throws Exception {
        addMember(processKey, systemUserId, "OWNER");

        String key1 = createApiKeyForUser(systemUserId);          // POST /api-key
        MvcResult second = mockMvc.perform(post("/admin/users/" + systemUserId + "/api-keys")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        String key2 = mapper.readTree(second.getResponse().getContentAsString()).get("key").asText();
        String key2Id = mapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        setGrantsFull(systemUserId, processKey);

        // Both keys are valid (data endpoint with full grant)
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + key1))
                .andExpect(status().isOk());
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + key2))
                .andExpect(status().isOk());

        // Revoke key2 by id -> key2 dies, key1 keeps working
        mockMvc.perform(post("/admin/users/" + systemUserId + "/api-keys/" + key2Id + "/revoke")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + key1))
                .andExpect(status().isOk());
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + key2))
                .andExpect(status().isUnauthorized());

        // Human accounts still have one-key-per-user: a second key -> 409
        UUID humanId = createUser("int4human1", "HUMAN", "MyStr0ng!P@ssw0rd");
        createApiKeyForUser(humanId);
        mockMvc.perform(post("/admin/users/" + humanId + "/api-keys")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    // --- #6: expired and revoked keys give 401 ---

    @Test
    void criterion6_expiredAndRevokedKeys_returns401() throws Exception {
        addMember(processKey, systemUserId, "OWNER");
        setGrantsFull(systemUserId, processKey);

        // Revoked: issue a key then revoke it by id
        MvcResult issued = mockMvc.perform(post("/admin/users/" + systemUserId + "/api-key")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        String revokedToken = mapper.readTree(issued.getResponse().getContentAsString()).get("key").asText();
        String revokedId = mapper.readTree(issued.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/admin/users/" + systemUserId + "/api-keys/" + revokedId + "/revoke")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + revokedToken))
                .andExpect(status().isUnauthorized());

        // Expired: insert a key with expiresAt in the past directly (no endpoint sets expiry)
        String expiredToken = "zbpm_sk_expired_" + UUID.randomUUID();
        ApiKeyEntity expired = new ApiKeyEntity();
        expired.setId(UUID.randomUUID());
        expired.setOwnerUserId(systemUserId);
        expired.setKeyHash(KeyHasher.sha256(expiredToken));
        expired.setPrefix(expiredToken.substring(0, Math.min(16, expiredToken.length())));
        expired.setCreatedAt(Instant.now());
        expired.setExpiresAt(Instant.now().minusSeconds(3600));
        apiKeyRepository.save(expired);

        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    // --- #7: lastUsedAt is updated on use ---

    @Test
    void criterion7_lastUsedAt_updatedOnUse() throws Exception {
        addMember(processKey, systemUserId, "OWNER");
        String key = createApiKeyForUser(systemUserId);
        setGrantsFull(systemUserId, processKey);

        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + key))
                .andExpect(status().isOk());

        MvcResult list = mockMvc.perform(get("/admin/users/" + systemUserId + "/api-keys")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        // The key just used must carry lastUsedAt; other keys of the account (created by
        // earlier criteria) may legitimately have null — match by prefix, not by position.
        String prefix = key.substring(0, Math.min(16, key.length()));
        JsonNode arr = mapper.readTree(list.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode n : arr) {
            if (prefix.equals(n.path("prefix").asText())) {
                assertThat(n.hasNonNull("lastUsedAt")).isTrue();
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    // --- #9: X-On-Behalf-Of must match the task (assignee/candidate/member) ---

    // WO-SEC-64 HOLD: чужой "stranger" не существует в БД — fail-closed 404
    // от existence-гейта (а не 403 от task-сверки). Разделение доказывает, что
    // гейт стоит до работы; 403-кейс для существующего-но-чужого — ниже.

    @Test
    void criterion9_completeWithOnBehalfOf_realAssignee200_foreign403() throws Exception {
        addMember(processKey, systemUserId, "OWNER");
        String systemKey = createApiKeyForUser(systemUserId);
        setGrantsFull(systemUserId, processKey);

        UUID taskId = startTaskAndGetId(); // assignee=sysuser1 (seeded by BPMN)
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getAssignee()).isEqualTo("sysuser1");

        // A foreign EXISTING name is NOT the assignee -> 403 (task-сверка).
        // (Несуществующий — 404 от existence-гейта, отдельный слой; здесь
        //  нужен существующий юзер, чтобы достичь именно task-сверки.)
        createUser("stranger", "HUMAN", "MyStr0ng!P@ssw0rd");
        CompleteTaskDTO foreign = new CompleteTaskDTO();
        foreign.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", "stranger")
                        .content(mapper.writeValueAsString(foreign))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // The real assignee -> 200, attribution is verifiable
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", "sysuser1")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- #9b: claim via X-On-Behalf-Of — candidate only ---

    @Test
    void criterion9b_claimWithOnBehalfOf_candidate200_foreign403() throws Exception {
        addMember(candidateProcessKey, systemUserId, "OWNER");
        String systemKey = createApiKeyForUser(systemUserId);
        setGrantsFull(systemUserId, candidateProcessKey);

        // int4manager is in group "managers" -> candidate -> claim succeeds, assignee = clean name
        UUID taskId = startClaimableTask();
        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", "int4manager"))
                .andExpect(status().isOk());
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getAssignee()).isEqualTo("int4manager");

        // An EXISTING name that is neither candidate nor assignee nor member
        // -> 403 от task-сверки, task untouched. (WO-SEC-64 HOLD: несуществующий
        // даёт 404 от existence-гейта — отдельный слой, здесь нужен живой юзер.)
        UUID taskId2 = startClaimableTask();
        mockMvc.perform(post("/user-tasks/" + taskId2 + "/claim")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", "stranger"))
                .andExpect(status().isForbidden());
        assertThat(userTaskRepository.findById(taskId2).orElseThrow().getAssignee()).isNull();
    }

    // --- #10: the on-behalf rule holds for ANY key, regardless of the account type ---
    // A human-owned key is still a key: X-On-Behalf-Of is checked against the assignee /
    // candidate, not rejected by account type (WO-INT-4 criterion 10).

    @Test
    void criterion10_humanKey_withOnBehalfOf_checkedLikeAnyKey() throws Exception {
        // A dedicated human owner: criterion 4 already issues a key for humanOwnerId
        // and a human account has exactly one key — reusing the same id would 409.
        UUID humanKeyOwnerId = createUser("int4human2", "HUMAN", "MyStr0ng!P@ssw0rd");
        addMember(processKey, humanKeyOwnerId, "OWNER");
        String humanKey = createApiKeyForUser(humanKeyOwnerId);
        setGrantsFull(humanKeyOwnerId, processKey);

        // The real assignee -> 200 (same rule as a system key)
        UUID taskId = startTaskAndGetId(); // assignee=sysuser1
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + humanKey)
                        .header("X-On-Behalf-Of", "sysuser1")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // A foreign EXISTING name is NOT the assignee -> 403 (task-сверка).
        UUID taskId2 = startTaskAndGetId();
        mockMvc.perform(post("/user-tasks/" + taskId2 + "/complete")
                        .header("Authorization", "Bearer " + humanKey)
                        .header("X-On-Behalf-Of", "stranger")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- Helpers ---

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

    /** Creates a user through the real HTTP path (UserResource -> UiUserServiceImpl.create). */
    private UUID createUser(String username, String userType, String password) throws Exception {
        String body = "{\"username\":\"" + username
            + "\",\"fullName\":\"" + username
            + "\",\"email\":\"" + username + "@zorrodev.test"
            + "\",\"role\":\"SUPER_ADMIN\""
            + ",\"active\":true"
            + ",\"password\":\"" + password
            + "\",\"userType\":\"" + userType + "\"}";
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
            throw new IllegalStateException("createUser(" + username + ") failed: " + status
                + " " + result.getResponse().getContentAsString());
        }
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID deploy(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());
    }

    /** Idempotent membership insert (a user may be a member of only one role per process). */
    private void addMember(String processKey, UUID userId, String role) {
        ProcessEntity process = processRepository.findByDefinitionKey(processKey).orElseThrow();
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

    private String createApiKeyForUser(UUID userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/users/" + userId + "/api-key")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void setGrantsFull(UUID userId, String processKey) throws Exception {
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"full\":true}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    /** Starts assignee-process-sys and returns its task (assignee=sysuser1 seeded by BPMN). */
    private UUID startTaskAndGetId() throws Exception {
        return startProcessAndGetTaskId(processDefinitionId);
    }

    /** Starts candidate-group-process, clears the assignee, keeps candidateGroups=managers. */
    private UUID startClaimableTask() throws Exception {
        UUID taskId = startProcessAndGetTaskId(candidateProcessDefinitionId);
        UserTaskEntity task = userTaskRepository.findById(taskId).orElseThrow();
        task.setAssignee(null);
        task.setCandidateGroups("managers");
        userTaskRepository.save(task);
        return taskId;
    }

    private UUID startProcessAndGetTaskId(UUID definitionId) throws Exception {
        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(definitionId);
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
        return UUID.fromString(mapper.readTree(taskResult.getResponse().getContentAsString())
            .get("data").get(0).get("id").asText());
    }
}