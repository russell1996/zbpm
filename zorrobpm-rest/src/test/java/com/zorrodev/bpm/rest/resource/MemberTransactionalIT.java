package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.service.AuditLogService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-DEBT-7 S4: {@code @Transactional} on the MemberResource mutation endpoints.
 * Proves that audit failure rolls the mutation back — i.e. {@code ProcessMemberService}
 * joins the facade's tx (the service declares no {@code @Transactional} of its own).
 *
 * POF GREEN (with {@code @Transactional} on the facade): mock auditLogService.record()
 * throws RuntimeException → transaction rolled back → mutation NOT visible in the DB.
 * POF RED (without {@code @Transactional} on the facade): same exception → mutation
 * already committed → row present / role changed / row gone.
 *
 * Every test creates its own candidate user (uuid-suffixed login), so assertions only
 * ever touch rows this test created — order-independent (P-8).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberTransactionalIT {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UiUserRepository userRepository;
    @Autowired ProcessRepository processRepository;
    @Autowired PasswordHasher passwordHasher;

    @MockitoBean AuditLogService auditLogService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String processKey;
    private UUID processId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");

        UUID ownerId = createUser("txmem-owner");
        processKey = "tx-mem-" + UUID.randomUUID().toString().substring(0, 8);
        deployProcess(processKey);
        processId = processRepository.findByDefinitionKey(processKey).orElseThrow().getId();

        // Owner membership via HTTP (audit mock is a lenient no-op unless stubbed)
        addMemberHttp(ownerId, "OWNER");
    }

    // ==================== addMember: audit failure rolls the insert back ====================

    @Test
    void addMember_auditFails_memberNotPersisted() throws Exception {
        UUID candidateId = createUser("txmem-add");

        doThrow(new RuntimeException("audit write failed"))
            .when(auditLogService).record(any(), any(), any(), any());

        mockMvc.perform(post("/processes/" + processKey + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"userId\":\"" + candidateId + "\",\"role\":\"VIEWER\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is5xxServerError());

        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_member WHERE process_id = ? AND user_id = ?",
            Integer.class, processId, candidateId);
        assertEquals(0, rows, "Member must NOT be persisted when audit fails (tx rolled back)");
    }

    // ==================== changeRole: audit failure keeps the old role ====================

    @Test
    void changeRole_auditFails_roleUnchanged() throws Exception {
        UUID candidateId = createUser("txmem-role");
        addMemberHttp(candidateId, "VIEWER");

        doThrow(new RuntimeException("audit write failed"))
            .when(auditLogService).record(any(), any(), any(), any());

        mockMvc.perform(patch("/processes/" + processKey + "/members/" + candidateId)
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"role\":\"DESIGNER\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is5xxServerError());

        String role = jdbc.queryForObject(
            "SELECT role FROM process_member WHERE process_id = ? AND user_id = ?",
            String.class, processId, candidateId);
        assertEquals("VIEWER", role, "Role must NOT change when audit fails (tx rolled back)");
    }

    // ==================== removeMember: audit failure keeps the row ====================

    @Test
    void removeMember_auditFails_memberStillPresent() throws Exception {
        UUID candidateId = createUser("txmem-del");
        addMemberHttp(candidateId, "VIEWER");

        doThrow(new RuntimeException("audit write failed"))
            .when(auditLogService).record(any(), any(), any(), any());

        mockMvc.perform(delete("/processes/" + processKey + "/members/" + candidateId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().is5xxServerError());

        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_member WHERE process_id = ? AND user_id = ?",
            Integer.class, processId, candidateId);
        assertEquals(1, rows, "Member must NOT be removed when audit fails (tx rolled back)");
    }

    // ==================== happy-path: mutation persists when audit succeeds ====================

    @Test
    void addMember_happyPath_memberPersisted() throws Exception {
        UUID candidateId = createUser("txmem-happy");

        // Audit succeeds (mock returns void by default)
        MvcResult result = mockMvc.perform(post("/processes/" + processKey + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"userId\":\"" + candidateId + "\",\"role\":\"VIEWER\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();

        // Concrete response values, not just "row exists"
        assertEquals("VIEWER", mapper.readTree(result.getResponse().getContentAsString()).get("role").asText());
        assertEquals(candidateId.toString(),
            mapper.readTree(result.getResponse().getContentAsString()).get("userId").asText());

        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_member WHERE process_id = ? AND user_id = ?",
            Integer.class, processId, candidateId);
        assertEquals(1, rows, "Member must be persisted on happy-path");
        String role = jdbc.queryForObject(
            "SELECT role FROM process_member WHERE process_id = ? AND user_id = ?",
            String.class, processId, candidateId);
        assertEquals("VIEWER", role, "Persisted role must equal the requested role");
    }

    // ==================== Helpers ====================

    private UUID createUser(String loginPrefix) {
        UUID id = UUID.randomUUID();
        UiUserEntity user = new UiUserEntity();
        user.setId(id);
        user.setUsername(loginPrefix + "-" + id.toString().substring(0, 8));
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(loginPrefix + " User");
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        return id;
    }

    private void deployProcess(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));
        bpmn = bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                   .replace("name=\"Process 1\"", "name=\"" + key + "\"")
                   .replace("process id=\"process1\"", "process id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    private void addMemberHttp(UUID userId, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                .content(mapper.writeValueAsString(dto))
                .contentType("application/json"))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
