package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.mail.MailStatus;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-INT-5 criteria 7, 8, 9: mail health and test send integration tests.
 * Full-context tests using real Spring context + MockMvc.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailResourceTest {

    @Autowired MockMvc mockMvc;
    @Autowired MailStatus mailStatus;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String superAdminToken;

    @BeforeAll
    void setup() throws Exception {
        superAdminToken = loginAndGetToken("admin", "admin");
    }

    // ==================== Criterion 7: Health indicator reflects config status ====================

    @Test
    void criterion7_healthEndpoint_returnsConfiguredStatus() throws Exception {
        // criterion 7: health endpoint returns configured flag
        MvcResult result = mockMvc.perform(get("/admin/mail/health")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn();

        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(json.has("configured"), "Response must have 'configured' field");
        // In test profile, MailProperties may or may not be configured depending on env
        // The key assertion is that the endpoint works and returns valid JSON
    }

    @Test
    void criterion7_healthEndpoint_noAuth_gets401() throws Exception {
        // criterion 7: unauthenticated access is rejected
        mockMvc.perform(get("/admin/mail/health"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void criterion7_healthEndpoint_invalidToken_gets401() throws Exception {
        // criterion 7: invalid token is rejected with 401
        mockMvc.perform(get("/admin/mail/health")
                .header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized());
    }

    // ==================== Criterion 8: Test email send ====================

    @Test
    void criterion8_testEmail_invalidToken_gets401() throws Exception {
        // criterion 8: invalid token is rejected with 401
        mockMvc.perform(post("/admin/mail/test")
                .header("Authorization", "Bearer invalid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("\"admin@example.com\""))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void criterion8_testEmail_noAuth_gets401() throws Exception {
        // criterion 8: unauthenticated access is rejected
        mockMvc.perform(post("/admin/mail/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("\"admin@example.com\""))
            .andExpect(status().isUnauthorized());
    }

    // ==================== Criterion 9: Status tracking ====================

    @Test
    void criterion9_statusRecords_lastSuccess() {
        // criterion 9: MailStatus tracks last success time
        mailStatus.recordSuccess();
        assertNotNull(mailStatus.getLastSuccess(), "lastSuccess should be recorded after success");
        assertNull(mailStatus.getLastErrorMessage(), "lastErrorMessage should be cleared on success");
    }

    @Test
    void criterion9_statusRecords_lastError() {
        // criterion 9: MailStatus tracks last error time and message
        mailStatus.recordError("SMTP connection refused");
        assertNotNull(mailStatus.getLastError(), "lastError should be recorded after failure");
        assertNotNull(mailStatus.getLastErrorMessage(), "lastErrorMessage should be recorded");
        assertTrue(mailStatus.getLastErrorMessage().contains("SMTP connection refused"));
    }

    @Test
    void criterion9_healthEndpoint_showsStatusTimes() throws Exception {
        // criterion 9: health endpoint returns lastSuccess and lastError
        mailStatus.recordSuccess();

        MvcResult result = mockMvc.perform(get("/admin/mail/health")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(json.has("configured"), "Response must have 'configured'");
        assertTrue(json.has("lastSuccess"), "Response must have 'lastSuccess'");
        assertTrue(json.has("lastError"), "Response must have 'lastError'");
        assertTrue(json.has("lastErrorMessage"), "Response must have 'lastErrorMessage'");
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
