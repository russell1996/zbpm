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
 * WO-VM-6: element-bindings GET (list) + DELETE.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElementBindingsCrudIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String userToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        if (!userRepository.existsByUsername("crudUser")) {
            createUser("crudUser", "USER");
        }
        userToken = login("crudUser", "passr");
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

    private String setupProcess(String processKey) throws Exception {
        String bpmn = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
              id="Definitions_crud" targetNamespace="http://bpmn.io/schema/bpmn">
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

    // --- Criterion #1: GET returns bindings ---

    @Test
    void criterion1_listBindings_returnsBindings() throws Exception {
        String pdKey = "list-proc-" + UUID.randomUUID().toString().substring(0, 8);
        String formKey = "list-form-" + UUID.randomUUID().toString().substring(0, 8);
        setupProcess(pdKey);
        deployForm(formKey);
        createBinding(pdKey, "startEvent", formKey);

        mockMvc.perform(get("/process-definitions/" + pdKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].elementId").value("startEvent"));
    }

    // --- Criterion #2: DELETE removes binding, resolve no longer finds it ---

    @Test
    void criterion2_deleteBinding_removesBinding() throws Exception {
        String formKey = "del-form-" + UUID.randomUUID().toString().substring(0, 8);
        deployForm(formKey);
        String pdKey = "del-proc-" + UUID.randomUUID().toString().substring(0, 8);
        setupProcess(pdKey);
        createBinding(pdKey, "startEvent", formKey);

        // Verify binding exists via GET
        mockMvc.perform(get("/process-definitions/" + pdKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Delete binding
        mockMvc.perform(delete("/process-definitions/" + pdKey + "/element-bindings/startEvent")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Verify binding is gone via GET
        mockMvc.perform(get("/process-definitions/" + pdKey + "/element-bindings")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- Criterion #3: DELETE non-admin → 403 ---

    @Test
    void criterion3_deleteBinding_nonAdmin_returns403() throws Exception {
        String pdKey = "auth-proc-" + UUID.randomUUID().toString().substring(0, 8);
        setupProcess(pdKey);

        mockMvc.perform(delete("/process-definitions/" + pdKey + "/element-bindings/startEvent")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
