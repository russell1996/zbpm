package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-BE-6: @Valid on contract mutating endpoints — invalid body → 400 validation.
 * Happy-path regression is covered by existing FormResourceIntegrationTest
 * and VariableSchemaGeneratorIntegrationTest.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = adminLogin();
    }

    // --- POST /forms (DeployFormDTO) ---
    @Test
    void deployForm_emptyJson_returns400() throws Exception {
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void deployForm_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"key\":\"test-form\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void deployForm_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // --- POST /process-definitions/{key}/element-bindings (CreateElementBindingDTO) ---
    @Test
    void createElementBinding_emptyJson_returns400() throws Exception {
        mockMvc.perform(post("/process-definitions/dummy-process/element-bindings")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void createElementBinding_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/process-definitions/dummy-process/element-bindings")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"elementId\":\"elem\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // --- POST /process-definitions/{key}/elements/{elem}/schema (SaveElementSchemaDTO) ---
    @Test
    void saveElementSchema_emptyJson_returns400() throws Exception {
        mockMvc.perform(post("/process-definitions/dummy-process/elements/elem1/schema")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    // --- POST /variable-schemas/generate (GenerateSchemaDTO) ---
    @Test
    void generateSchema_emptyJson_returns400() throws Exception {
        mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    private String adminLogin() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
