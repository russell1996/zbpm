package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.FormRepository;
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
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-VM-1: ElementArtifact kind + Runtime isolation.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElementArtifactKindIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private UUID processDefinitionId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        // Deploy form with required field + kind=FORM_JS
        String schema = "{\"type\":\"form\",\"components\":[{\"type\":\"textfield\",\"key\":\"name\",\"validate\":{\"required\":true}},{\"type\":\"number\",\"key\":\"amount\"}]}";
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(new DeployFormDTO() {{
                            setKey("orderForm");
                            setKind("FORM_JS");
                            setSchema(schema);
                        }}))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Deploy BPMN with start formKey="orderForm"
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/validation-start.bpmn")));
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        processDefinitionId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());
    }

    private String login(String username, String password) throws Exception {
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

    // --- Criterion #2: POST /forms without kind → 400 ---

    @Test
    void criterion2_deployForm_withoutKind_returns400() throws Exception {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey("no-kind-form");
        dto.setSchema("{\"type\":\"form\",\"components\":[]}");
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("kind"));
    }

    // --- Criterion #2: POST /forms with invalid kind → 400 ---

    @Test
    void criterion2_deployForm_invalidKind_returns400() throws Exception {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey("bad-kind-form");
        dto.setKind("INVALID_KIND");
        dto.setSchema("{\"type\":\"form\",\"components\":[]}");
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Invalid kind")));
    }

    // --- Criterion #2: POST /forms with kind → saves ---

    @Test
    void criterion2_deployForm_withKind_saves() throws Exception {
        String key = "kind-form-" + UUID.randomUUID().toString().substring(0, 8);
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(key);
        dto.setKind("FORM_JS");
        dto.setSchema("{\"type\":\"form\",\"components\":[]}");
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.kind").value("FORM_JS"))
                .andExpect(jsonPath("$.version").value(1));
    }

    // --- Criterion #2: VARIABLE_SCHEMA kind ---

    @Test
    void criterion2_deployForm_variableSchemaKind_saves() throws Exception {
        String key = "vs-form-" + UUID.randomUUID().toString().substring(0, 8);
        String jsonSchema = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}";
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(key);
        dto.setKind("VARIABLE_SCHEMA");
        dto.setSchema(jsonSchema);
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.kind").value("VARIABLE_SCHEMA"));
    }

    // --- Criterion #3: GET /forms/{key} returns kind ---

    @Test
    void criterion3_getForm_returnsKind() throws Exception {
        String key = "kind-get-" + UUID.randomUUID().toString().substring(0, 8);
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(key);
        dto.setKind("FORM_JS");
        dto.setSchema("{\"type\":\"form\",\"components\":[]}");
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/forms/" + key)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("FORM_JS"));
    }

    // --- Criterion #3: GET /forms list returns kind ---

    @Test
    void criterion3_listForms_returnsKind() throws Exception {
        mockMvc.perform(get("/forms")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind != null)]").exists());
    }

    // --- Criterion #4: FORM_JS artifact still validates (regression) ---

    @Test
    void criterion4_formJsArtifact_validatesVariables() throws Exception {
        // Missing required 'name' → 400
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(List.of(
            createVar("amount", "100", ProcessVariableType.LONG)
        ));
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // --- Criterion #5: VARIABLE_SCHEMA artifact does NOT validate (no-op) ---

    @Test
    void criterion5_variableSchemaArtifact_skipsFormValidation() throws Exception {
        // Deploy a VARIABLE_SCHEMA form
        String vsKey = "vs-validate-" + UUID.randomUUID().toString().substring(0, 8);
        String jsonSchema = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\",\"required\":[\"critical\"],\"properties\":{\"critical\":{\"type\":\"string\"}}}";
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(new DeployFormDTO() {{
                            setKey(vsKey);
                            setKind("VARIABLE_SCHEMA");
                            setSchema(jsonSchema);
                        }}))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Deploy BPMN with start formKey pointing to VARIABLE_SCHEMA artifact
        String bpmnVs = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
              id="Definitions_vs" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="vs-process" name="VS Process" isExecutable="true">
                <bpmn:startEvent id="startEvent">
                  <bpmn:extensionElements>
                    <zeebe:properties>
                      <zeebe:property name="formKey" value="%s" />
                    </zeebe:properties>
                  </bpmn:extensionElements>
                  <bpmn:outgoing>flow1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:endEvent id="endEvent">
                  <bpmn:incoming>flow1</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="flow1" sourceRef="startEvent" targetRef="endEvent" />
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(vsKey);

        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmnVs);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID vsPdId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());

        // Start process WITHOUT providing 'critical' — form validation is skipped (kind=VARIABLE_SCHEMA)
        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(vsPdId);
        startDto.setVariables(List.of(
            createVar("anything", "value", ProcessVariableType.STRING)
        ));
        // Must succeed — VARIABLE_SCHEMA → no-op validation
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(startDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    private ProcessVariable createVar(String name, String value, ProcessVariableType type) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(value);
        pv.setType(type);
        return pv;
    }
}
