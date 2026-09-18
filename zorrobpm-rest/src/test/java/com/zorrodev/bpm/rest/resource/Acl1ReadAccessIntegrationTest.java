package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ACL-1 (ADR-8): read resolver split — definitions visible to ALL authenticated users,
 * runtime visible only to process members.
 *
 * Full-context IT (V11): real filter chain, real DB, four principals:
 * - outsider: JWT USER with NO membership — must see ALL definitions, but NO runtime;
 * - member:   JWT USER with membership (OWNER) on procA — sees procA runtime;
 * - admin:    SUPER_ADMIN — sees everything (regression);
 * - fullKey:  ServicePrincipal with isFull=true grant on procA ONLY — must NOT see procB
 *             runtime (WO-SEC-54 regression).
 *
 * POF (G-K): criterion3 (central): if readableRuntimePdIds returned null for UserPrincipal
 * ("see all"), the outsider would see instanceA (200) — test goes RED. Criterion1 (reverse):
 * if visibleDefinitionIds returned Set.of(), the outsider's definition list would be empty —
 * test goes RED.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class Acl1ReadAccessIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private com.zorrodev.bpm.engine.scheduler.FeedPositionAssigner feedPositionAssigner;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String outsiderToken; // JWT USER, no membership
    private String memberToken;   // JWT USER, OWNER of procA
    private String fullAKey;      // ServicePrincipal, isFull=true grant on procA only

    private String procAKey;
    private String procBKey;
    private UUID pdIdA;
    private UUID pdIdB;
    private UUID instanceA;
    private UUID userTaskA;
    private String formAKey;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");

        // Two deployed processes (definitions + registry rows).
        // procA's user task carries a formKey so GET /user-tasks/{id}/form resolves a real
        // embedded form with variable prefill (runtime read — WO-ACL-1 HOLD). procB has none.
        procAKey = "acl1-procA-" + UUID.randomUUID().toString().substring(0, 8);
        procBKey = "acl1-procB-" + UUID.randomUUID().toString().substring(0, 8);
        formAKey = "acl1-formA-" + UUID.randomUUID().toString().substring(0, 8);
        deployForm(formAKey);
        pdIdA = deployProcess(procAKey, formAKey);
        pdIdB = deployProcess(procBKey, null);

        // Real runtime for procA: instance with a variable + a blocking user task
        instanceA = startInstanceWithVariables(pdIdA);
        userTaskA = findUserTaskId(instanceA);

        // Outsider: JWT user without ANY membership
        UUID outsiderId = createUser("acl1-outsider-" + UUID.randomUUID().toString().substring(0, 8));
        outsiderToken = loginAndGetToken(findUsername(outsiderId), "pass");

        // Member: JWT user + real membership (OWNER) on procA via the admin API
        UUID memberId = createUser("acl1-member-" + UUID.randomUUID().toString().substring(0, 8));
        String memberUsername = findUsername(memberId);
        addMember(memberId, procAKey, "OWNER");
        memberToken = loginAndGetToken(memberUsername, "pass");

        // WO-SEC-54 regression: ServicePrincipal with REAL isFull=true grant on procA only
        UUID fullUserId = createUser("acl1-full-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(fullUserId, procAKey, "OWNER");
        fullAKey = createApiKeyForUser(fullUserId);
        setGrantsFull(fullUserId, procAKey);

        // Domain events: 2 for procA, 1 for procB
        emitEvent(pdIdA, "process-instance.started");
        emitEvent(pdIdA, "user-task.created");
        emitEvent(pdIdB, "process-instance.started");
        // WO-REL-38: курсор ленты — feed_position (ставит джоб).
        feedPositionAssigner.assignPendingPositions();
    }

    // ==================== Criterion #1: non-admin sees ALL definitions ====================

    @Test
    void criterion1_outsiderWithoutMembership_seesAllDefinitions() throws Exception {
        // WO-TEST-7: the module's definitions corpus outgrew the default page (pageSize=20) —
        // procA/procB stopped landing on the first page and this assert went red for a reason
        // that had nothing to do with authorization. The claim is "the outsider sees ALL
        // definitions", so the request must fetch all of them: pageSize=200 (the API maximum,
        // server-clamped in ProcessDefinitionServiceImpl/QueryServiceImpl) + latestVersionOnly
        // (one row per key, so rows ≈ unique keys instead of 20 rows per 17 keys). The 30
        // noise deployments below keep the test honest — with them the OLD unpaged assert is
        // red on exactly this corpus (POF by load), and >200 unique keys is ~4x away even with
        // the noise, ~10x without it.
        deployNoiseDefinitions(30);

        MvcResult result = mockMvc.perform(get("/process-definitions")
                        .param("pageSize", "200")
                        .param("latestVersionOnly", "true")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andReturn();

        Set<String> keys = definitionKeys(result);
        // POF (reverse): RED if visibleDefinitionIds returns Set.of() → empty list
        assertThat(keys).contains(procAKey, procBKey);
    }

    // ==================== Criterion #2: non-admin sees card/XML/structure of a FOREIGN definition ====================

    @Test
    void criterion2_outsider_seesForeignDefinitionCard() throws Exception {
        mockMvc.perform(get("/process-definitions/" + pdIdB)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pdIdB.toString()));
    }

    @Test
    void criterion2_outsider_seesForeignDefinitionXml() throws Exception {
        mockMvc.perform(get("/process-definitions/" + pdIdB + "/xml")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk());
    }

    @Test
    void criterion2_outsider_seesForeignDefinitionStructure() throws Exception {
        mockMvc.perform(get("/process-definitions/" + pdIdB + "/structure")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pdIdB.toString()))
                .andExpect(jsonPath("$.key").value(procBKey));
    }

    // ==================== Criterion #3 (POF, central): non-admin sees NO foreign runtime ====================

    @Test
    void criterion3_outsider_seesNoForeignInstances() throws Exception {
        MvcResult result = mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andReturn();
        // RED if readableRuntimePdIds returns null ("see all") for UserPrincipal
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("data").size()).isZero();
    }

    @Test
    void criterion3_outsider_foreignInstance_returns404() throws Exception {
        mockMvc.perform(get("/process-instances/" + instanceA)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void criterion3_outsider_seesNoForeignVariables() throws Exception {
        MvcResult result = mockMvc.perform(get("/variables")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("data").size()).isZero();
    }

    @Test
    void criterion3_outsider_foreignUserTask_returns404() throws Exception {
        mockMvc.perform(get("/user-tasks/" + userTaskA)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void criterion3_outsider_foreignUserTaskForm_returns404() throws Exception {
        // POF (WO-ACL-1 HOLD): task form of an instance = runtime read — carries variable prefill.
        // If requireRuntimePdAccess were swapped back to requirePdAccess (definition set = null for
        // a user = "see all"), the outsider would get 200 with the instance's variables.
        mockMvc.perform(get("/user-tasks/" + userTaskA + "/form")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void criterion3_outsider_seesNoForeignEvents() throws Exception {
        MvcResult result = mockMvc.perform(get("/events")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("data").size()).isZero();
    }

    // ==================== Criterion #4: member sees runtime of OWN process ====================

    @Test
    void criterion4_member_seesOwnInstance() throws Exception {
        mockMvc.perform(get("/process-instances/" + instanceA)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(instanceA.toString()));
    }

    @Test
    void criterion4_member_seesOwnVariables() throws Exception {
        MvcResult result = mockMvc.perform(get("/variables")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.size()).isGreaterThan(0);
        boolean foundAmount = false;
        for (JsonNode v : data) {
            if ("amount".equals(v.get("name").asText())) {
                foundAmount = true;
            }
        }
        assertThat(foundAmount).isTrue();
    }

    @Test
    void criterion4_member_seesOwnUserTask() throws Exception {
        mockMvc.perform(get("/user-tasks/" + userTaskA)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userTaskA.toString()));
    }

    @Test
    void criterion4_member_seesOwnUserTaskForm_withVariablePrefill() throws Exception {
        // Member of procA resolves the task form of the instance and receives the variable prefill
        // (amount=100) — the data this WO's new guard protects from outsiders.
        mockMvc.perform(get("/user-tasks/" + userTaskA + "/form")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("embedded"))
                .andExpect(jsonPath("$.data.amount").value("100"));
    }

    @Test
    void criterion4_member_seesOwnEvents_notForeign() throws Exception {
        MvcResult result = mockMvc.perform(get("/events")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        Set<String> pdIds = new HashSet<>();
        data.forEach(e -> pdIds.add(e.get("processDefinitionId").asText()));
        assertThat(pdIds).contains(pdIdA.toString());
        assertThat(pdIds).doesNotContain(pdIdB.toString());
    }

    // ==================== Criterion #5: SUPER_ADMIN sees everything (regression) ====================

    @Test
    void criterion5_admin_seesForeignInstance() throws Exception {
        mockMvc.perform(get("/process-instances/" + instanceA)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(instanceA.toString()));
    }

    @Test
    void criterion5_admin_seesAllEvents() throws Exception {
        // Ask per process instead of scanning the global feed: /events returns a PAGE, so once
        // enough events from other tests sit ahead of ours they fall outside it and the assertion
        // fails for a reason that has nothing to do with authorization. That is what reddened CI
        // on 2026-08-17 while every local run stayed green — the page filled differently there.
        // "Admin sees a foreign process's events" is what criterion 5 claims, and asking for that
        // process states the claim directly instead of hoping it survives the window.
        assertAdminSeesEventsOf(procAKey, pdIdA);
        assertAdminSeesEventsOf(procBKey, pdIdB);
    }

    private void assertAdminSeesEventsOf(String processKey, UUID expectedPdId) throws Exception {
        MvcResult result = mockMvc.perform(get("/events")
                        .param("processDefinitionKey", processKey)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        Set<String> pdIds = new HashSet<>();
        data.forEach(e -> pdIds.add(e.get("processDefinitionId").asText()));
        assertThat(pdIds).contains(expectedPdId.toString());
    }

    // ==================== Criterion #6: ServicePrincipal isFull NOT expanded (WO-SEC-54) ====================

    @Test
    void criterion6_fullGrantOnProcA_doesNotSeeProcBEvents() throws Exception {
        MvcResult result = mockMvc.perform(get("/events")
                        .header("Authorization", "Bearer " + fullAKey))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        Set<String> pdIds = new HashSet<>();
        data.forEach(e -> pdIds.add(e.get("processDefinitionId").asText()));
        // isFull=true is scoped to procA — procB events must NOT leak (WO-SEC-54 S-02)
        assertThat(pdIds).contains(pdIdA.toString());
        assertThat(pdIds).doesNotContain(pdIdB.toString());
    }

    // ==================== Helpers ====================

    private Set<String> definitionKeys(MvcResult result) throws Exception {
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        Set<String> keys = new HashSet<>();
        data.forEach(d -> keys.add(d.get("key").asText()));
        return keys;
    }

    private UUID deployProcess(String key) throws Exception {
        return deployProcess(key, null);
    }

    /**
     * WO-TEST-7 (POF by load): definitions whose names sort BEFORE this test's own
     * ("aaa-" < "acl1-"), so with the default pageSize=20 they guarantee the old unpaged
     * assertion misses procA/procB — the exact failure mode that reddened master.
     */
    private void deployNoiseDefinitions(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            deployProcess("aaa-noise-" + UUID.randomUUID().toString().substring(0, 8) + "-" + i);
        }
    }

    private UUID deployProcess(String key, String formKey) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/sec43-process.bpmn")));
        bpmn = bpmn.replace("id=\"sec43-process\"", "id=\"" + key + "\"")
                   .replace("name=\"SEC43 Process\"", "name=\"" + key + "\"");
        if (formKey != null) {
            // Give procA's user task a real form (resolved by getUserTaskForm → task.getFormKey())
            String formDef = "<bpmn:extensionElements>"
                + "<zeebe:formDefinition formKey=\"" + formKey + "\" />"
                + "</bpmn:extensionElements>";
            bpmn = bpmn.replace("<bpmn:userTask id=\"userTask1\" name=\"User Task\">",
                "<bpmn:userTask id=\"userTask1\" name=\"User Task\">" + formDef);
        }
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

    private void deployForm(String formKey) throws Exception {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(formKey);
        dto.setKind("FORM_JS");
        dto.setSchema("{\"type\":\"form\",\"components\":[],\"properties\":{}}");
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    private void bindFormToUserTask(String processKey, String formKey) throws Exception {
        mockMvc.perform(post("/process-definitions/" + processKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"elementId\":\"userTask1\",\"artifactKey\":\"" + formKey + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private UUID startInstanceWithVariables(UUID pdId) throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(java.util.List.of(
            new com.zorrodev.bpm.contract.model.ProcessVariable() {{
                setName("amount");
                setValue("100");
                setType(com.zorrodev.bpm.contract.model.ProcessVariableType.STRING);
            }}
        ));
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
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

    private void emitEvent(UUID pdId, String type) {
        DomainEventEntity event = new DomainEventEntity();
        event.setId(UUID.randomUUID());
        event.setType(type);
        event.setVersion(1);
        event.setOccurredAt(Instant.now());
        event.setProcessDefinitionId(pdId);
        event.setProcessInstanceId(instanceA != null ? instanceA : UUID.randomUUID());
        event.setOwnerScope(pdId.toString());
        event.setData(Map.of());
        domainEventRepository.save(event);
    }

    private String findUsername(UUID userId) throws Exception {
        return userRepository.findById(userId).orElseThrow().getUsername();
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

    private void setGrantsFull(UUID userId, String processKey) throws Exception {
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"full\":true}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
