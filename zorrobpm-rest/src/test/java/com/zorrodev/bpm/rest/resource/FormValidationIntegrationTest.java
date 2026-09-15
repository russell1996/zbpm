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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-FORM-5: Server-side form validation on start/complete.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormValidationIntegrationTest {

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

        // Deploy form with required field — key matches BPMN's formKey="orderForm"
        String schema = "{\"type\":\"form\",\"components\":[{\"type\":\"textfield\",\"key\":\"name\",\"validate\":{\"required\":true}},{\"type\":\"number\",\"key\":\"amount\"}]}";
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(new DeployFormDTO() {{ setKey("orderForm"); setKind("FORM_JS"); setSchema(schema); }}))
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

    // --- Criterion #1: Valid data → 200 ---

    @Test
    void criterion1_validData_returns200() throws Exception {
        // Verify startFormKey is stored on the process definition
        mockMvc.perform(get("/process-definitions/" + processDefinitionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startFormKey").value("orderForm"));

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(List.of(
            createVar("name", "Alice", ProcessVariableType.STRING),
            createVar("amount", "100", ProcessVariableType.LONG)
        ));
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    // --- Criterion #2: Missing required → 400 VALIDATION_ERROR ---

    @Test
    void criterion2_missingRequired_returns400() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        // Send only 'amount', missing required 'name'
        dto.setVariables(List.of(
            createVar("amount", "100", ProcessVariableType.LONG)
        ));
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    // --- Criterion #5: No form → no validation (200) ---

    @Test
    void criterion5_noForm_skipsValidation() throws Exception {
        // Deploy a BPMN without formKey
        String bpmnNoForm = new String(Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn")))
            .replace("formKey=\"orderForm\"", "");
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

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of(
            createVar("anything", "value", ProcessVariableType.STRING)
        ));
        // No form → no validation → 200 even though required fields don't exist
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    // --- Fail-open fix: variables=null with required field → 400 ---

    @Test
    void nullVariables_withRequiredField_returns400() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(null);
        // null variables → treated as empty → required field "name" missing → 400
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    private ProcessVariable createVar(String name, String value, ProcessVariableType type) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(value);
        pv.setType(type);
        return pv;
    }
}
