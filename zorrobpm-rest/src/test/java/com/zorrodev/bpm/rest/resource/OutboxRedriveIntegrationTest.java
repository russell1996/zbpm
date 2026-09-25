package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.exchange.MailSendRequested;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-REL-22 (B1/B2): admin quarantine list + re-drive (V11, full-context).
 * Inherits to PG unchanged via {@link OutboxRedrivePgIT}.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxRedriveIntegrationTest {

    @TestConfiguration
    static class CaptureConfig {
        static final List<MailSendRequested> MAIL = Collections.synchronizedList(new ArrayList<>());

        @EventListener
        public void on(MailSendRequested e) {
            MAIL.add(e);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired OutboxRepository outboxRepository;
    @Autowired OutboxBatchProcessor processor;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String superAdminToken;
    private String userToken;

    @BeforeAll
    void setup() throws Exception {
        superAdminToken = loginAndGetToken("admin", "admin");
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("rel22-user");
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName("rel22-user");
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        userToken = loginAndGetToken("rel22-user", "pass");
    }

    @BeforeEach
    void cleanOutbox() {
        outboxRepository.deleteAllInBatch();
        CaptureConfig.MAIL.clear();
    }

    private UUID seedFailedEmail() {
        UUID id = UUID.randomUUID();
        OutboxEntry e = new OutboxEntry();
        e.setId(id);
        e.setKind(OutboxKind.EMAIL);
        e.setPayload("{\"to\":\"u@test.com\",\"subject\":\"s\",\"body\":\"b\",\"html\":false}");
        e.setCreatedAt(Instant.now());
        e.setPublished(false);
        e.setAttempts(5);
        e.setStatus(com.zorrodev.bpm.engine.entity.OutboxStatus.FAILED);
        e.setLastError("boom");
        outboxRepository.saveAndFlush(e);
        return id;
    }

    // ==================== B1: quarantine list ====================

    @Test
    void criterionB1_failedList_showsStuckEntry() throws Exception {
        UUID id = seedFailedEmail();

        MvcResult result = mockMvc.perform(get("/admin/outbox")
                .queryParam("status", "FAILED")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.isArray(), "list response");
        boolean found = false;
        for (JsonNode n : body) {
            if (id.toString().equals(n.get("id").asText())) {
                found = true;
                assertEquals("FAILED", n.get("status").asText());
                assertEquals(5, n.get("attempts").asInt());
            }
        }
        assertTrue(found, "stuck entry visible in quarantine list");
    }

    @Test
    void criterionB1_unknownStatus_400() throws Exception {
        mockMvc.perform(get("/admin/outbox")
                .queryParam("status", "NOPE")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isBadRequest());
    }

    @Test
    void criterionGuard_nonAdmin_403() throws Exception {
        UUID id = seedFailedEmail();

        mockMvc.perform(get("/admin/outbox")
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/outbox/" + id + "/redrive")
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }

    // ==================== B2: re-drive ====================

    @Test
    void criterionB2_redrive_resetsAndRepicks() throws Exception {
        UUID id = seedFailedEmail();

        MvcResult result = mockMvc.perform(post("/admin/outbox/" + id + "/redrive")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("PENDING", body.get("status").asText());
        assertEquals(0, body.get("attempts").asInt());

        OutboxEntry row = outboxRepository.findById(id).orElseThrow();
        assertEquals(com.zorrodev.bpm.engine.entity.OutboxStatus.PENDING, row.getStatus());
        assertEquals(0, row.getAttempts());

        // The batch processor picks the redriven row up again (Spring event = delivered
        // as far as this service goes; the broker hop is covered by RabbitOutboxConfirmIT).
        processor.processBatch();
        assertFalse(CaptureConfig.MAIL.isEmpty(), "redriven entry re-published to the mail consumer");
        assertEquals(id.toString(), CaptureConfig.MAIL.get(0).getOutboxId());
    }

    @Test
    void criterionB2_redrive_unknownId_404() throws Exception {
        mockMvc.perform(post("/admin/outbox/" + UUID.randomUUID() + "/redrive")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void criterionB2_redrive_nonFailed_409() throws Exception {
        UUID id = seedFailedEmail();
        outboxRepository.findById(id).ifPresent(e -> {
            e.setStatus(com.zorrodev.bpm.engine.entity.OutboxStatus.PENDING);
            outboxRepository.save(e);
        });

        mockMvc.perform(post("/admin/outbox/" + id + "/redrive")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isConflict());
    }

    private String loginAndGetToken(String username, String password) throws Exception {
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
}
