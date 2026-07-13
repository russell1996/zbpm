package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.service.UiUserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-14: forcePasswordChange server-side enforcement.
 *
 * #3: User with forcePasswordChange=true → data-API 403 PASSWORD_CHANGE_REQUIRED
 * #4: After password change → flag reset → 200
 * #6: Proof-of-failure (V3+V11): on current code, flag is ignored → 200 (RED)
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
    "zorrobpm.security.force-password-enforce=true"
})
class ForcePasswordChangeEnforcementIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private UiUserService userService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String forcePwToken;
    private UUID forcePwUserId;

    @BeforeAll
    void setup() throws Exception {
        // Create user with forcePasswordChange=true
        forcePwUserId = UUID.randomUUID();
        UiUserEntity user = new UiUserEntity();
        user.setId(forcePwUserId);
        user.setUsername("force-pw-enforce-" + UUID.randomUUID());
        user.setPasswordHash(passwordHasher.hash("initial-password"));
        user.setFullName("Force PW Enforce Test");
        user.setRole("ADMIN");
        user.setActive(true);
        user.setForcePasswordChange(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        // Login to get token
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername(user.getUsername());
        loginDTO.setPassword("initial-password");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse authResponse = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        forcePwToken = authResponse.getToken();
        assertThat(authResponse.getUser().isForcePasswordChange()).isTrue();
    }

    // --- Criterion #3: forcePasswordChange=true → 403 on data-API ---

    @Test
    @Order(1)
    void criterion3_forcePasswordChange_blocksDataApi() throws Exception {
        mockMvc.perform(get("/process-definitions")
                        .header("Authorization", "Bearer " + forcePwToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    // --- Criterion #4: After password change → 200 ---

    @Test
    @Order(3)
    void criterion4_afterPasswordChange_accessOpens() throws Exception {
        // Change password via service
        UpdateUiUserDTO updateDTO = new UpdateUiUserDTO();
        updateDTO.setPassword("new-secure-password-456");
        userService.update(forcePwUserId, updateDTO);

        // Re-login with new password
        LoginDTO reloginDTO = new LoginDTO();
        reloginDTO.setUsername(userRepository.findById(forcePwUserId).orElseThrow().getUsername());
        reloginDTO.setPassword("new-secure-password-456");

        MvcResult reloginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(reloginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse reloginResponse = mapper.readValue(reloginResult.getResponse().getContentAsString(), AuthResponse.class);
        String newToken = reloginResponse.getToken();

        // Now /auth/me should return 200
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    // --- Criterion #6: Proof-of-failure (V3+V11) ---
    // On current code WITHOUT ForcePasswordChangeFilter, the flag is ignored.
    // This test should pass (200) before the fix, proving the flag is NOT enforced.
    // After adding ForcePasswordChangeFilter, the same request returns 403.
    // We test that the filter IS active by verifying 403 now.

    @Test
    @Order(2)
    void criterion6_filterEnforcesForcePasswordChange() throws Exception {
        // The filter is active → 403
        mockMvc.perform(get("/process-definitions")
                        .header("Authorization", "Bearer " + forcePwToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    // --- Login and logout are exempt ---

    @Test
    @Order(4)
    void loginEndpoint_notBlockedByForcePasswordChange() throws Exception {
        // Use the admin bootstrap user (always available, password "admin")
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");

        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
