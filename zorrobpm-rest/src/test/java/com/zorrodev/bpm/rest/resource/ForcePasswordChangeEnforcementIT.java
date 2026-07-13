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

    // --- Criterion #4: After password change → 200 on NON-exempt endpoint + flag reset in DB ---

    @Test
    @Order(3)
    void criterion4_afterPasswordChange_accessOpens() throws Exception {
        // Change password via service
        UpdateUiUserDTO updateDTO = new UpdateUiUserDTO();
        updateDTO.setPassword("new-secure-password-456");
        userService.update(forcePwUserId, updateDTO);

        // Verify flag is reset in DB
        UiUserEntity updatedUser = userRepository.findById(forcePwUserId).orElseThrow();
        assertThat(updatedUser.isForcePasswordChange()).isFalse();

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

        // Now /process-definitions (NON-exempt) should return 200
        mockMvc.perform(get("/process-definitions")
                        .header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    // --- Criterion #6: Proof-of-failure (V3) — real RED→GREEN ---
    // Create a SEPARATE user with forcePasswordChange=true for this test
    // (avoids mutating the admin user which other tests depend on).
    // GREEN: user with forcePasswordChange=true → 403
    // RED: change password → flag resets → 200

    @Test
    @Order(2)
    void criterion6_proofOfFailure_redThenGreen() throws Exception {
        // Create a separate user for POF
        UUID pofUserId = UUID.randomUUID();
        UiUserEntity pofUser = new UiUserEntity();
        pofUser.setId(pofUserId);
        pofUser.setUsername("pof-test-" + UUID.randomUUID());
        pofUser.setPasswordHash(passwordHasher.hash("pof-password-123"));
        pofUser.setFullName("POF Test User");
        pofUser.setRole("ADMIN");
        pofUser.setActive(true);
        pofUser.setForcePasswordChange(true);
        pofUser.setCreatedAt(Instant.now());
        pofUser.setUpdatedAt(Instant.now());
        userRepository.save(pofUser);

        LoginDTO pofLogin = new LoginDTO();
        pofLogin.setUsername(pofUser.getUsername());
        pofLogin.setPassword("pof-password-123");
        MvcResult pofResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(pofLogin))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        AuthResponse pofResp = mapper.readValue(pofResult.getResponse().getContentAsString(), AuthResponse.class);
        String pofToken = pofResp.getToken();

        // --- GREEN: forcePasswordChange=true → 403 ---
        mockMvc.perform(get("/process-definitions")
                        .header("Authorization", "Bearer " + pofToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        // --- RED: change password → flag resets → 200 ---
        UpdateUiUserDTO updateDTO = new UpdateUiUserDTO();
        updateDTO.setPassword("pof-new-password-456");
        userService.update(pofUserId, updateDTO);

        // Verify flag reset in DB
        UiUserEntity updatedUser = userRepository.findById(pofUserId).orElseThrow();
        assertThat(updatedUser.isForcePasswordChange()).isFalse();

        // Re-login
        LoginDTO relogin = new LoginDTO();
        relogin.setUsername(pofUser.getUsername());
        relogin.setPassword("pof-new-password-456");
        MvcResult reloginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(relogin))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        AuthResponse reloginResp = mapper.readValue(reloginResult.getResponse().getContentAsString(), AuthResponse.class);
        String newToken = reloginResp.getToken();

        // --- GREEN: after password change → 200 on NON-exempt endpoint ---
        mockMvc.perform(get("/process-definitions")
                        .header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    // --- Login endpoint is exempt from forcePasswordChange enforcement ---

    @Test
    @Order(4)
    void loginEndpoint_notBlockedByForcePasswordChange() throws Exception {
        // Login with the forcePwUser (forcePasswordChange=true) — login is exempt, should succeed
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername(userRepository.findById(forcePwUserId).orElseThrow().getUsername());
        loginDTO.setPassword("new-secure-password-456"); // password was changed in criterion4

        mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
