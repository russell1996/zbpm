package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.DmnDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-C8-18: atomic multi-resource deployments via {@code POST /deployments}.
 * Every test goes through the HTTP layer (MockMvc, full context).
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class DeploymentIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private DmnDefinitionRepository dmnDefinitionRepository;
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

    // ==================== Criterion #2: atomicity — broken resource poisons the whole batch ====================

    @Test
    void deployBatch_brokenDmn_deploysNothing() throws Exception {
        String key = "c8-18-atomic-" + UUID.randomUUID().toString().substring(0, 8);
        String decision = "c8-18-broken-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = BPMN_BUSINESS_RULE.replace("KEY", key);
        String brokenDmn = DMN_DISCOUNT
            .replace("Definitions_discount", "Definitions_" + decision)
            .replace("id=\"discount\"", "id=\"" + decision + "\"")
            + "<broken";

        mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content(batchBody(bpmn, brokenDmn))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());

        // nothing was laid down: the decision is absent from GET /dmn ...
        MvcResult list = mockMvc.perform(get("/dmn")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(list.getResponse().getContentAsString()).doesNotContain(decision);

        // ... and no process version row survived either (direct DB proof — a re-POST alone
        // cannot discriminate: the sha256 fast path returns version 1 whether the row was
        // rolled back or was never there, so count rows instead).
        assertThat(processDefinitionRepository.findAll().stream()
            .filter(pd -> key.equals(pd.getKey()))
            .toList()).isEmpty();

        // ... and re-posting the same BPMN alone creates version 1 (no half-committed row survived)
        MvcResult redeployed = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(java.util.Map.of("bpmn", bpmn)))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        assertThat(mapper.readTree(redeployed.getResponse().getContentAsString()).get("version").asInt()).isEqualTo(1);
    }

    // ==================== Criteria #1 + #3: success batch, one deployment_id, end-to-end ====================

    @Test
    void deployBatch_bpmnAndDmn_sharesDeploymentIdAndRuns() throws Exception {
        String key = "c8-18-ok-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = BPMN_BUSINESS_RULE.replace("KEY", key);
        // DMN item FIRST in the request on purpose: the service owns the ordering, not the client.
        MvcResult deployed = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"resources\":["
                    + "{\"type\":\"DMN\",\"content\":" + mapper.writeValueAsString(DMN_DISCOUNT) + "},"
                    + "{\"type\":\"BPMN\",\"content\":" + mapper.writeValueAsString(bpmn) + "}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode body = mapper.readTree(deployed.getResponse().getContentAsString());
        UUID deploymentId = UUID.fromString(body.get("id").asText());
        UUID pdId = UUID.fromString(body.get("processes").get(0).get("processDefinitionId").asText());
        assertThat(body.get("processes").get(0).get("key").asText()).isEqualTo(key);
        assertThat(body.get("decisions").get(0).get("decisionId").asText()).isEqualTo("discount");

        // both rows carry the same deployment_id (direct DB proof of the linkage C8-3b needs)
        assertThat(processDefinitionRepository.findById(pdId).orElseThrow().getDeploymentId()).isEqualTo(deploymentId);
        assertThat(dmnDefinitionRepository.findFirstByDecisionIdOrderByVersionDesc("discount").orElseThrow().getDeploymentId())
            .isEqualTo(deploymentId);

        // the deployed process really runs against the deployed decision (HTTP end-to-end)
        MvcResult started = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"processDefinitionId\":\"" + pdId + "\",\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        UUID piId = UUID.fromString(mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());
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
    }

    // ==================== Criterion #4: single deploys behave as before (deployment_id stays NULL) ====================

    @Test
    void singleDeploys_leaveDeploymentIdNull() throws Exception {
        String key = "c8-18-single-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = BPMN_BUSINESS_RULE.replace("KEY", key);
        MvcResult pd = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(java.util.Map.of("bpmn", bpmn)))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        UUID pdId = UUID.fromString(mapper.readTree(pd.getResponse().getContentAsString()).get("id").asText());
        assertThat(processDefinitionRepository.findById(pdId).orElseThrow().getDeploymentId()).isNull();

        String decision = "c8-18-single-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"dmn\":" + mapper.writeValueAsString(
                    DMN_DISCOUNT.replace("Definitions_discount", "Definitions_" + decision).replace("id=\"discount\"", "id=\"" + decision + "\"")) + "}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
        assertThat(dmnDefinitionRepository.findFirstByDecisionIdOrderByVersionDesc(decision).orElseThrow().getDeploymentId())
            .isNull();
    }

    // ==================== Criterion #5: SUPER_ADMIN only ====================

    @Test
    void deployBatch_nonAdmin_returns403() throws Exception {
        String username = "c8-18-user-" + UUID.randomUUID();
        createUser(username, "USER");
        String userToken = loginAndGetToken(username, "pass");

        mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + userToken)
                .content(batchBody(BPMN_BUSINESS_RULE.replace("KEY", "unused"), DMN_DISCOUNT))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden());
    }

    @Test
    void deployBatch_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/deployments")
                .content(batchBody(BPMN_BUSINESS_RULE.replace("KEY", "unused"), DMN_DISCOUNT))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    // ==================== Request validation ====================

    @Test
    void deployBatch_unknownType_returns400() throws Exception {
        mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"resources\":[{\"type\":\"FORM\",\"content\":\"<x/>\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }

    @Test
    void deployBatch_emptyResources_returns400() throws Exception {
        mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"resources\":[]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }

    // ==================== Helpers ====================

    private String batchBody(String bpmn, String dmn) throws Exception {
        return "{\"resources\":["
            + "{\"type\":\"BPMN\",\"content\":" + mapper.writeValueAsString(bpmn) + "},"
            + "{\"type\":\"DMN\",\"content\":" + mapper.writeValueAsString(dmn) + "}]}";
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
}
