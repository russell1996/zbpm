package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.MailStatus;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailResourceTest {

    @Autowired MockMvc mockMvc;
    @Autowired MailStatus mailStatus;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String superAdminToken;
    private String regularUserToken;

    @BeforeAll
    void setup() throws Exception {
        superAdminToken = loginAndGetToken("admin", "admin");

        UiUserEntity regularUser = new UiUserEntity();
        regularUser.setId(UUID.randomUUID());
        regularUser.setUsername("mail-regular-user");
        regularUser.setPasswordHash(passwordHasher.hash("pass123"));
        regularUser.setFullName("Regular User");
        regularUser.setRole("USER");
        regularUser.setActive(true);
        regularUser.setCreatedAt(Instant.now());
        regularUser.setUpdatedAt(Instant.now());
        userRepository.save(regularUser);

        regularUserToken = loginAndGetToken("mail-regular-user", "pass123");
    }

    private static final String SETTINGS_BODY =
        "{\"host\":\"smtp.x\",\"port\":587,\"username\":\"u\",\"from\":\"f@x\",\"allowedRecipients\":\"\"}";
    private static final String TESTSELF_BODY =
        "{\"host\":\"smtp.x\",\"port\":587,\"username\":\"u\",\"password\":\"p\",\"from\":\"f@x\"}";

    // ==================== Criterion 7: Health ====================

    @Test
    void criterion7_healthEndpoint_returnsConfiguredStatus() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/mail/health")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(json.has("configured"), "Response must have 'configured' field");
    }

    @Test
    void criterion7_healthEndpoint_noAuth_gets401() throws Exception {
        mockMvc.perform(get("/admin/mail/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void criterion7_healthEndpoint_invalidToken_gets401() throws Exception {
        mockMvc.perform(get("/admin/mail/health")
                .header("Authorization", "Bearer invalid-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void criterion7_healthEndpoint_nonSuperAdmin_gets403() throws Exception {
        mockMvc.perform(get("/admin/mail/health")
                .header("Authorization", "Bearer " + regularUserToken)).andExpect(status().isForbidden());
    }

    // ==================== Criterion 4: Settings require SUPER_ADMIN ====================

    @Test
    void criterion4_settingsGet_superAdmin_gets200() throws Exception {
        mockMvc.perform(get("/admin/mail/settings")
                .header("Authorization", "Bearer " + superAdminToken)).andExpect(status().isOk());
    }

    @Test
    void criterion4_settingsGet_nonSuperAdmin_gets403() throws Exception {
        mockMvc.perform(get("/admin/mail/settings")
                .header("Authorization", "Bearer " + regularUserToken)).andExpect(status().isForbidden());
    }

    @Test
    void criterion4_settingsGet_noAuth_gets401() throws Exception {
        mockMvc.perform(get("/admin/mail/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void criterion5_settingsGet_responseHasPasswordSet_notPasswordValue() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/mail/settings")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk()).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(json.has("passwordSet"), "Response must indicate whether a password is set");
        String raw = result.getResponse().getContentAsString();
        assertFalse(raw.contains("\"password\":\"topsecret\""), "Password value must not be returned");
    }

    @Test
    void criterion4_settingsPut_superAdmin_gets200() throws Exception {
        mockMvc.perform(put("/admin/mail/settings")
                .header("Authorization", "Bearer " + superAdminToken)
                .contentType(MediaType.APPLICATION_JSON).content(SETTINGS_BODY))
            .andExpect(status().isOk());
    }

    @Test
    void criterion4_settingsPut_nonSuperAdmin_gets403() throws Exception {
        mockMvc.perform(put("/admin/mail/settings")
                .header("Authorization", "Bearer " + regularUserToken)
                .contentType(MediaType.APPLICATION_JSON).content(SETTINGS_BODY))
            .andExpect(status().isForbidden());
    }

    // ==================== Criterion 6: test-self requires SUPER_ADMIN ====================

    @Test
    void criterion6_testSelf_superAdmin_reachesService_notAuthBlocked() throws Exception {
        mockMvc.perform(post("/admin/mail/test-self")
                .header("Authorization", "Bearer " + superAdminToken)
                .contentType(MediaType.APPLICATION_JSON).content(TESTSELF_BODY))
            .andExpect(result -> {
                int s = result.getResponse().getStatus();
                assertTrue(s != 401 && s != 403,
                    "super-admin must pass auth and reach the service, got " + s);
            });
    }

    @Test
    void criterion6_testSelf_nonSuperAdmin_gets403() throws Exception {
        mockMvc.perform(post("/admin/mail/test-self")
                .header("Authorization", "Bearer " + regularUserToken)
                .contentType(MediaType.APPLICATION_JSON).content(TESTSELF_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    void criterion6_testSelf_noAuth_gets401() throws Exception {
        mockMvc.perform(post("/admin/mail/test-self")
                .contentType(MediaType.APPLICATION_JSON).content(TESTSELF_BODY))
            .andExpect(status().isUnauthorized());
    }

    // ==================== Criterion 2/4 wiring: test profile isolation ====================

    @Autowired org.springframework.context.ApplicationContext ctx;

    @Test
    void profileWiring_testProfile_noRealMailTransportAnywhere() {
        assertThat(ctx.getBeansOfType(org.springframework.mail.javamail.JavaMailSender.class)).isEmpty();
        assertThat(ctx.getBeansOfType(com.zorrodev.bpm.engine.mail.MailDeliveryListener.class)).isEmpty();
        assertThat(ctx.getBeansOfType(com.zorrodev.bpm.engine.mail.SmtpMailSender.class)).isEmpty();
    }

    // ==================== Criterion 9: Status tracking ====================

    @Test
    void criterion9_statusRecords_lastSuccess() {
        mailStatus.recordSuccess();
        assertNotNull(mailStatus.getLastSuccess(), "lastSuccess should be recorded after success");
        assertNull(mailStatus.getLastErrorMessage(), "lastErrorMessage should be cleared on success");
    }

    @Test
    void criterion9_statusRecords_lastError() {
        mailStatus.recordError("SMTP connection refused");
        assertNotNull(mailStatus.getLastError(), "lastError should be recorded after failure");
        assertNotNull(mailStatus.getLastErrorMessage(), "lastErrorMessage should be recorded");
        assertTrue(mailStatus.getLastErrorMessage().contains("SMTP connection refused"));
    }

    @Test
    void criterion9_healthEndpoint_showsStatusTimes() throws Exception {
        mailStatus.recordSuccess();
        MvcResult result = mockMvc.perform(get("/admin/mail/health")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk()).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(json.has("configured"));
        assertTrue(json.has("reachable"));
        assertTrue(json.has("lastSuccess"));
        assertTrue(json.has("lastError"));
        assertTrue(json.has("lastErrorMessage"));
    }

    // ==================== Helpers ====================

    private String loginAndGetToken(String username, String password) throws Exception {
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
}
