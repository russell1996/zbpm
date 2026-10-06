package com.zorrodev.bpm.rest.resource;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ENG-34 (CR-08, критерий 7) — the refusal as an API CLIENT sees it.
 *
 * <p>The engine-level tests
 * ({@code UnsupportedConstructDeployIntegrationTests}) drive
 * {@code ProcessDefinitionService.addProcessDefinition} and assert the {@code ApiException}. That is
 * the production funnel — {@code DeploymentServiceImpl:77} calls exactly this method, and
 * {@code GlobalExceptionHandler:175} turns any {@code ApiException} into its status plus
 * {@code {code, params, message}} — but it stops one layer short of the wire. This class closes that
 * last hop: a real {@code POST /deployments} with a real admin token, asserting HTTP 400 and the
 * stable code + element ids in the JSON body. Without it, nothing proves that the author of a model
 * actually SEES which construct was rejected (the failure mode this WO closes is silence).
 *
 * <p>Mirrors {@link BatchDeployValidationIT} (same full-context MockMvc setup, same login).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnsupportedConstructDeployRejectionIT {

    @Autowired MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    /** A perfectly ordinary model — the only thing wrong with it is the loop on {@code loopTask}. */
    private static final String STANDARD_LOOP = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_rest" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="KEY" name="KEY" isExecutable="true">
            <bpmn:startEvent id="startEvent" name="startEvent">
              <bpmn:outgoing>f1</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" name="f1" sourceRef="startEvent" targetRef="loopTask" />
            <bpmn:scriptTask id="loopTask" name="loopTask" scriptFormat="feel" resultVariable="x">
              <bpmn:incoming>f1</bpmn:incoming>
              <bpmn:outgoing>f2</bpmn:outgoing>
              <bpmn:script>1</bpmn:script>
              <bpmn:standardLoopCharacteristics testBefore="false" loopMaximum="3" />
            </bpmn:scriptTask>
            <bpmn:sequenceFlow id="f2" name="f2" sourceRef="loopTask" targetRef="endEvent" />
            <bpmn:endEvent id="endEvent" name="endEvent">
              <bpmn:incoming>f2</bpmn:incoming>
            </bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    @Test
    void unsupportedConstruct_isRejected400_withStableCodeAndElementIds() throws Exception {
        String key = "eng34-rest-" + UUID.randomUUID().toString().substring(0, 8);

        MvcResult result = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"resources\":[{\"type\":\"BPMN\",\"content\":"
                    + mapper.writeValueAsString(STANDARD_LOOP.replace("KEY", key)) + "}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            // the stable code a client keys its message on — same field SERVICE_TASK_MISSING_JOB uses
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_STANDARD_LOOP"))
            // and the ids of the offending elements, so the author knows what to edit
            .andExpect(jsonPath("$.params.elementIds[0]").value("loopTask"))
            .andReturn();

        // the message names the CONSTRUCT, not "parse error" — the whole point of the WO
        assertThat(result.getResponse().getContentAsString())
            .contains("standardLoopCharacteristics")
            .contains("loopTask");
    }

    @Test
    void theSameModelWithoutTheLoop_stillDeploys201() throws Exception {
        // the guard: the refusal is about the construct, not about this endpoint or this model shape
        String key = "eng34-rest-ok-" + UUID.randomUUID().toString().substring(0, 8);
        String withoutLoop = STANDARD_LOOP.replace(
            "<bpmn:standardLoopCharacteristics testBefore=\"false\" loopMaximum=\"3\" />", "");

        MvcResult deployed = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"resources\":[{\"type\":\"BPMN\",\"content\":"
                    + mapper.writeValueAsString(withoutLoop.replace("KEY", key)) + "}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        assertThat(deployed.getResponse().getContentAsString()).contains(key);
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