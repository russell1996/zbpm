package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-API-4 (Finding #2, High): the batch deploy path ({@code POST /deployments})
 * bypassed the WO-SEC-62 size cap — {@code DeployBatchDTO.resources} had no
 * {@code @Valid} (no cascade into list elements) and
 * {@code DeploymentItemDTO.content} had no constraint at all.
 *
 * <p>Every test goes through the HTTP layer (MockMvc, full context, V11):
 * the 413/400 below is produced by real MVC Bean Validation + the real
 * {@code GlobalExceptionHandler}, not by calling the service directly.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchDeployValidationIT {

    @Autowired MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    private static final String BPMN_TEMPLATE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_KEY" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="KEY" name="KEY" isExecutable="true">
            <bpmn:startEvent id="startEvent" name="startEvent">
              <bpmn:outgoing>flow1</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:sequenceFlow id="flow1" name="flow1" sourceRef="startEvent" targetRef="endEvent" />
            <bpmn:endEvent id="endEvent" name="endEvent">
              <bpmn:incoming>flow1</bpmn:incoming>
            </bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private static final String DMN_SMALL = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="Definitions_api4" name="api4" namespace="http://camunda.org/schema/1.0/dmn">
          <decision id="api4-discount" name="Api4Discount">
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

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    // ==================== Criterion #1: oversized batch item → 413, same shape as single ====================

    @Test
    void criterion1_oversizedBatchItem_rejected413_sameShapeAsSingle() throws Exception {
        String oversized = "x".repeat(AddProcessDefinitionDTO.MAX_BPMN_LENGTH + 1);
        String body = "{\"resources\":[{\"type\":\"DMN\",\"content\":"
            + mapper.writeValueAsString(oversized) + "}]}";

        MvcResult result = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("BPMN_TOO_LARGE"))
            .andExpect(jsonPath("$.params.maxLength").value(AddProcessDefinitionDTO.MAX_BPMN_LENGTH))
            .andExpect(jsonPath("$.params.actualLength").value(AddProcessDefinitionDTO.MAX_BPMN_LENGTH + 1))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("5 MB");
    }

    @Test
    void criterion1_singleBpmnPath_still413_regressionAnchor() throws Exception {
        // Anchor: the WO-SEC-62 single path must keep answering the same 413 shape
        // after the handler condition was widened for the nested batch field.
        String oversized = "y".repeat(AddProcessDefinitionDTO.MAX_BPMN_LENGTH + 1);
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(oversized);

        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("BPMN_TOO_LARGE"))
            .andExpect(jsonPath("$.params.maxLength").value(AddProcessDefinitionDTO.MAX_BPMN_LENGTH));
    }

    // ==================== Criterion #2: empty content → 400, cascade really fires ====================

    @Test
    void criterion2_emptyContent_rejected400_cascadeProof() throws Exception {
        // Empty string: @Size alone would pass it — only the cascaded @NotBlank
        // turns it into a 400. The field path proves the cascade (not the service).
        MvcResult result = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"resources\":[{\"type\":\"BPMN\",\"content\":\"\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andReturn();
        JsonNode errors = mapper.readTree(result.getResponse().getContentAsString()).get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field").asText()).isEqualTo("resources[0].content");
    }

    @Test
    void criterion2_nullContent_rejected400_cascadeProof() throws Exception {
        // Absent content (null): same cascade, same 400 shape.
        MvcResult result = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"resources\":[{\"type\":\"BPMN\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andReturn();
        JsonNode errors = mapper.readTree(result.getResponse().getContentAsString()).get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field").asText()).isEqualTo("resources[0].content");
    }

    // ==================== Guard: a valid batch still deploys ====================

    @Test
    void guard_validBatch_stillDeploys201() throws Exception {
        String key = "api4-ok-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"resources\":["
            + "{\"type\":\"BPMN\",\"content\":" + mapper.writeValueAsString(BPMN_TEMPLATE.replace("KEY", key)) + "},"
            + "{\"type\":\"DMN\",\"content\":" + mapper.writeValueAsString(DMN_SMALL) + "}]}";

        MvcResult deployed = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode tree = mapper.readTree(deployed.getResponse().getContentAsString());
        assertThat(tree.get("processes").get(0).get("key").asText()).isEqualTo(key);
    }

    private String login(String username, String password) throws Exception {
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
