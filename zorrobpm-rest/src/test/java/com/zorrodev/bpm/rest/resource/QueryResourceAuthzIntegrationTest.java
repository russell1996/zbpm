package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-43: cross-grant isolation for the five singular QueryResource GET endpoints
 * (getServiceTask / getUserTask / getProcessInstance / getProcessInstanceActivities / getIncident).
 *
 * Full-context IT (V11): real filter chain, real DB, three principals with different grants.
 * userA has a non-full grant on processA, userB on processB only. All singular resources of
 * processA exist. userB must get 404 (existence hidden) on every resource of processA, userA
 * must get 200 on the same resources, and the SUPER_ADMIN must keep full access.
 *
 * POF (G-K): without the authz guard, userB GET /process-instances/{instanceA} → 200 + full data
 * (RED); with the guard → 404 (GREEN).
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class QueryResourceAuthzIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private ServiceTaskRepository serviceTaskRepository;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TokenRepository tokenRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String userAKey;   // ServicePrincipal: non-full grant on processA
    private String userBKey;   // ServicePrincipal: non-full grant on processB only
    private String userFullAKey; // WO-SEC-54: ServicePrincipal with isFull=true grant on processA only

    private static final String PROC_A_KEY = "sec43-procA";
    private static final String PROC_B_KEY = "sec43-procB";

    private UUID pdIdA;
    private UUID pdIdB;

    private UUID instanceA;
    private UUID userTaskA;
    private UUID serviceTaskA;
    private UUID incidentA;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");

        pdIdA = deployProcess(PROC_A_KEY);
        pdIdB = deployProcess(PROC_B_KEY);

        UUID userAId = createUser("sec43-userA-" + UUID.randomUUID(), "USER");
        addMember(userAId, PROC_A_KEY, "OWNER");
        userAKey = createApiKeyForUser(userAId);
        setGrants(userAId, PROC_A_KEY, "START");

        UUID userBId = createUser("sec43-userB-" + UUID.randomUUID(), "USER");
        addMember(userBId, PROC_B_KEY, "OWNER");
        userBKey = createApiKeyForUser(userBId);
        setGrants(userBId, PROC_B_KEY, "START");

        // WO-SEC-54: real isFull=true grant on processA only (crit #4 regression for WO-SEC-43)
        UUID userFullAId = createUser("sec54-userFullA-" + UUID.randomUUID(), "USER");
        addMember(userFullAId, PROC_A_KEY, "OWNER");
        userFullAKey = createApiKeyForUser(userFullAId);
        setGrantsFull(userFullAId, PROC_A_KEY);

        createProcessAResources();
    }

    /** Runs a real instance of processA (blocks at its user task) and seeds the service task + incident. */
    private void createProcessAResources() throws Exception {
        instanceA = startInstance(pdIdA);
        userTaskA = findUserTaskId(instanceA);

        ServiceTaskEntity st = new ServiceTaskEntity();
        st.setId(UUID.randomUUID());
        st.setProcessInstanceId(instanceA);
        st.setProcessDefinitionId(pdIdA);
        st.setBpmnElementId("serviceTask1");
        st.setCreatedAt(Instant.now());
        serviceTaskRepository.save(st);
        serviceTaskA = st.getId();

        ActivityEntity activity = new ActivityEntity();
        activity.setId(UUID.randomUUID());
        activity.setProcessInstanceId(instanceA);
        // WO-OPS-12: fk_activities__token — ссылаемся только на существующий token.
        TokenEntity seedToken = new TokenEntity();
        seedToken.setId(UUID.randomUUID());
        tokenRepository.save(seedToken);
        activity.setToken(seedToken.getId());
        activity.setBpmnElementId("sec43-seeded-activity");
        activity.setCreatedAt(Instant.now());
        activityRepository.save(activity);

        IncidentEntity incident = new IncidentEntity();
        incident.setId(UUID.randomUUID());
        incident.setActivityId(activity.getId());
        incident.setMessage("sec43 test incident");
        incident.setCreatedAt(Instant.now());
        incidentRepository.save(incident);
        incidentA = incident.getId();
    }

    // ==================== Criterion #1 (POF): foreign process instance → 404 ====================

    @Test
    void getProcessInstance_foreignInstance_returns404() throws Exception {
        // userB has a non-full grant on processB only → instanceA belongs to processA → 404
        mockMvc.perform(get("/process-instances/" + instanceA)
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isNotFound());
    }

    // ==================== Criterion #2: principal with grant on processA → 200 ====================

    @Test
    void getProcessInstance_ownGrant_returns200() throws Exception {
        mockMvc.perform(get("/process-instances/" + instanceA)
                .header("Authorization", "Bearer " + userAKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(instanceA.toString()));
    }

    // ==================== Criterion #3: the other four singular GETs — 404 foreign / 200 own ====================

    @Test
    void getUserTask_foreign_returns404() throws Exception {
        mockMvc.perform(get("/user-tasks/" + userTaskA)
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isNotFound());
    }

    @Test
    void getUserTask_ownGrant_returns200() throws Exception {
        mockMvc.perform(get("/user-tasks/" + userTaskA)
                .header("Authorization", "Bearer " + userAKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userTaskA.toString()));
    }

    @Test
    void getServiceTask_foreign_returns404() throws Exception {
        mockMvc.perform(get("/service-tasks/" + serviceTaskA)
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isNotFound());
    }

    @Test
    void getServiceTask_ownGrant_returns200() throws Exception {
        mockMvc.perform(get("/service-tasks/" + serviceTaskA)
                .header("Authorization", "Bearer " + userAKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(serviceTaskA.toString()));
    }

    @Test
    void getIncident_foreign_returns404() throws Exception {
        mockMvc.perform(get("/incidents/" + incidentA)
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isNotFound());
    }

    @Test
    void getIncident_ownGrant_returns200() throws Exception {
        mockMvc.perform(get("/incidents/" + incidentA)
                .header("Authorization", "Bearer " + userAKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(incidentA.toString()));
    }

    @Test
    void getProcessInstanceActivities_foreign_returns404() throws Exception {
        mockMvc.perform(get("/process-instances/" + instanceA + "/activities")
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isNotFound());
    }

    @Test
    void getProcessInstanceActivities_ownGrant_returns200() throws Exception {
        mockMvc.perform(get("/process-instances/" + instanceA + "/activities")
                .header("Authorization", "Bearer " + userAKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].processInstanceId").value(instanceA.toString()));
    }

    // WO-PERF-7: аддитивный paged-эндпоинт достижим по HTTP с тем же authz-гардом.
    // Наследник QueryResourceAuthzPgIT гоняет их и на реальном PG.

    @Test
    void getProcessInstanceActivitiesPaged_foreign_returns404() throws Exception {
        mockMvc.perform(get("/process-instances/" + instanceA + "/activities/paged")
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isNotFound());
    }

    @Test
    void getProcessInstanceActivitiesPaged_ownGrant_returns200_withPagedBody() throws Exception {
        // Рантайм пишет свои activity-строки помимо сида — точное total не фиксируем,
        // проверяем структуру страницы и принадлежность инстансу.
        mockMvc.perform(get("/process-instances/" + instanceA + "/activities/paged")
                .param("pageIndex", "0")
                .param("pageSize", "10")
                .header("Authorization", "Bearer " + userAKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").exists())
            .andExpect(jsonPath("$.pageSize").value(10))
            .andExpect(jsonPath("$.data[0].processInstanceId").value(instanceA.toString()));
    }

    // ==================== Criterion #4: SUPER_ADMIN keeps full access (regression) ====================

    @Test
    void admin_canReadForeignProcessInstance() throws Exception {
        mockMvc.perform(get("/process-instances/" + instanceA)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    @Test
    void admin_canReadForeignUserTask() throws Exception {
        mockMvc.perform(get("/user-tasks/" + userTaskA)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    @Test
    void admin_canReadForeignServiceTask() throws Exception {
        mockMvc.perform(get("/service-tasks/" + serviceTaskA)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    @Test
    void admin_canReadForeignIncident() throws Exception {
        mockMvc.perform(get("/incidents/" + incidentA)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    @Test
    void admin_canReadForeignActivities() throws Exception {
        mockMvc.perform(get("/process-instances/" + instanceA + "/activities")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    // ==================== Helpers ====================

    private String loginAndGetToken(String username, String password) throws Exception {
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

    private UUID createUser(String username, String role) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole(role);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    private UUID deployProcess(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/sec43-process.bpmn")));
        bpmn = bpmn.replace("id=\"sec43-process\"", "id=\"" + key + "\"")
                   .replace("name=\"SEC43 Process\"", "name=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID startInstance(UUID pdId) throws Exception {
        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(pdId);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(startDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID findUserTaskId(UUID instanceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("processInstanceId", instanceId.toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        return UUID.fromString(data.get(0).get("id").asText());
    }

    private void addMember(UUID userId, String processKey, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private String createApiKeyForUser(UUID userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/users/" + userId + "/api-key")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void setGrants(UUID userId, String processKey, String permissions) throws Exception {
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"permissions\":\"" + permissions + "\"}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    /** WO-SEC-54: isFull=true grant (no permissions field). */
    private void setGrantsFull(UUID userId, String processKey) throws Exception {
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"full\":true}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ==================== WO-SEC-54 crit #4: full grant still sees OWN direct-GETs (WO-SEC-43 regression) ====================

    @Test
    void fullGrantOnA_seesOwnProcessInstance_returns200() throws Exception {
        mockMvc.perform(get("/process-instances/" + instanceA)
                        .header("Authorization", "Bearer " + userFullAKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(instanceA.toString()));
    }

    @Test
    void fullGrantOnA_seesOwnUserTask_returns200() throws Exception {
        mockMvc.perform(get("/user-tasks/" + userTaskA)
                        .header("Authorization", "Bearer " + userFullAKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userTaskA.toString()));
    }
}
