package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordTokenIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired StubMailSender stubMail;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String regularUserToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");

        UiUserEntity regular = new UiUserEntity();
        regular.setId(UUID.randomUUID());
        regular.setUsername("acl18-regular");
        regular.setPasswordHash(passwordHasher.hash("RegPassw0rd!"));
        regular.setFullName("Regular");
        regular.setRole("USER");
        regular.setActive(true);
        regular.setCreatedAt(Instant.now());
        regular.setUpdatedAt(Instant.now());
        userRepository.save(regular);

        regularUserToken = loginAndGetToken("acl18-regular", "RegPassw0rd!");
    }

    @Test
    void criterion4_invitedUserCannotLoginUntilAccepting() throws Exception {
        String username = "acl18-invitee-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@corp.kz";

        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(username);
        dto.setEmail(email);
        dto.setRole("USER");
        dto.setActive(true);
        dto.setCreationMode("INVITE");

        String id = mockMvc.perform(post("/users").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(dto)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Map<String, Object> created = mapper.readValue(id, Map.class);
        UUID userId = UUID.fromString(String.valueOf(created.get("id")));

        // Before accepting — login fails (no usable password).
        LoginDTO beforeLogin = new LoginDTO();
        beforeLogin.setUsername(username);
        beforeLogin.setPassword("whatever");
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(beforeLogin)))
                .andExpect(status().isUnauthorized());

        // Extract the raw token from the invitation email and accept it.
        String token = extractTokenFromLastMail();
        mockMvc.perform(post("/auth/accept-invitation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("token", token, "password", "AcceptedPass1!"))))
                .andExpect(status().isOk());

        // After accepting — login succeeds.
        LoginDTO afterLogin = new LoginDTO();
        afterLogin.setUsername(username);
        afterLogin.setPassword("AcceptedPass1!");
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(afterLogin)))
                .andExpect(status().isOk());

        userRepository.deleteById(userId);
    }

    @Test
    void criterion6_consumedTokenCannotBeReused() throws Exception {
        String username = "acl18-reuse-" + UUID.randomUUID().toString().substring(0, 8);
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(username);
        dto.setEmail(username + "@corp.kz");
        dto.setRole("USER");
        dto.setActive(true);
        dto.setCreationMode("INVITE");
        mockMvc.perform(post("/users").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        String token = extractTokenFromLastMail();
        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("token", token, "password", "FirstPassw0rd!"))))
                .andExpect(status().isOk());
        // Second use of the same token → rejected.
        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("token", token, "password", "SecondPassw0rd!"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void criterion11_adminResetWithoutEmailRejected() throws Exception {
        // An account that has no email cannot receive a reset link — the request must fail clearly.
        UiUserEntity target = new UiUserEntity();
        target.setId(UUID.randomUUID());
        target.setUsername("acl18-noemail");
        target.setRole("USER");
        target.setActive(true);
        target.setPasswordHash(passwordHasher.hash("InitPassw0rd!"));
        target.setCreatedAt(Instant.now());
        target.setUpdatedAt(Instant.now());
        userRepository.save(target);

        stubMail.clear();
        mockMvc.perform(post("/users/" + target.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());

        // No reset email must have been dispatched to a non-existent address.
        assertThat(stubMail.getSent()).isEmpty();

        userRepository.deleteById(target.getId());
    }

    @Test
    void criterion12_forgotPasswordIsEnumerationSafe() throws Exception {
        // Known and unknown emails must produce an identical (200) response.
        mockMvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", "acl18-regular@corp.kz"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", "definitely-not-here@corp.kz"))))
                .andExpect(status().isOk());
    }

    @Test
    void criterion1_passwordCreationWorks() throws Exception {
        String username = "acl18-pwd-" + UUID.randomUUID().toString().substring(0, 8);
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(username);
        dto.setEmail(username + "@corp.kz");
        dto.setRole("USER");
        dto.setActive(true);
        dto.setPassword("PwdPassw0rd!");
        dto.setCreationMode("PASSWORD");

        mockMvc.perform(post("/users").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        // PASSWORD path → usable password immediately, no invitation link needed.
        LoginDTO login = new LoginDTO();
        login.setUsername(username);
        login.setPassword("PwdPassw0rd!");
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(login)))
                .andExpect(status().isOk());
    }

    @Test
    void criterion10_adminResetRequiresSuperAdmin() throws Exception {
        // A regular USER is rejected.
        mockMvc.perform(post("/users/" + UUID.randomUUID() + "/reset-password")
                        .header("Authorization", "Bearer " + regularUserToken))
                .andExpect(status().isForbidden());

        // Admin can issue a reset (email captured by the stub).
        stubMail.clear();
        UiUserEntity target = new UiUserEntity();
        target.setId(UUID.randomUUID());
        target.setUsername("acl18-target");
        target.setEmail("acl18-target@corp.kz");
        target.setRole("USER");
        target.setActive(true);
        target.setPasswordHash(passwordHasher.hash("InitPassw0rd!"));
        target.setCreatedAt(Instant.now());
        target.setUpdatedAt(Instant.now());
        userRepository.save(target);

        mockMvc.perform(post("/users/" + target.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertThat(stubMail.getSent()).isNotEmpty();
        assertThat(stubMail.getSent().get(0).to()).isEqualTo("acl18-target@corp.kz");

        userRepository.deleteById(target.getId());
    }

    private String extractTokenFromLastMail() {
        String body = stubMail.getSent().get(stubMail.getSent().size() - 1).body();
        int idx = body.indexOf("token=");
        return body.substring(idx + "token=".length()).trim();
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(dto)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
