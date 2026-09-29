package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ENG-10 (B-01): start-form validation must use the schema of the EXACT process definition
 * version being started, not always "latest by key".
 *
 * v1 references form A (requires field "fieldA"); v2 (deployed after v1, same process key)
 * references form B (requires field "fieldB"). Before the fix, starting explicitly by v1's
 * processDefinitionId still validated against v2/formB — accepting fieldB-shaped variables that
 * v1 never expects, and rejecting the fieldA-shaped variables v1 actually requires.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StartFormVersionIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private UUID v1Id;
    private UUID v2Id;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        deployForm("eng10FormA", "fieldA");
        deployForm("eng10FormB", "fieldB");

        v1Id = deployBpmn("eng10-start-v1.bpmn"); // version 1, formKey=eng10FormA (requires fieldA)
        v2Id = deployBpmn("eng10-start-v2.bpmn"); // version 2, formKey=eng10FormB (requires fieldB)
    }

    private void deployForm(String key, String requiredField) throws Exception {
        String schema = "{\"type\":\"form\",\"components\":[{\"type\":\"textfield\",\"key\":\""
            + requiredField + "\",\"validate\":{\"required\":true}}]}";
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

    private UUID deployBpmn(String fixtureFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fixtureFile));
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult result = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(addDto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
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

    private ProcessVariable var(String name, String value) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(value);
        pv.setType(ProcessVariableType.STRING);
        return pv;
    }

    // --- Criterion #1: explicit v1 id + v1-shaped variables (fieldA) -> 200 ---

    @Test
    void criterion1_explicitOlderVersion_validFieldA_returns200() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(v1Id);
        dto.setVariables(List.of(var("fieldA", "hello")));
        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }

    // --- Criterion #2: explicit v1 id + v2-shaped variables (fieldB, missing fieldA) -> 400,
    //     validated against v1's OWN form (formA), not v2's (formB) ---

    @Test
    void criterion2_explicitOlderVersion_v2ShapedVariables_rejectedAgainstOwnForm() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(v1Id);
        dto.setVariables(List.of(var("fieldB", "hello"))); // satisfies v2's form, NOT v1's
        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("fieldA")));
    }

    // --- Criterion #3 (regression): start by key only (no explicit id) -> resolves to latest
    //     (v2) and validates against v2's form, exactly as before ---

    @Test
    void criterion3_byKeyOnly_defaultsToLatestVersion_validatesAgainstLatestForm() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("eng10-process");
        dto.setVariables(List.of(var("fieldB", "hello"))); // v2 (latest) requires fieldB
        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }

    // --- Criterion #3b (regression): explicit LATEST id (v2) + v2-shaped variables -> 200 ---

    @Test
    void criterion3b_explicitLatestVersion_validFieldB_returns200() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(v2Id);
        dto.setVariables(List.of(var("fieldB", "hello")));
        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }
}
