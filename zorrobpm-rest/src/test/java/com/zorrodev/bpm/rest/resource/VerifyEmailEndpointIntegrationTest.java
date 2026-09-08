package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-REG-4 (rest, full filter): POST /auth/verify-email is public, consumes
 * EMAIL_VERIFY token idempotently, notifies SUPER_ADMINs, leaves login 401.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerifyEmailEndpointIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private StubMailSender mailSender;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerBody(String username, String email) {
        return "{\"username\":\"" + username + "\",\"password\":\"MyStr0ng!P@ssw0rd\",\"fullName\":\"Verify\",\"email\":\"" + email + "\"}";
    }

    private String registerAndGetToken(String username, String email) throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username, email).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        // Last mail to this email holds the token
        String body = mailSender.getSent().stream()
            .filter(m -> email.equalsIgnoreCase(m.to()))
            .map(StubMailSender.MailRecord::body)
            .reduce((a, b) -> b).orElseThrow();
        Matcher m = Pattern.compile("token=([^\\s\"]+)").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private UiUserEntity onlyUserWithEmail(String email) {
        List<UiUserEntity> found = userRepository.findAll().stream()
            .filter(u -> email.equalsIgnoreCase(u.getEmail()))
            .toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Test
    void verifyEmail_happy_movesToPendingApproval() throws Exception {
        String email = "vhttp-" + tag() + "@x.com";
        String token = registerAndGetToken("vhttp-" + tag(), email);

        mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        UiUserEntity after = onlyUserWithEmail(email);
        assertThat(after.getRegistrationStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(after.getEmailVerifiedAt()).isNotNull();
        userRepository.deleteById(after.getId());
    }

    @Test
    void verifyEmail_repeatToken_isErrorNotRollback() throws Exception {
        String email = "vrepeat-" + tag() + "@x.com";
        String token = registerAndGetToken("vrepeat-" + tag(), email);
        UiUserEntity user = onlyUserWithEmail(email);

        mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRegistrationStatus())
            .isEqualTo("PENDING_APPROVAL");
        userRepository.deleteById(user.getId());
    }

    @Test
    void verifyEmail_loginStill401() throws Exception {
        String suffix = tag();
        String email = "vlogin-" + suffix + "@x.com";
        String username = "vlogin-" + suffix;
        String token = registerAndGetToken(username, email);

        mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        // login must still be 401 (active false)
        String loginBody = "{\"username\":\"" + username + "\",\"password\":\"MyStr0ng!P@ssw0rd\"}";
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());

        userRepository.deleteById(onlyUserWithEmail(email).getId());
    }
}
