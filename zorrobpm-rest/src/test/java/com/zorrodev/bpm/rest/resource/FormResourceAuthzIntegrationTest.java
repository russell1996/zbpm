package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-47: FormResource authz — getForm/listForms/listElementBindings.
 * V11: full-context integration test with real DB + real filter chain.
 *
 * Setup:
 * - Deploy 2 processes (procA, procB) with different process keys
 * - Deploy form "formA" and bind to procA
 * - Deploy form "formB" and bind to procB
 * - Deploy form "unboundForm" (no binding)
 * - Create a user with grant on procA only
 *
 * Tests:
 * - Criteria 1: listForms for restricted user excludes procB forms
 * - Criteria 2: getForm for restricted user → 404 on procB form
 * - Criteria 3: getForm for admin → 200 on all forms (regression)
 * - Criteria 4: listElementBindings for restricted user → 404 on procB
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FormResourceAuthzIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String restrictedToken; // grant on procA only
    private String fullAToken;      // WO-SEC-54: isFull=true grant on procA only

    private static final String PROC_A_KEY = "sec47-procA-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String PROC_B_KEY = "sec47-procB-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String FORM_A_KEY = "sec47-formA-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String FORM_B_KEY = "sec47-formB-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String UNBOUND_FORM_KEY = "sec47-unbound-" + UUID.randomUUID().toString().substring(0, 8);

    private UUID pdIdA;
    private UUID pdIdB;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        // Create user with limited grants
        UUID restrictedUserId = createRestrictedUser("sec47-restricted-" + UUID.randomUUID().toString().substring(0, 8));
        String apiKey = createApiKeyForUser(restrictedUserId);

        // Deploy 2 processes
        pdIdA = deployProcess(PROC_A_KEY);
        pdIdB = deployProcess(PROC_B_KEY);

        // Add user as member of procA (required before setting grants)
        addMember(restrictedUserId, PROC_A_KEY, "VIEWER");

        // Set grants: only procA
        setGrants(restrictedUserId, PROC_A_KEY, "READ");
        restrictedToken = apiKey;

        // WO-SEC-54 crit #4: user with isFull=true grant on procA only (regression for WO-SEC-47)
        UUID fullAUserId = createRestrictedUser("sec54-fullA-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(fullAUserId, PROC_A_KEY, "OWNER");
        fullAToken = createApiKeyForUser(fullAUserId);
        setGrantsFull(fullAUserId, PROC_A_KEY);

        // Deploy forms
        deployForm(FORM_A_KEY, "{\"type\":\"form\",\"components\":[],\"properties\":{}}");
        deployForm(FORM_B_KEY, "{\"type\":\"form\",\"components\":[],\"properties\":{}}");
        deployForm(UNBOUND_FORM_KEY, "{\"type\":\"form\",\"components\":[],\"properties\":{}}");

        // Bind formA to procA (start event)
        createElementBinding(PROC_A_KEY, "startEvent", FORM_A_KEY);

        // Bind formB to procB (start event)
        createElementBinding(PROC_B_KEY, "startEvent", FORM_B_KEY);
        // unboundForm has NO binding
    }

    // ==================== Helper methods ====================

    private String login(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .header("Authorization", "Bearer none")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    private UUID createRestrictedUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass1"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
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
                        .content("{\"grants\":[{\"processKey\":\"" + processKey
                                + "\",\"permissions\":\"" + permissions + "\"}]}")
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

    private void addMember(UUID userId, String processKey, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private UUID deployProcess(String key) throws Exception {
        // Use inline BPMN for each process to avoid file name conflicts
        String bpmn = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
              id="Definitions_%s" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="%s" name="%s" isExecutable="true">
                <bpmn:startEvent id="startEvent" name="Start Event">
                  <bpmn:outgoing>flow1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:endEvent id="endEvent" name="End Event">
                  <bpmn:incoming>flow1</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="flow1" sourceRef="startEvent" targetRef="endEvent" />
              </bpmn:process>
              <bpmndi:BPMNDiagram id="BPMNDiagram_1">
                <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="%s">
                  <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="startEvent">
                    <dc:Bounds x="162" y="82" width="36" height="36" />
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="endEvent">
                    <dc:Bounds x="402" y="82" width="36" height="36" />
                  </bpmndi:BPMNShape>
                </bpmndi:BPMNPlane>
              </bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """.formatted(key, key, key, key);

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

    private void deployForm(String formKey, String schema) throws Exception {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(formKey);
        dto.setKind("FORM_JS");
        dto.setSchema(schema);
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    private void createElementBinding(String processKey, String elementId, String formKey) throws Exception {
        CreateElementBindingDTO dto = new CreateElementBindingDTO();
        dto.setElementId(elementId);
        dto.setArtifactKey(formKey);
        mockMvc.perform(post("/process-definitions/" + processKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ==================== Tests ====================

    /**
     * POF RED: restricted user can GET /forms/{procB form} → 200 (BUG: should be 404).
     * This test must be written FIRST, before the fix is applied.
     * After the fix, this same test must return 404.
     */
    @Test
    @Order(1)
    void pofRed_restrictedUser_getFormProcB_returns404() throws Exception {
        // Restricted user (grant on procA only) tries to get procB's form
        // EXPECTED: 404 (form belongs to inaccessible process)
        mockMvc.perform(get("/forms/" + FORM_B_KEY)
                        .header("Authorization", "Bearer " + restrictedToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Criteria 1: listForms for restricted user excludes procB forms.
     * Restricted user (grant on procA only) should see:
     * - formA (bound to procA) ✓
     * - unboundForm ✓
     * - formB (bound to procB) ✗
     */
    @Test
    @Order(2)
    void criteria1_listForms_restrictedUser_excludesProcBForms() throws Exception {
        MvcResult result = mockMvc.perform(get("/forms")
                        .header("Authorization", "Bearer " + restrictedToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode forms = mapper.readTree(body);

        // Should contain formA and unboundForm, but NOT formB
        assertThat(forms.isArray()).isTrue();
        var keys = new java.util.HashSet<String>();
        forms.forEach(f -> keys.add(f.get("key").asText()));

        assertThat(keys).contains(FORM_A_KEY, UNBOUND_FORM_KEY);
        assertThat(keys).doesNotContain(FORM_B_KEY);
    }

    /**
     * Criteria 2: getForm for restricted user → 404 on procB form.
     * (Same as POF GREEN — the fix makes this return 404.)
     */
    @Test
    @Order(3)
    void criteria2_getForm_restrictedUser_procBForm_returns404() throws Exception {
        mockMvc.perform(get("/forms/" + FORM_B_KEY)
                        .header("Authorization", "Bearer " + restrictedToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Criteria 3: getForm for admin → 200 on all forms (regression).
     */
    @Test
    @Order(4)
    void criteria3_getForm_admin_accessAllForms() throws Exception {
        // Admin should see all forms
        mockMvc.perform(get("/forms/" + FORM_A_KEY)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(FORM_A_KEY));

        mockMvc.perform(get("/forms/" + FORM_B_KEY)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(FORM_B_KEY));

        mockMvc.perform(get("/forms/" + UNBOUND_FORM_KEY)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(UNBOUND_FORM_KEY));
    }

    /**
     * Criteria 3 regression: getForm for restricted user → 200 on procA form.
     */
    @Test
    @Order(5)
    void criteria3_regression_getForm_restrictedUser_procAForm_returns200() throws Exception {
        mockMvc.perform(get("/forms/" + FORM_A_KEY)
                        .header("Authorization", "Bearer " + restrictedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(FORM_A_KEY));
    }

    /**
     * Criteria 3 regression: getForm for restricted user → 200 on unbound form.
     */
    @Test
    @Order(6)
    void criteria3_regression_getForm_restrictedUser_unboundForm_returns200() throws Exception {
        mockMvc.perform(get("/forms/" + UNBOUND_FORM_KEY)
                        .header("Authorization", "Bearer " + restrictedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(UNBOUND_FORM_KEY));
    }

    /**
     * Criteria 4: listElementBindings for restricted user → 404 on procB.
     */
    @Test
    @Order(7)
    void criteria4_listElementBindings_restrictedUser_procB_returns404() throws Exception {
        mockMvc.perform(get("/process-definitions/" + PROC_B_KEY + "/element-bindings")
                        .header("Authorization", "Bearer " + restrictedToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Criteria 4: listElementBindings for restricted user → 200 on procA.
     */
    @Test
    @Order(8)
    void criteria4_listElementBindings_restrictedUser_procA_returns200() throws Exception {
        mockMvc.perform(get("/process-definitions/" + PROC_A_KEY + "/element-bindings")
                        .header("Authorization", "Bearer " + restrictedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].artifactKey").value(FORM_A_KEY));
    }

    /**
     * Criteria 4: listElementBindings for admin → 200 on all processes (regression).
     */
    @Test
    @Order(9)
    void criteria4_listElementBindings_admin_accessAll() throws Exception {
        mockMvc.perform(get("/process-definitions/" + PROC_A_KEY + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/process-definitions/" + PROC_B_KEY + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    /**
     * Regression: listForms for admin shows all forms.
     */
    @Test
    @Order(10)
    void regression_listForms_admin_seesAllForms() throws Exception {
        MvcResult result = mockMvc.perform(get("/forms")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode forms = mapper.readTree(body);
        var keys = new java.util.HashSet<String>();
        forms.forEach(f -> keys.add(f.get("key").asText()));

        assertThat(keys).contains(FORM_A_KEY, FORM_B_KEY, UNBOUND_FORM_KEY);
    }

    /**
     * WO-SEC-54 crit #4: isFull=true grant on procA sees OWN procA form (200)
     * but still NOT procB form (404) — full access stays scoped to the granted process.
     */
    @Test
    @Order(12)
    void fullGrantOnA_seesOwnForm_butNotProcB() throws Exception {
        mockMvc.perform(get("/forms/" + FORM_A_KEY)
                        .header("Authorization", "Bearer " + fullAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(FORM_A_KEY));

        mockMvc.perform(get("/forms/" + FORM_B_KEY)
                        .header("Authorization", "Bearer " + fullAToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Unauthenticated request → 401 on listForms.
     */
    @Test
    @Order(11)
    void listForms_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/forms"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * WO-SEC-59 #7: schema-map must enforce process authz. A restricted user (grant on procA only)
     * calling schema-map for procB must be denied (404 — same resolution as getForm), not leak the
     * form schema of an inaccessible process.
     */
    @Test
    @Order(13)
    void criteria7_schemaMap_restrictedUser_procB_returns404() throws Exception {
        mockMvc.perform(get("/process-definitions/" + PROC_B_KEY + "/schema-map")
                        .header("Authorization", "Bearer " + restrictedToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Regression: admin can read schema-map for an accessible process.
     */
    @Test
    @Order(14)
    void criteria7_schemaMap_admin_procA_returns200() throws Exception {
        mockMvc.perform(get("/process-definitions/" + PROC_A_KEY + "/schema-map")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
