package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-FORM-1: Form storage + deploy + API.
 * Full-context IT tests on SpringBootTest.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormResourceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String userToken;

    @BeforeAll
    void setup() throws Exception {
        // Use existing bootstrap admin (admin/admin)
        adminToken = login("admin", "admin");

        // Create fresh user for non-admin tests
        if (!userRepository.existsByUsername("formUser")) {
            createUser("formUser", "USER");
        }
        userToken = login("formUser", "passr");
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

    private DeployFormDTO deployForm(String key, String schema) {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(key);
        dto.setKind("FORM_JS");
        dto.setSchema(schema);
        return dto;
    }

    // --- Criterion #1: POST /forms saves schema v1 ---

    @Test
    void criterion1_deployForm_savesVersion1() throws Exception {
        String key = "test-form-" + UUID.randomUUID().toString().substring(0, 8);
        String schema = "{\"type\":\"form\",\"components\":[],\"properties\":{}}";
        DeployFormDTO dto = deployForm(key, schema);

        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.schema").value(schema));
    }

    // --- Criterion #2: Repeat deploy → version=2 ---

    @Test
    void criterion2_duplicateDeploy_incrementsVersion() throws Exception {
        String key = "dup-form-" + UUID.randomUUID().toString().substring(0, 8);
        String schema = "{\"type\":\"form\",\"components\":[],\"properties\":{}}";

        // First deploy
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(deployForm(key, schema)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // Second deploy (same key)
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(deployForm(key, schema)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
    }

    // --- Criterion #3: GET /forms/{key} returns latest ---

    @Test
    void criterion3_getForm_returnsLatestVersion() throws Exception {
        String key = "latest-form-" + UUID.randomUUID().toString().substring(0, 8);
        String schemaV1 = "{\"type\":\"form\",\"version\":1}";
        String schemaV2 = "{\"type\":\"form\",\"version\":2}";

        // Deploy v1 then v2
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(deployForm(key, schemaV1)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(deployForm(key, schemaV2)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // GET returns v2
        mockMvc.perform(get("/forms/" + key)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.schema").value(schemaV2));
    }

    // --- Criterion #4: GET /forms/{unknown} → 404 ---

    @Test
    void criterion4_getForm_unknownKey_returns404() throws Exception {
        mockMvc.perform(get("/forms/nonexistent-form-" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // --- Criterion #5: Invalid JSON → 400 ---

    @Test
    void criterion5_invalidJsonSchema_returns400() throws Exception {
        DeployFormDTO dto = deployForm("bad-json-form", "{not valid json");
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // --- Criterion #6: Only SUPER_ADMIN can deploy ---

    @Test
    void criterion6_nonAdminDeploy_returns403() throws Exception {
        DeployFormDTO dto = deployForm("admin-only-form", "{}");
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + userToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- WO-FORM-2 tests ---

    private String startProcessAndGetTaskId() throws Exception {
        String bpmn = new String(
            Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn")));

        // Deploy BPMN
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID pdId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());

        // Start process with variables for prefill
        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(pdId);
        ProcessVariable amount = new ProcessVariable();
        amount.setName("amount"); amount.setValue("100"); amount.setType(ProcessVariableType.STRING);
        ProcessVariable currency = new ProcessVariable();
        currency.setName("currency"); currency.setValue("USD"); currency.setType(ProcessVariableType.STRING);
        startDto.setVariables(List.of(amount, currency));
        MvcResult startResult = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(startDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID instanceId = UUID.fromString(
            mapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        // Find the user task
        MvcResult taskResult = mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("processInstanceId", instanceId.toString()))
                .andExpect(status().isOk())
                .andReturn();
        var tasks = mapper.readTree(taskResult.getResponse().getContentAsString()).get("data");
        return tasks.get(0).get("id").asText();
    }

    // --- Criterion #1: Task with linked form → {type:embedded, schema, data} ---

    @Test
    void criterion1_taskWithLinkedForm_returnsEmbeddedWithSchema() throws Exception {
        // Deploy form schema
        String schema = "{\"type\":\"form\",\"components\":[{\"type\":\"number\",\"key\":\"amount\"}]}";
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(deployForm("orderForm", schema)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Start process and get task
        String taskId = startProcessAndGetTaskId();

        // GET /user-tasks/{id}/form
        mockMvc.perform(get("/user-tasks/" + taskId + "/form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("embedded"))
                .andExpect(jsonPath("$.schema").value(schema));
    }

    // --- Criterion #2: data contains current variables (prefill) ---

    @Test
    void criterion2_prefillData_containsCurrentVariables() throws Exception {
        String schema = "{\"type\":\"form\",\"components\":[]}";
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(deployForm("prefillForm", schema)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        String taskId = startProcessAndGetTaskId();

        mockMvc.perform(get("/user-tasks/" + taskId + "/form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("embedded"))
                .andExpect(jsonPath("$.data.amount").value("100"))
                .andExpect(jsonPath("$.data.currency").value("USD"));
    }

    // --- Criterion #3: Task with external-ref → {type:external, url} ---

    @Test
    void criterion3_externalReference_returnsExternalType() throws Exception {
        // Create a BPMN with externalReference (URL)
        String bpmnExternal = new String(
            Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn")))
            .replace("formKey=\"orderForm\"", "externalReference=\"https://example.com/form\"");

        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmnExternal);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID pdId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());

        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(pdId);
        MvcResult startResult = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(startDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID instanceId = UUID.fromString(
            mapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        MvcResult taskResult = mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("processInstanceId", instanceId.toString()))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = mapper.readTree(taskResult.getResponse().getContentAsString())
            .get("data").get(0).get("id").asText();

        mockMvc.perform(get("/user-tasks/" + taskId + "/form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("external"))
                .andExpect(jsonPath("$.url").value("https://example.com/form"));
    }

    // --- Criterion #4: Task without form → {type:none} ---

    @Test
    void criterion4_noForm_returnsNoneType() throws Exception {
        // Deploy a BPMN without formDefinition
        String bpmnNoForm = new String(
            Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn")))
            .replace("<zeebe:formDefinition formKey=\"orderForm\" />", "");

        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmnNoForm);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID pdId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());

        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(pdId);
        MvcResult startResult = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(startDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID instanceId = UUID.fromString(
            mapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        MvcResult taskResult = mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("processInstanceId", instanceId.toString()))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = mapper.readTree(taskResult.getResponse().getContentAsString())
            .get("data").get(0).get("id").asText();

        mockMvc.perform(get("/user-tasks/" + taskId + "/form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("none"));
    }

    // --- Criterion #6: Unknown task → 404 ---

    @Test
    void criterion6_unknownTask_returns404() throws Exception {
        mockMvc.perform(get("/user-tasks/" + UUID.randomUUID() + "/form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ==================== WO-AUD-3: getStartForm ====================

    @Test
    void aud3_startForm_deployedFormAndProcess_returns200WithSchema() throws Exception {
        // Deploy form with key matching BPMN's start formKey
        String formKey = "startFormAudit-" + UUID.randomUUID().toString().substring(0, 8);
        String schema = "{\"type\":\"form\",\"components\":[{\"type\":\"textfield\",\"key\":\"name\"}],\"properties\":{}}";
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(new DeployFormDTO() {{ setKey(formKey); setKind("FORM_JS"); setSchema(schema); }}))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Deploy BPMN with startFormKey
        String bpmn = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
              id="Definitions_aud3" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="audit3-process" name="Audit3 Process" isExecutable="true">
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
            """.formatted(formKey);

        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // GET /process-definitions/{key}/start-form → 200 + schema
        mockMvc.perform(get("/process-definitions/audit3-process/start-form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value(schema))
                .andExpect(jsonPath("$.type").value("embedded"));
    }

    @Test
    void aud3_startForm_unknownKey_returns404() throws Exception {
        mockMvc.perform(get("/process-definitions/nonexistent-key-999/start-form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}
