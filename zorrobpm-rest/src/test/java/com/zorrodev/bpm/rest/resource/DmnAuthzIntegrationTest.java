package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.service.DmnService;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-40: cross-tenant DMN isolation.
 * Full-context IT (V11): real filter chain, real DB, two principals with different grants.
 *
 * userB has a (non-full) grant on processB only — via a real API key (ServicePrincipal).
 * DMN decisions are scoped to process definitions A/B at deploy time.
 *
 * POF (G-K): without authz in DmnResource, userB GET /dmn/discountA → 200 + full content (RED);
 * with authz → 404 (GREEN).
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class DmnAuthzIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private DmnService dmnService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String userBKey;   // ServicePrincipal: grant on processB only
    private UUID pdIdA;
    private UUID pdIdB;

    private static final String PROC_A_KEY = "sec40-procA";
    private static final String PROC_B_KEY = "sec40-procB";

    private static final String DMN_A = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/" id="Definitions_discountA" name="discountA" namespace="http://camunda.org/schema/1.0/dmn" exporter="Camunda Modeler" exporterVersion="5.0.0">
          <decision id="discountA" name="Discount A">
            <decisionTable id="DecisionTable_A1" hitPolicy="FIRST">
              <input id="Input_A1" label="Category">
                <inputExpression id="InputExpression_A1" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_A1" label="Discount" name="discount" typeRef="number" />
              <rule id="Rule_A1">
                <inputEntry id="In_A1"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_A1"><text>20</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    private static final String DMN_B = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/" id="Definitions_discountB" name="discountB" namespace="http://camunda.org/schema/1.0/dmn" exporter="Camunda Modeler" exporterVersion="5.0.0">
          <decision id="discountB" name="Discount B">
            <decisionTable id="DecisionTable_B1" hitPolicy="FIRST">
              <input id="Input_B1" label="Category">
                <inputExpression id="InputExpression_B1" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_B1" label="Discount" name="discount" typeRef="number" />
              <rule id="Rule_B1">
                <inputEntry id="In_B1"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_B1"><text>30</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");

        // Two processes (registry + process definitions), like other authz ITs
        pdIdA = deployProcess(PROC_A_KEY);
        pdIdB = deployProcess(PROC_B_KEY);

        // userB — member of processB only, API key with a non-full grant on processB
        UUID userBId = createUser("sec40-userB-" + UUID.randomUUID(), "USER");
        addMember(userBId, PROC_B_KEY, "OWNER");
        userBKey = createApiKeyForUser(userBId);
        setGrants(userBId, PROC_B_KEY, "START");

        // Deploy one decision per process, scoped to its process definition
        dmnService.deploy(DMN_A, pdIdA);
        dmnService.deploy(DMN_B, pdIdB);
    }

    // ==================== Criterion #1: GET /dmn is scoped to the principal's processes ====================

    @Test
    void getDecisions_userWithGrantOnOwnProcess_seesOnlyOwnDecisions() throws Exception {
        MvcResult result = mockMvc.perform(get("/dmn")
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isOk())
            .andReturn();
        Set<String> ids = decisionIds(result);
        assertThat(ids).contains("discountB").doesNotContain("discountA");
    }

    @Test
    void getDecisions_admin_seesAllDecisions() throws Exception {
        MvcResult result = mockMvc.perform(get("/dmn")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        Set<String> ids = decisionIds(result);
        assertThat(ids).contains("discountA", "discountB");
    }

    @Test
    void getDecisions_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/dmn"))
            .andExpect(status().isUnauthorized());
    }

    // ==================== Criterion #2 (POF): GET /dmn/{id} of a foreign decision → 404 ====================

    @Test
    void getDecision_foreignDecision_returns404() throws Exception {
        // userB has access only to processB; discountA belongs to processA → 404, existence hidden
        mockMvc.perform(get("/dmn/discountA")
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isNotFound());
    }

    // ==================== Criterion #3: POST /dmn/{id}/evaluate of a foreign decision → 404 ====================

    @Test
    void evaluateDecision_foreignDecision_returns404() throws Exception {
        mockMvc.perform(post("/dmn/discountA/evaluate")
                .header("Authorization", "Bearer " + userBKey)
                .content("{\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound());
    }

    // ==================== Criterion #4: own decisions stay fully accessible (regression) ====================

    @Test
    void getDecision_ownDecision_stillAccessible() throws Exception {
        mockMvc.perform(get("/dmn/discountB")
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("discountB"));
    }

    @Test
    void evaluateDecision_ownDecision_stillWorks() throws Exception {
        mockMvc.perform(post("/dmn/discountB/evaluate")
                .header("Authorization", "Bearer " + userBKey)
                .content("{\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    @Test
    void admin_canReadForeignDecision() throws Exception {
        mockMvc.perform(get("/dmn/discountA")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("discountA"));
    }

    // ==================== Helpers ====================

    private Set<String> decisionIds(MvcResult result) throws Exception {
        JsonNode arr = mapper.readTree(result.getResponse().getContentAsString());
        Set<String> ids = new HashSet<>();
        for (JsonNode node : arr) {
            ids.add(node.get("id").asText());
        }
        return ids;
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
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));
        bpmn = bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                   .replace("name=\"Process 1\"", "name=\"" + key + "\"")
                   .replace("process id=\"process1\"", "process id=\"" + key + "\"");
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
}
