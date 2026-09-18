package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.service.UiUserService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-2 tests for:
 *  - #5: first login admin/admin → forcePasswordChange: true
 *  - #6: after password change on separate user → forcePasswordChange: false
 *  - #3: CORS — preflight with forbidden Origin → no Access-Control-Allow-Origin
 *  - #4: CORS — preflight with allowed Origin → correct headers
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityHardeningIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UiUserRepository userRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private UiUserService userService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse authResponse = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        adminToken = authResponse.getToken();
    }

    // --- Criterion #5: first login admin/admin → forcePasswordChange: true ---

    @Test
    void criterion5_firstLogin_forcePasswordChangeTrue() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        UiUser user = mapper.readValue(result.getResponse().getContentAsString(), UiUser.class);
        assertThat(user.isForcePasswordChange()).isTrue();
    }

    // --- Criterion #6: after password change on SEPARATE user → forcePasswordChange: false ---

    @Test
    void criterion6_afterPasswordChange_forcePasswordChangeFalse() throws Exception {
        // Create a separate user with forcePasswordChange=true
        UiUserEntity testUser = new UiUserEntity();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("force-pw-test-" + UUID.randomUUID());
        testUser.setPasswordHash(passwordHasher.hash("initial-password"));
        testUser.setFullName("Force PW Test User");
        testUser.setRole("ADMIN");
        testUser.setActive(true);
        testUser.setForcePasswordChange(true);
        testUser.setCreatedAt(java.time.Instant.now());
        testUser.setUpdatedAt(java.time.Instant.now());
        userRepository.save(testUser);

        // Verify forcePasswordChange is true via login
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername(testUser.getUsername());
        loginDTO.setPassword("initial-password");

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse authResponse = mapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);
        assertThat(authResponse.getUser().isForcePasswordChange()).isTrue();

        // Change password via service
        UpdateUiUserDTO updateDTO = new UpdateUiUserDTO();
        updateDTO.setPassword("new-secure-password-123");
        userService.update(testUser.getId(), updateDTO);

        // Re-login with new password
        LoginDTO reloginDTO = new LoginDTO();
        reloginDTO.setUsername(testUser.getUsername());
        reloginDTO.setPassword("new-secure-password-123");

        MvcResult reloginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(reloginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse reloginResponse = mapper.readValue(reloginResult.getResponse().getContentAsString(), AuthResponse.class);
        assertThat(reloginResponse.getUser().isForcePasswordChange()).isFalse();
    }

    // --- WO-SEC-14 Criterion #5: admin role is SUPER_ADMIN after bootstrap ---

    @Test
    void criterion5_adminRole_isSuperAdmin() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        UiUser user = mapper.readValue(result.getResponse().getContentAsString(), UiUser.class);
        assertThat(user.getRole()).isEqualTo("SUPER_ADMIN");
    }

    // --- Criterion #3: CORS — preflight with forbidden Origin → no CORS headers ---

    @Test
    void criterion3_corsForbiddenOrigin_noAllowHeader() throws Exception {
        mockMvc.perform(options("/process-instances")
                        .header("Origin", "http://evil.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // --- Criterion #4: CORS — preflight with allowed Origin → correct headers ---

    @Test
    void criterion4_corsDev_localhostAllowed() throws Exception {
        mockMvc.perform(options("/process-instances")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().exists("Access-Control-Allow-Credentials"));
    }
}
