package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-REG-3: POST /auth/register over real HTTP (full filter chain, no token).
 * Engine semantics are proven in {@code SelfRegistrationIntegrationTests}; here the
 * route binding, the SUPER_ADMIN-payload guard (criterion 3 — the epic-critical one),
 * honest conflicts and the pending-login 401.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationEndpointIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerBody(String username, String email, String extraJson) {
        return "{\"username\":\"" + username + "\",\"password\":\"MyStr0ng!P@ssw0rd\","
            + "\"fullName\":\"Self\",\"email\":\"" + email + "\"" + extraJson + "}";
    }

    private UiUserEntity onlyUserWithEmail(String email) {
        List<UiUserEntity> found = userRepository.findAll().stream()
            .filter(u -> email.equals(u.getEmail()))
            .toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Test
    void register_happy_createsPendingInactiveUser() throws Exception {
        String email = "reghttp-" + tag() + "@x.com";

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("reghttp-" + tag(), email, "").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        UiUserEntity stored = onlyUserWithEmail(email);
        assertThat(stored.isActive()).isFalse();
        assertThat(stored.getRegistrationStatus()).isEqualTo("PENDING_EMAIL_VERIFICATION");
        assertThat(stored.getRole()).isEqualTo("USER");
        userRepository.deleteById(stored.getId());
    }

    @Test
    void register_superAdminInPayload_staysUser() throws Exception {
        // Criterion 3 — the most important test of the epic: no role field in the DTO,
        // so privilege escalation through this path is structurally impossible.
        String email = "regrole-" + tag() + "@x.com";

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("regrole-" + tag(), email, ",\"role\":\"SUPER_ADMIN\"").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        UiUserEntity stored = onlyUserWithEmail(email);
        assertThat(stored.getRole()).isEqualTo("USER");
        userRepository.deleteById(stored.getId());
    }

    @Test
    void register_duplicateEmail_conflictsWithoutSecondRow() throws Exception {
        String email = "regdupe-" + tag() + "@x.com";

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("regdupe-" + tag(), email, "").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("regdupe2-" + tag(), email.toUpperCase(), "").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().is4xxClientError());

        UiUserEntity stored = onlyUserWithEmail(email);
        userRepository.deleteById(stored.getId());
    }

    @Test
    void login_pendingUser_returns401() throws Exception {
        String suffix = tag();
        String email = "regpend-" + suffix + "@x.com";
        String username = "regpend-" + suffix;

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username, email, "").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        LoginDTO login = new LoginDTO();
        login.setUsername(username);
        login.setPassword("MyStr0ng!P@ssw0rd");
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized());

        userRepository.deleteById(onlyUserWithEmail(email).getId());
    }
}
