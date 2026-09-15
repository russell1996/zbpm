package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-VM-9a: schema-map + save element schema + carry-forward.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElementSchemaMapIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String userToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        if (!userRepository.existsByUsername("schemaMapUser")) {
            createUser("schemaMapUser", "USER");
        }
        userToken = login("schemaMapUser", "passr");
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

    private void createUser(String username, String role) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass" + username.charAt(username.length() - 1)));
        user.setFullName(username);
        user.setRole(role);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
    }

    private String deployBpmnWithStartEvent(String processKey) throws Exception {
        String bpmn = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
              id="Definitions_sm" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="%s" name="%s" isExecutable="true">
                <bpmn:startEvent id="startEvent">
                  <bpmn:outgoing>flow1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:endEvent id="endEvent">
                  <bpmn:incoming>flow1</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="flow1" sourceRef="startEvent" targetRef="endEvent" />
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(processKey, processKey);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private String deployBpmnWithUserTask(String processKey, String externalRef) throws Exception {
        String extAttr = externalRef != null
            ? "externalReference=\"" + externalRef + "\"" : "";
        String bpmn = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
              id="Definitions_sm2" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="%s" name="%s" isExecutable="true">
                <bpmn:startEvent id="startEvent">
                  <bpmn:outgoing>flow1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="userTask1">
                  <bpmn:extensionElements>
                    <zeebe:formDefinition %s />
                  </bpmn:extensionElements>
                  <bpmn:incoming>flow1</bpmn:incoming>
                  <bpmn:outgoing>flow2</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:endEvent id="endEvent">
                  <bpmn:incoming>flow2</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="flow1" sourceRef="startEvent" targetRef="userTask1" />
                <bpmn:sequenceFlow id="flow2" sourceRef="userTask1" targetRef="endEvent" />
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(processKey, processKey, extAttr);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    // --- Criterion #1: schema-map returns elements ---

    @Test
    void criterion1_schemaMap_returnsElements() throws Exception {
        String pdKey = "sm-proc-" + UUID.randomUUID().toString().substring(0, 8);
        deployBpmnWithStartEvent(pdKey);

        mockMvc.perform(get("/process-definitions/" + pdKey + "/schema-map")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processDefinitionKey").value(pdKey))
                .andExpect(jsonPath("$.elements").isArray())
                .andExpect(jsonPath("$.elements.length()").value(1))
                .andExpect(jsonPath("$.elements[0].elementId").value("startEvent"))
                .andExpect(jsonPath("$.elements[0].type").value("START_EVENT"));
    }

    // --- Criterion #2: shared flag ---

    @Test
    void criterion2_schemaMap_sharedFlag() throws Exception {
        // Deploy 2 processes with same start event binding to same artifact
        String pdKey1 = "shared1-" + UUID.randomUUID().toString().substring(0, 8);
        String pdKey2 = "shared2-" + UUID.randomUUID().toString().substring(0, 8);
        deployBpmnWithStartEvent(pdKey1);
        deployBpmnWithStartEvent(pdKey2);

        // Create bindings to same artifact
        String formKey = "shared-form-" + UUID.randomUUID().toString().substring(0, 8);
        deployForm(formKey);
        createBinding(pdKey1, "startEvent", formKey);
        createBinding(pdKey2, "startEvent", formKey);

        // Check shared flag
        mockMvc.perform(get("/process-definitions/" + pdKey1 + "/schema-map")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elements[0].artifactKey").value(formKey))
                .andExpect(jsonPath("$.elements[0].shared").value(true));
    }

    // --- Criterion #3: save on start event ---

    @Test
    void criterion3_saveStartEvent_createsBinding() throws Exception {
        String pdKey = "save-start-" + UUID.randomUUID().toString().substring(0, 8);
        deployBpmnWithStartEvent(pdKey);

        SaveElementSchemaDTO saveDto = new SaveElementSchemaDTO();
        saveDto.setKind("FORM_JS");
        saveDto.setSchema("{\"type\":\"form\",\"components\":[]}");
        mockMvc.perform(post("/process-definitions/" + pdKey + "/elements/startEvent/schema")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(saveDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementId").value("startEvent"))
                .andExpect(jsonPath("$.artifactKey").value(pdKey + ":startEvent"))
                .andExpect(jsonPath("$.kind").value("FORM_JS"))
                .andExpect(jsonPath("$.artifactVersion").value(1));
    }

    // --- Criterion #4: save on user-task with externalReference ---

    @Test
    void criterion4_saveUserTask_usesExternalReference() throws Exception {
        String pdKey = "save-ut-" + UUID.randomUUID().toString().substring(0, 8);
        deployBpmnWithUserTask(pdKey, "myArtifactKey");

        SaveElementSchemaDTO saveDto = new SaveElementSchemaDTO();
        saveDto.setKind("VARIABLE_SCHEMA");
        saveDto.setSchema("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"}");
        mockMvc.perform(post("/process-definitions/" + pdKey + "/elements/userTask1/schema")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(saveDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactKey").value("myArtifactKey"))
                .andExpect(jsonPath("$.kind").value("VARIABLE_SCHEMA"));
    }

    // --- Criterion #5: save on user-task without externalReference → 400 ---

    @Test
    void criterion5_saveUserTask_noExternalRef_returns400() throws Exception {
        String pdKey = "save-noext-" + UUID.randomUUID().toString().substring(0, 8);
        deployBpmnWithUserTask(pdKey, null);

        SaveElementSchemaDTO saveDto = new SaveElementSchemaDTO();
        saveDto.setKind("FORM_JS");
        saveDto.setSchema("{\"type\":\"form\",\"components\":[]}");
        mockMvc.perform(post("/process-definitions/" + pdKey + "/elements/userTask1/schema")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(saveDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // --- Criterion #6: carry-forward ---

    @Test
    void criterion6_carryForward_bindingsCopiedToNewVersion() throws Exception {
        String pdKey = "carry-" + UUID.randomUUID().toString().substring(0, 8);
        String formKey = "carry-form-" + UUID.randomUUID().toString().substring(0, 8);
        deployForm(formKey);

        // Deploy v1 with start event
        deployBpmnWithStartEvent(pdKey);
        createBinding(pdKey, "startEvent", formKey);

        // Verify v1 has binding
        mockMvc.perform(get("/process-definitions/" + pdKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Deploy v2 (same BPMN, but new version)
        deployBpmnWithStartEvent(pdKey);

        // Verify v2 has carry-forwarded binding
        mockMvc.perform(get("/process-definitions/" + pdKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].elementId").value("startEvent"))
                .andExpect(jsonPath("$[0].artifactKey").value(formKey));
    }

    // --- Criterion #7: non-admin → 403 ---

    @Test
    void criterion7_saveSchema_nonAdmin_returns403() throws Exception {
        String pdKey = "auth-sm-" + UUID.randomUUID().toString().substring(0, 8);
        deployBpmnWithStartEvent(pdKey);

        SaveElementSchemaDTO saveDto = new SaveElementSchemaDTO();
        saveDto.setKind("FORM_JS");
        saveDto.setSchema("{\"type\":\"form\",\"components\":[]}");
        mockMvc.perform(post("/process-definitions/" + pdKey + "/elements/startEvent/schema")
                        .header("Authorization", "Bearer " + userToken)
                        .content(mapper.writeValueAsString(saveDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private void deployForm(String key) throws Exception {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(key);
        dto.setKind("FORM_JS");
        dto.setSchema("{\"type\":\"form\",\"components\":[]}");
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    private void createBinding(String pdKey, String elementId, String artifactKey) throws Exception {
        CreateElementBindingDTO bindDto = new CreateElementBindingDTO();
        bindDto.setElementId(elementId);
        bindDto.setArtifactKey(artifactKey);
        mockMvc.perform(post("/process-definitions/" + pdKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(bindDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
