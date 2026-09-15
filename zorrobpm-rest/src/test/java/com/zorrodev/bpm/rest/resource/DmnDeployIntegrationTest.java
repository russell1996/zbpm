package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-C8-15 (A-7): DMN deploy was unreachable in production — only tests called
 * {@code DmnService.deploy} directly. Every test here goes through the HTTP layer
 * (MockMvc, full context): deploy DMN via {@code POST /dmn}, then read/evaluate/run.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class DmnDeployIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private com.zorrodev.bpm.engine.repository.AuditLogRepository auditLogRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;

    private static final String DMN_DISCOUNT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="Definitions_discount" name="discount" namespace="http://camunda.org/schema/1.0/dmn">
          <decision id="discount" name="Discount">
            <decisionTable id="DecisionTable_1" hitPolicy="FIRST">
              <input id="Input_1" label="Category">
                <inputExpression id="InputExpression_1" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_1" label="Discount" name="discount" typeRef="number" />
              <rule id="Rule_gold">
                <inputEntry id="In_gold"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_gold"><text>20</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    private static final String BPMN_BUSINESS_RULE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="Definitions_e2e" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="KEY" name="KEY" isExecutable="true">
            <bpmn:startEvent id="startEvent" name="startEvent">
              <bpmn:outgoing>flow1</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:sequenceFlow id="flow1" name="flow1" sourceRef="startEvent" targetRef="decide" />
            <bpmn:businessRuleTask id="decide" name="decide">
              <bpmn:extensionElements>
                <zeebe:calledDecision decisionId="discount" resultVariable="discount" />
              </bpmn:extensionElements>
              <bpmn:incoming>flow1</bpmn:incoming>
              <bpmn:outgoing>flow2</bpmn:outgoing>
            </bpmn:businessRuleTask>
            <bpmn:sequenceFlow id="flow2" name="flow2" sourceRef="decide" targetRef="endEvent" />
            <bpmn:endEvent id="endEvent" name="endEvent">
              <bpmn:incoming>flow2</bpmn:incoming>
            </bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");
    }

    // ==================== Criteria #1 + #3: deploy via HTTP, visible via GET, evaluable ====================

    @Test
    void deployDmn_admin_fullChainWorks() throws Exception {
        MvcResult deploy = mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"dmn\":" + mapper.writeValueAsString(DMN_DISCOUNT) + "}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        assertThat(decisionIds(deploy)).contains("discount");

        MvcResult list = mockMvc.perform(get("/dmn")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(decisionIds(list)).contains("discount");

        MvcResult eval = mockMvc.perform(post("/dmn/discount/evaluate")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(mapper.readTree(eval.getResponse().getContentAsString()).get("result").asInt()).isEqualTo(20);
    }

    // ==================== Criterion #2: SUPER_ADMIN only, same codes as BPMN deploy ====================

    @Test
    void deployDmn_nonAdmin_returns403() throws Exception {
        String username = "c8-15-user-" + UUID.randomUUID();
        createUser(username, "USER");
        String userToken = loginAndGetToken(username, "pass");

        mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + userToken)
                .content("{\"dmn\":" + mapper.writeValueAsString(DMN_DISCOUNT) + "}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden());
    }

    @Test
    void deployDmn_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/dmn")
                .content("{\"dmn\":" + mapper.writeValueAsString(DMN_DISCOUNT) + "}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    // ==================== Criterion #4: process binding feeds the existing authz scoping ====================

    @Test
    void deployDmn_scopedBinding_visibleOnlyToGrantedProcess() throws Exception {
        UUID pdIdA = deployProcess("c8-15-procA-" + UUID.randomUUID().toString().substring(0, 8));
        UUID pdIdB = deployProcess("c8-15-procB-" + UUID.randomUUID().toString().substring(0, 8));

        String username = "c8-15-userB-" + UUID.randomUUID();
        UUID userBId = createUser(username, "USER");
        addMember(userBId, processKey(pdIdB), "OWNER");
        String userBKey = createApiKeyForUser(userBId);
        setGrants(userBId, processKey(pdIdB), "START");

        deployDmn(DMN_DISCOUNT.replace("discount", "discountA"), pdIdA);
        deployDmn(DMN_DISCOUNT.replace("discount", "discountB"), pdIdB);

        // grant on B only: own decision visible, foreign hidden — requireDecisionAccess finally has data
        mockMvc.perform(get("/dmn/discountB")
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isOk());
        mockMvc.perform(get("/dmn/discountA")
                .header("Authorization", "Bearer " + userBKey))
            .andExpect(status().isNotFound());
    }

    // ==================== Criterion #5: businessRuleTask runs after HTTP deploys (the value test) ====================

    @Test
    void deployDmn_writesAuditLogRecord() throws Exception {
        // WO-C8-17 (debt from WO-C8-15): every newly deployed decision version leaves a DEPLOY
        // audit record, like BPMN deploy does. Unique decision id — rest ITs commit rows.
        String decision = "c8-17-audit-" + UUID.randomUUID().toString().substring(0, 8);
        String dmn = DMN_DISCOUNT
            .replace("Definitions_discount", "Definitions_" + decision)
            .replace("id=\"discount\"", "id=\"" + decision + "\"");
        MvcResult deploy = mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"dmn\":" + mapper.writeValueAsString(dmn) + "}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        assertThat(decisionIds(deploy)).contains(decision);

        assertThat(auditLogRepository.findByFilters(decision, null, null, null))
            .anyMatch(e -> "DEPLOY".equals(e.getAction()));
    }

    @Test
    void businessRuleProcess_endToEnd_afterHttpDeploys() throws Exception {
        String key = "c8-15-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = BPMN_BUSINESS_RULE.replace("KEY", key);
        MvcResult deployed = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(java.util.Map.of("bpmn", bpmn)))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        UUID pdId = UUID.fromString(mapper.readTree(deployed.getResponse().getContentAsString()).get("id").asText());

        deployDmn(DMN_DISCOUNT, null);

        MvcResult started = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"processDefinitionId\":\"" + pdId + "\",\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        UUID piId = UUID.fromString(mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());

        // the decision really evaluated during execution — impossible in prod before this WO
        MvcResult vars = mockMvc.perform(get("/variables?processInstanceId=" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = mapper.readTree(vars.getResponse().getContentAsString()).get("data");
        String discount = null;
        for (JsonNode v : data) {
            if ("discount".equals(v.get("name").asText())) {
                discount = v.get("value").asText();
            }
        }
        assertThat(discount).isEqualTo("20");

        MvcResult pi = mockMvc.perform(get("/process-instances/" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(mapper.readTree(pi.getResponse().getContentAsString()).hasNonNull("completedAt")).isTrue();
    }

    // ==================== Helpers ====================

    private void deployDmn(String dmnXml, UUID processDefinitionId) throws Exception {
        String body = processDefinitionId == null
            ? "{\"dmn\":" + mapper.writeValueAsString(dmnXml) + "}"
            : "{\"dmn\":" + mapper.writeValueAsString(dmnXml) + ",\"processDefinitionId\":\"" + processDefinitionId + "\"}";
        mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + adminToken)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }

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
        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(java.util.Map.of("bpmn", bpmn)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String processKey(UUID pdId) throws Exception {
        MvcResult result = mockMvc.perform(get("/process-definitions/" + pdId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
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
