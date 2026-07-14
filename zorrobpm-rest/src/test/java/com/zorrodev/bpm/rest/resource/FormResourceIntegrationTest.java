package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
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

import java.time.Instant;
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
                .andExpect(status().isOk())
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // Second deploy (same key)
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(deployForm(key, schema)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
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
                .andExpect(status().isOk());
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(deployForm(key, schemaV2)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // GET returns v2
        mockMvc.perform(get("/forms/" + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.schema").value(schemaV2));
    }

    // --- Criterion #4: GET /forms/{unknown} → 404 ---

    @Test
    void criterion4_getForm_unknownKey_returns404() throws Exception {
        mockMvc.perform(get("/forms/nonexistent-form-" + UUID.randomUUID()))
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
}
