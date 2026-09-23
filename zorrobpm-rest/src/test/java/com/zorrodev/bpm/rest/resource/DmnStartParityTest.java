package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-DIFF-8 (Raxon finding #13, WO-028 scenarios S-057/058/059): DMN business rule
 * tasks through the PUBLIC API (POST /dmn + POST /process-definitions, never the
 * service layer). BPMN+DMN are copied verbatim from
 * {@code Other Projects/raxon-bpm/scenarios/S-05{7,8,9}-dmn-*}; the start variables
 * use the harness's encoding (Integer amount -&gt; LONG, same as Raxon's
 * {@code ZorroClient.toProcessVariable}).
 *
 * <p>Live-Zeebe expectations (Raxon oracle): S-057 completes with
 * {@code tier=standard}; S-058 completes with a null tier (stored as "" by the
 * pre-existing {@code ElementSupport.toProcessVariable} null branch); S-059 stalls
 * with a CALLED_DECISION_ERROR incident and re-evaluates on resolve — the start
 * itself is never rejected.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class DmnStartParityTest {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;

    // S-057 process.bpmn verbatim (calledDecision tier-decision -> tier)
    private static final String S057_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                          id="Defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="s057-dmn-happy" name="S-057 dmn happy" isExecutable="true">
            <bpmn:startEvent id="start" name="Start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="decide"/>
            <bpmn:businessRuleTask id="decide" name="Decide tier">
              <bpmn:extensionElements>
                <zeebe:calledDecision decisionId="tier-decision" resultVariable="tier"/>
              </bpmn:extensionElements>
              <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>
            </bpmn:businessRuleTask>
            <bpmn:sequenceFlow id="f2" sourceRef="decide" targetRef="end"/>
            <bpmn:endEvent id="end" name="End"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """;

    // S-057 tier.dmn verbatim (UNIQUE, input typeRef number, output tier/string)
    private static final String TIER_DMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                     xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/"
                     xmlns:dc="http://www.omg.org/spec/DMN/20180521/DC/"
                     id="defs-tier" name="tier-defs" namespace="http://raxon.io/dmn/tier">
          <decision id="tier-decision" name="Tier decision">
            <decisionTable id="tier-table" hitPolicy="UNIQUE">
              <input id="in-1" label="Amount">
                <inputExpression id="in-expr-1" typeRef="number">
                  <text>amount</text>
                </inputExpression>
              </input>
              <output id="out-1" label="Tier" name="tier" typeRef="string"/>
              <rule id="rule-1">
                <description>small</description>
                <inputEntry id="in-entry-1">
                  <text>&lt; 100</text>
                </inputEntry>
                <outputEntry id="out-entry-1">
                  <text>"standard"</text>
                </outputEntry>
              </rule>
              <rule id="rule-2">
                <description>big</description>
                <inputEntry id="in-entry-2">
                  <text>&gt;= 100</text>
                </inputEntry>
                <outputEntry id="out-entry-2">
                  <text>"premium"</text>
                </outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    // S-058 process.bpmn verbatim (calledDecision gap-decision -> tier)
    private static final String S058_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                          id="Defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="s058-dmn-no-match" name="S-058 dmn no match" isExecutable="true">
            <bpmn:startEvent id="start" name="Start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="decide"/>
            <bpmn:businessRuleTask id="decide" name="Decide tier">
              <bpmn:extensionElements>
                <zeebe:calledDecision decisionId="gap-decision" resultVariable="tier"/>
              </bpmn:extensionElements>
              <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>
            </bpmn:businessRuleTask>
            <bpmn:sequenceFlow id="f2" sourceRef="decide" targetRef="end"/>
            <bpmn:endEvent id="end" name="End"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """;

    // S-058 gap.dmn verbatim (UNIQUE, gap table <100 / >200)
    private static final String GAP_DMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                     id="defs-gap" name="gap-defs" namespace="http://raxon.io/dmn/gap">
          <decision id="gap-decision" name="Gap decision">
            <decisionTable id="gap-table" hitPolicy="UNIQUE">
              <input id="in-1" label="Amount">
                <inputExpression id="in-expr-1" typeRef="number">
                  <text>amount</text>
                </inputExpression>
              </input>
              <output id="out-1" label="Tier" name="tier" typeRef="string"/>
              <rule id="rule-1">
                <inputEntry id="in-entry-1">
                  <text>&lt; 100</text>
                </inputEntry>
                <outputEntry id="out-entry-1">
                  <text>"standard"</text>
                </outputEntry>
              </rule>
              <rule id="rule-2">
                <inputEntry id="in-entry-2">
                  <text>&gt; 200</text>
                </inputEntry>
                <outputEntry id="out-entry-2">
                  <text>"premium"</text>
                </outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    // S-059 process.bpmn verbatim (unknown decisionId no-such-decision)
    private static final String S059_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                          id="Defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="s059-dmn-missing-decision" name="S-059 dmn missing" isExecutable="true">
            <bpmn:startEvent id="start" name="Start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="decide"/>
            <bpmn:businessRuleTask id="decide" name="Decide tier">
              <bpmn:extensionElements>
                <zeebe:calledDecision decisionId="no-such-decision" resultVariable="tier"/>
              </bpmn:extensionElements>
              <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>
            </bpmn:businessRuleTask>
            <bpmn:sequenceFlow id="f2" sourceRef="decide" targetRef="end"/>
            <bpmn:endEvent id="end" name="End"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");
    }

    @Test
    void s057_happy_longAmount_completesWithTierStandard() throws Exception {
        UUID pdId = deployBpmn(S057_BPMN);
        deployDmn(TIER_DMN);

        UUID piId = start(pdId, "amount", "50", "LONG");

        assertThat(instanceCompleted(piId)).isTrue();
        assertThat(variableValue(piId, "tier")).isEqualTo("standard");
    }

    @Test
    void s058_noMatch_longAmount_completesWithNullTier() throws Exception {
        UUID pdId = deployBpmn(S058_BPMN);
        deployDmn(GAP_DMN);

        UUID piId = start(pdId, "amount", "150", "LONG");

        // no rule matches: NOT an incident (Raxon probe fact) — completes, null tier
        // stored as "" by the pre-existing toProcessVariable null branch.
        assertThat(instanceCompleted(piId)).isTrue();
        assertThat(variableValue(piId, "tier")).isEqualTo("");
    }

    @Test
    void s059_missingDecision_stallsWithIncidentInsteadOf422() throws Exception {
        // Unique unknown id: rest ITs commit DMN rows globally (P-8) — a fixed id
        // would see a decision deployed by the resolve test below.
        String missingId = "no-such-decision-" + UUID.randomUUID().toString().substring(0, 8);
        UUID pdId = deployBpmn(S059_BPMN.replace("no-such-decision", missingId));
        deployDmn(TIER_DMN);

        // Zeebe parity (Raxon WO-028 probe): CALLED_DECISION_ERROR incident, process
        // stalls. The start itself must NOT be rejected with 422.
        UUID piId = start(pdId, "amount", "50", "LONG");

        assertThat(instanceCompleted(piId)).isFalse();
        JsonNode incident = incidentFor(piId, missingId);
        assertThat(incident.get("bpmnElementId").asText()).isEqualTo("decide");
    }

    @Test
    void s057_bpmnOnlyDeploy_stallsWithIncidentInsteadOf422() throws Exception {
        // Harness-accurate path (Raxon ZorroClient.deploy sends BPMN only, never the
        // DMN): the referenced decision is unknown at start time. Same incident
        // behavior as S-059 — the start is accepted, the token parks. Unique id for
        // the same P-8 reason: other tests deploy tier-decision globally.
        String missingId = "tier-decision-" + UUID.randomUUID().toString().substring(0, 8);
        UUID pdId = deployBpmn(S057_BPMN.replace("tier-decision", missingId));

        UUID piId = start(pdId, "amount", "50", "LONG");

        assertThat(instanceCompleted(piId)).isFalse();
        JsonNode incident = incidentFor(piId, missingId);
        assertThat(incident.get("bpmnElementId").asText()).isEqualTo("decide");
    }

    @Test
    void s059_resolveAfterDeploy_completesWithTierStandard() throws Exception {
        String missingId = "no-such-decision-" + UUID.randomUUID().toString().substring(0, 8);
        UUID pdId = deployBpmn(S059_BPMN.replace("no-such-decision", missingId));
        deployDmn(TIER_DMN);
        UUID piId = start(pdId, "amount", "50", "LONG");
        assertThat(instanceCompleted(piId)).isFalse();
        UUID incidentId = UUID.fromString(incidentFor(piId, missingId).get("id").asText());

        // Operator deploys the missing decision, then resolves: resolve re-executes
        // the element (Raxon WO-028 resolve = re-evaluation parity).
        deployDmn(TIER_DMN.replace("tier-decision", missingId));
        mockMvc.perform(post("/incidents/" + incidentId + "/resolve")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        assertThat(instanceCompleted(piId)).isTrue();
        assertThat(variableValue(piId, "tier")).isEqualTo("standard");
    }

    // ==================== Helpers (public HTTP API only) ====================

    private UUID deployBpmn(String bpmn) throws Exception {
        MvcResult result = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(java.util.Map.of("bpmn", bpmn)))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void deployDmn(String dmn) throws Exception {
        mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"dmn\":" + mapper.writeValueAsString(dmn) + "}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }

    private UUID start(UUID pdId, String varName, String varValue, String varType) throws Exception {
        MvcResult started = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"processDefinitionId\":\"" + pdId + "\",\"variables\":[{\"name\":\"" + varName
                    + "\",\"value\":\"" + varValue + "\",\"type\":\"" + varType + "\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());
    }

    private boolean instanceCompleted(UUID piId) throws Exception {
        MvcResult pi = mockMvc.perform(get("/process-instances/" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(pi.getResponse().getContentAsString()).hasNonNull("completedAt");
    }

    private String variableValue(UUID piId, String name) throws Exception {
        MvcResult vars = mockMvc.perform(get("/variables?processInstanceId=" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = mapper.readTree(vars.getResponse().getContentAsString()).get("data");
        for (JsonNode v : data) {
            if (name.equals(v.get("name").asText())) {
                return v.get("value").asText();
            }
        }
        return null;
    }

    private JsonNode incidentFor(UUID piId, String decisionId) throws Exception {
        MvcResult incidents = mockMvc.perform(get("/incidents?processInstanceId=" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = mapper.readTree(incidents.getResponse().getContentAsString()).get("data");
        for (JsonNode incident : data) {
            // WO-DIFF-8: broker-verbatim message (Raxon WO-028 live-Zeebe probe:
            // "Expected to evaluate decision 'X', but no decision found for id 'X'").
            if (incident.get("message").asText().contains("Expected to evaluate decision '" + decisionId + "'")) {
                return incident;
            }
        }
        throw new AssertionError("no incident mentioning '" + decisionId + "' in "
            + incidents.getResponse().getContentAsString());
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
}
