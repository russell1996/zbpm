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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-VM-5: JSON Schema validation for VARIABLE_SCHEMA deploy.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonSchemaValidationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
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

    private DeployFormDTO vsDto(String key, String schema) {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(key);
        dto.setKind("VARIABLE_SCHEMA");
        dto.setSchema(schema);
        return dto;
    }

    // --- Criterion #1: VARIABLE_SCHEMA with broken JSON → 400 ---

    @Test
    void criterion1_brokenJson_returns400() throws Exception {
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(vsDto("vs-broken", "{not valid json")))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid JSON")));
    }

    // --- Criterion #2: VARIABLE_SCHEMA with invalid JSON Schema → 400 ---

    @Test
    void criterion2_invalidSchema_returns400() throws Exception {
        // Valid JSON but invalid JSON Schema — $schema points to non-existent draft
        String invalidSchema = "{\"$schema\": \"https://json-schema.org/draft/9999-99/schema\", \"type\": \"object\"}";
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(vsDto("vs-invalid", invalidSchema)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid JSON Schema")));
    }

    // --- Criterion #3: VARIABLE_SCHEMA valid → 200 ---

    @Test
    void criterion3_validSchema_returns200() throws Exception {
        String validSchema = "{\"$schema\": \"https://json-schema.org/draft/2020-12/schema\", \"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}";
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(vsDto("vs-valid-" + UUID.randomUUID().toString().substring(0, 8), validSchema)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("VARIABLE_SCHEMA"));
    }

    // --- Criterion #4: FORM_JS not affected ---

    @Test
    void criterion4_formJs_stillWorks() throws Exception {
        String schema = "{\"type\":\"form\",\"components\":[{\"type\":\"textfield\",\"key\":\"name\"}]}";
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey("fjs-test-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setKind("FORM_JS");
        dto.setSchema(schema);
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("FORM_JS"));
    }
}
