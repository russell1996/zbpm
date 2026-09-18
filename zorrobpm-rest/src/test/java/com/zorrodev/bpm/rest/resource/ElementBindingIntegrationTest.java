package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-VM-3: Start-event binding + version pinning.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElementBindingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private ElementArtifactBindingRepository bindingRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String userToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        if (!userRepository.existsByUsername("bindingUser")) {
            createUser("bindingUser", "USER");
        }
        userToken = login("bindingUser", "passr");
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

    private void deployForm(String key, String schema) throws Exception {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(key);
        dto.setKind("FORM_JS");
        dto.setSchema(schema);
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    // --- Criterion #2: POST …/element-bindings creates binding with pinned version ---

    @Test
    void criterion2_createBinding_pinsArtifactVersion() throws Exception {
        // Deploy form v1
        String formKey = "bind-test-" + UUID.randomUUID().toString().substring(0, 8);
        deployForm(formKey, "{\"type\":\"form\",\"components\":[]}");

        // Deploy BPMN with startEvent
        String bpmn = createBpmn("bind-process-" + UUID.randomUUID().toString().substring(0, 8), "startEvent", null);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        String pdKey = mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText();

        // Create binding
        CreateElementBindingDTO bindDto = new CreateElementBindingDTO();
        bindDto.setElementId("startEvent");
        bindDto.setArtifactKey(formKey);
        MvcResult bindResult = mockMvc.perform(post("/process-definitions/" + pdKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(bindDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactKey").value(formKey))
                .andExpect(jsonPath("$.artifactVersion").value(1))
                .andExpect(jsonPath("$.elementId").value("startEvent"))
                .andReturn();
    }

    // --- Criterion #3: Resolve start form via element binding ---

    @Test
    void criterion3_resolveStartForm_usesBinding() throws Exception {
        String formKey = "resolve-bind-" + UUID.randomUUID().toString().substring(0, 8);
        String schema = "{\"type\":\"form\",\"components\":[{\"type\":\"textfield\",\"key\":\"name\"}]}";
        deployForm(formKey, schema);

        String processKey = "resolve-proc-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = createBpmn(processKey, "startEvent", null);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Create binding
        CreateElementBindingDTO bindDto = new CreateElementBindingDTO();
        bindDto.setElementId("startEvent");
        bindDto.setArtifactKey(formKey);
        mockMvc.perform(post("/process-definitions/" + processKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(bindDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Resolve start form — should return the bound artifact
        mockMvc.perform(get("/process-definitions/" + processKey + "/start-form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("embedded"))
                .andExpect(jsonPath("$.schema").value(schema));
    }

    // --- Criterion #4: Pinned version — new deploy (v2) doesn't change resolve for old PD version ---

    @Test
    void criterion4_pinnedVersion_oldPD_resolvesOldArtifact() throws Exception {
        String formKey = "pin-test-" + UUID.randomUUID().toString().substring(0, 8);
        String schemaV1 = "{\"type\":\"form\",\"components\":[{\"type\":\"number\",\"key\":\"amount\"}]}";
        String schemaV2 = "{\"type\":\"form\",\"components\":[{\"type\":\"textfield\",\"key\":\"name\"}]}";

        // Deploy form v1
        deployForm(formKey, schemaV1);

        // Deploy BPMN v1
        String processKey = "pin-proc-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = createBpmn(processKey, "startEvent", null);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Create binding (pins to v1)
        CreateElementBindingDTO bindDto = new CreateElementBindingDTO();
        bindDto.setElementId("startEvent");
        bindDto.setArtifactKey(formKey);
        mockMvc.perform(post("/process-definitions/" + processKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(bindDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactVersion").value(1));

        // Deploy form v2 (different schema)
        deployForm(formKey, schemaV2);

        // Resolve start form — should still return v1 schema (pinned)
        mockMvc.perform(get("/process-definitions/" + processKey + "/start-form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value(schemaV1));
    }

    // --- Criterion #6: Only SUPER_ADMIN can create binding ---

    @Test
    void criterion6_nonAdmin_createBinding_returns403() throws Exception {
        CreateElementBindingDTO bindDto = new CreateElementBindingDTO();
        bindDto.setElementId("startEvent");
        bindDto.setArtifactKey("any-form");
        mockMvc.perform(post("/process-definitions/any-key/element-bindings")
                        .header("Authorization", "Bearer " + userToken)
                        .content(mapper.writeValueAsString(bindDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- Criterion #5: Existing startFormKey fallback ---

    @Test
    void criterion5_existingStartFormKey_stillResolves() throws Exception {
        String formKey = "fallback-" + UUID.randomUUID().toString().substring(0, 8);
        String schema = "{\"type\":\"form\",\"components\":[{\"type\":\"number\",\"key\":\"qty\"}]}";
        deployForm(formKey, schema);

        // Deploy BPMN with startFormKey (old style)
        String bpmn = createBpmn("fallback-proc-" + UUID.randomUUID().toString().substring(0, 8), "startEvent", formKey);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        String pdKey = mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText();

        // No binding created — should fallback to startFormKey
        mockMvc.perform(get("/process-definitions/" + pdKey + "/start-form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("embedded"))
                .andExpect(jsonPath("$.schema").value(schema));
    }

    private String createBpmn(String processId, String startEventId, String formKey) {
        String formKeyAttr = formKey != null
            ? """
              <bpmn:extensionElements>
                <zeebe:properties>
                  <zeebe:property name="formKey" value="%s" />
                </zeebe:properties>
              </bpmn:extensionElements>""".formatted(formKey)
            : "";
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
              id="Definitions_%s" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="%s" name="%s" isExecutable="true">
                <bpmn:startEvent id="%s">
                  %s
                  <bpmn:outgoing>flow1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:endEvent id="endEvent">
                  <bpmn:incoming>flow1</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="flow1" sourceRef="%s" targetRef="endEvent" />
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(processId, processId, processId, startEventId, formKeyAttr, startEventId);
    }
}
