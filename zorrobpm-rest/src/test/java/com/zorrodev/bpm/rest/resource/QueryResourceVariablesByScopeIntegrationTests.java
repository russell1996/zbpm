package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-C8-51 (часть A): {@code GET /process-instances/{id}/variables-by-scope} —
 * переменные инстанса, сгруппированные по скоупам, на живом MI-подпроцессе
 * (два параллельных инстанса тела), через реальную цепочку фильтров и реальную БД (V11).
 *
 * <p>Контракт ответа (одобрен maintainer): root-группа ({@code scope == null}) всегда
 * первая; дальше по группе на каждый существующий scopeId; строки — СЫРЫЕ
 * (включая {@code _mi_batch_miSub}); группы детерминированно упорядочены.
 *
 * <p>Наследник {@code QueryResourceVariablesByScopePgIT} гоняет этот же набор на
 * реальном PostgreSQL — H2-green не считается PG-green (V11/P-22).
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class QueryResourceVariablesByScopeIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private TokenRepository tokenRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String userAKey;   // grant on the C851 process
    private String userBKey;   // grant on ANOTHER process only → foreign 404
    private String processKey;
    private String otherProcessKey;
    private UUID pdId;
    private UUID liveInstance; // read-only shared fixture for the grouping assertions

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");

        processKey = "c851-vars-" + UUID.randomUUID();
        otherProcessKey = "c851-other-" + UUID.randomUUID();
        pdId = deployProcess("c851-variables-by-scope.bpmn", "id=\"c851-variables-by-scope\"",
            "id=\"" + processKey + "\"");
        deployProcess("sec43-process.bpmn", "id=\"sec43-process\"", "id=\"" + otherProcessKey + "\"");

        UUID userA = createUser("c851-userA-" + UUID.randomUUID());
        addMember(userA, processKey, "OWNER");
        userAKey = createApiKeyForUser(userA);
        setGrants(userA, processKey, "START");

        UUID userB = createUser("c851-userB-" + UUID.randomUUID());
        addMember(userB, otherProcessKey, "OWNER");
        userBKey = createApiKeyForUser(userB);
        setGrants(userB, otherProcessKey, "START");

        liveInstance = startInstance(pdId);
    }

    // ==================== Критерий: группировка по скоупам, root первым ====================

    @Test
    void liveMiInstance_rootGroupFirstThenOneGroupPerMiBody() throws Exception {
        JsonNode groups = groups(liveInstance);

        assertThat(groups).hasSize(3); // root + 2 MI bodies
        assertThat(groups.get(0).get("scope").isNull())
            .as("root group must be first (scope == null)")
            .isTrue();

        List<String> rootNames = variableNames(groups.get(0));
        assertThat(rootNames)
            .as("root group carries root rows and the raw engine-internal MI batch marker")
            .contains("items", "laneMarker", "_mi_batch_miSub");
        assertThat(rootNames)
            .as("scoped MI rows must NOT be mixed into the root group")
            .doesNotContain("item", "loopCounter");
        assertThat(rootNames).as("deterministic order inside a group (by name)").isSorted();

        for (int i = 1; i < groups.size(); i++) {
            JsonNode scope = groups.get(i).get("scope");
            assertThat(scope.get("activityId").asText()).isNotBlank();
            assertThat(scope.get("bpmnElementId").asText()).isEqualTo("miSub");
            assertThat(scope.get("elementType").asText()).isEqualTo("SUB_PROCESS");
            assertThat(scope.get("status").asText()).isIn("CREATED", "IN_PROGRESS");
            assertThat(scope.get("parentScopeActivityId").isNull())
                .as("top-level MI body scope has no enclosing scope")
                .isTrue();
        }

        assertThat(itemValues(groups)).containsExactlyInAnyOrder("a", "b");
        assertThat(loopCounters(groups)).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void liveMiInstance_groupsHaveDeterministicOrderAcrossCalls() throws Exception {
        assertThat(groupScopeIds(groups(liveInstance))).isEqualTo(groupScopeIds(groups(liveInstance)));
    }

    // ==================== Критерий: /variables без изменений (root-only, WO-ENG-14) ====================

    @Test
    void plainVariablesEndpoint_staysRootOnlyAndHidesEngineInternals() throws Exception {
        MvcResult result = mockMvc.perform(get("/variables")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("processInstanceId", liveInstance.toString())
                        .param("pageSize", "200"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");

        List<String> names = new ArrayList<>();
        data.forEach(v -> names.add(v.get("name").asText()));
        assertThat(names).contains("items", "laneMarker");
        assertThat(names)
            .as("scoped MI rows and _mi_batch_ stay out of the plain /variables list")
            .doesNotContain("item", "loopCounter", "_mi_batch_miSub");
        data.forEach(v -> assertThat(v.get("activityId").isNull())
            .as("root rows resolve to a null activityId (scopeId IS NULL; replaces WO-ENG-14 masking)")
            .isTrue());
    }

    // ==================== Критерий: завершённые инстансы тел дают свои переменные ====================

    @Test
    void oneMiBodyCompleted_thatScopeGroupDisappearsAndSiblingStays() throws Exception {
        UUID instance = startInstance(pdId);
        JsonNode before = groups(instance);
        assertThat(before).hasSize(3);

        String scopeA = scopeIdWithItem(before, "a");
        UUID taskA = userTaskInScope(instance, scopeA);
        complete(taskA);

        JsonNode after = groups(instance);
        assertThat(after).hasSize(2); // root + the still-live body
        assertThat(groupScopeIds(after)).doesNotContain(scopeA);
        assertThat(itemValues(after)).containsExactly("b");
        assertThat(loopCounters(after)).containsExactly("2");
    }

    @Test
    void instanceFinished_onlyRootGroupRemains() throws Exception {
        UUID instance = startInstance(pdId);
        for (UUID taskId : userTaskIds(instance)) {
            complete(taskId);
        }

        mockMvc.perform(get("/process-instances/" + instance)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").exists());

        JsonNode groups = groups(instance);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("scope").isNull()).isTrue();
        assertThat(variableNames(groups.get(0))).doesNotContain("item", "loopCounter");
        assertThat(itemValues(groups)).isEmpty();
    }

    // ==================== Критерий: authz (чужой тенант → 404) ====================

    @Test
    void foreignInstance_returns404() throws Exception {
        mockMvc.perform(get("/process-instances/" + liveInstance + "/variables-by-scope")
                        .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isNotFound());
    }

    @Test
    void ownGrant_returns200() throws Exception {
        mockMvc.perform(get("/process-instances/" + liveInstance + "/variables-by-scope")
                        .header("Authorization", "Bearer " + userAKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].variables").exists());
    }

    @Test
    void unknownInstance_returns404() throws Exception {
        mockMvc.perform(get("/process-instances/" + UUID.randomUUID() + "/variables-by-scope")
                        .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNotFound());
    }

    // ==================== Helpers ====================

    private JsonNode groups(UUID instanceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/process-instances/" + instanceId + "/variables-by-scope")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> groupScopeIds(JsonNode groups) {
        List<String> ids = new ArrayList<>();
        for (JsonNode group : groups) {
            JsonNode scope = group.get("scope");
            ids.add(scope == null || scope.isNull() ? "root" : scope.get("activityId").asText());
        }
        return ids;
    }

    private List<String> variableNames(JsonNode group) {
        List<String> names = new ArrayList<>();
        group.get("variables").forEach(v -> names.add(v.get("name").asText()));
        return names;
    }

    private JsonNode variable(JsonNode group, String name) {
        for (JsonNode v : group.get("variables")) {
            if (name.equals(v.get("name").asText())) {
                return v;
            }
        }
        return null;
    }

    private List<String> itemValues(JsonNode groups) {
        return valuesOf(groups, "item");
    }

    private List<String> loopCounters(JsonNode groups) {
        return valuesOf(groups, "loopCounter");
    }

    private List<String> valuesOf(JsonNode groups, String name) {
        List<String> values = new ArrayList<>();
        for (JsonNode group : groups) {
            JsonNode v = variable(group, name);
            if (v != null) {
                values.add(v.get("value").asText());
            }
        }
        return values;
    }

    private String scopeIdWithItem(JsonNode groups, String item) {
        for (JsonNode group : groups) {
            JsonNode v = variable(group, "item");
            if (v != null && item.equals(v.get("value").asText())) {
                return group.get("scope").get("activityId").asText();
            }
        }
        throw new AssertionError("no MI body scope with item=" + item);
    }

    /** user task → activity row → token → scopeActivityId (the body's scope id). */
    private UUID userTaskInScope(UUID instanceId, String scopeActivityId) throws Exception {
        for (UUID taskId : userTaskIds(instanceId)) {
            ActivityEntity activity = activityRepository.findById(taskId).orElseThrow();
            UUID scopeId = activity.getToken() == null ? null
                : tokenRepository.findById(activity.getToken())
                    .map(TokenEntity::getScopeActivityId)
                    .orElse(null);
            if (scopeActivityId.equals(String.valueOf(scopeId))) {
                return taskId;
            }
        }
        throw new AssertionError("no user task in scope " + scopeActivityId);
    }

    private List<UUID> userTaskIds(UUID instanceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("processInstanceId", instanceId.toString())
                        .param("pageSize", "200"))
                .andExpect(status().isOk())
                .andReturn();
        List<UUID> ids = new ArrayList<>();
        mapper.readTree(result.getResponse().getContentAsString()).get("data")
            .forEach(t -> ids.add(UUID.fromString(t.get("id").asText())));
        return ids;
    }

    private void complete(UUID taskId) throws Exception {
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private String loginAndGetToken(String username, String password) throws Exception {
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

    private UUID createUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    private UUID deployProcess(String file, String idToken, String replacementIdToken) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/" + file)))
            .replace(idToken, replacementIdToken);
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

    private UUID startInstance(UUID processDefinitionId) throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(List.of(
            variable("items", ProcessVariableType.JSON, "[\"a\",\"b\"]"),
            variable("laneMarker", ProcessVariableType.STRING, "root")));
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    private void addMember(UUID userId, String key, String role) throws Exception {
        mockMvc.perform(post("/processes/" + key + "/members")
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

    private void setGrants(UUID userId, String key, String permissions) throws Exception {
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"grants\":[{\"processKey\":\"" + key + "\",\"permissions\":\"" + permissions + "\"}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
