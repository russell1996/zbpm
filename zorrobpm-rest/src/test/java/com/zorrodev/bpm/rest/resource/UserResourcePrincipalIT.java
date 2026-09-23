package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.AuditLogRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-69 criterion 2 (+ criterion 3 regression): audit-log записи путей
 * {@code UserResource} содержат корректный principal после замены
 * {@code principalFromRequest()} → {@code getPrincipal()}.
 *
 * <p>V11: реальный HTTP через полный Spring-контекст (MockMvc + живой
 * JwtAuthFilter + реальная БД). Проверяется записанный audit-лог, а не мок:
 * {@code USER_CREATE_INVITE}/{@code USER_CREATE_PASSWORD} несут
 * {@code principalType=USER} и {@code principalId=<admin-id из /auth/login>},
 * {@code USER_RESET_SENT} (путь {@code adminReset}) — того же admin.
 *
 * <p>Критерий 3 (идентичность до/после): сами IT инвариантны к переименованию —
 * тело резолва не менялось (тот же one-liner {@code request.getAttribute}),
 * поэтому эти же тесты зелёные и до, и после; расхождение поведения доказало
 * бы падение. Отсутствие старого метода дополнительно фиксирует
 * {@code UserResourcePrincipalTest.noPrivatePrincipalFromRequest} (рефлексия).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserResourcePrincipalIT {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;
    @Autowired AuditLogRepository auditLogRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private UUID adminId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        adminId = userRepository.findByUsername("admin").orElseThrow().getId();
    }

    private String login(String u, String p) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(u);
        dto.setPassword(p);
        MvcResult r = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private AuditLogEntity latestAudit(String action, String targetId) {
        List<AuditLogEntity> audits = auditLogRepository.findByFilters(null, null, null, null);
        return audits.stream()
            .filter(a -> action.equals(a.getAction()) && targetId.equals(a.getTargetId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no audit " + action + " for " + targetId));
    }

    @Test
    void criterion2_inviteCreate_auditCarriesAdminPrincipal() throws Exception {
        String username = "sec69-inv-" + UUID.randomUUID().toString().substring(0, 8);
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(username);
        dto.setFullName(username);
        dto.setEmail(username + "@zorrodev.test");
        dto.setRole("USER");
        dto.setActive(true);
        dto.setUserType("HUMAN");
        dto.setCreationMode("INVITE");

        MvcResult r = mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn();
        String newId = mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

        AuditLogEntity audit = latestAudit("USER_CREATE_INVITE", newId);
        assertThat(audit.getPrincipalType()).isEqualTo("USER");
        assertThat(audit.getPrincipalId()).isEqualTo(adminId.toString());
    }

    @Test
    void criterion2_passwordCreate_auditCarriesAdminPrincipal() throws Exception {
        String username = "sec69-pwd-" + UUID.randomUUID().toString().substring(0, 8);
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(username);
        dto.setFullName(username);
        dto.setEmail(username + "@zorrodev.test");
        dto.setRole("USER");
        dto.setActive(true);
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setUserType("HUMAN");
        dto.setCreationMode("PASSWORD");

        MvcResult r = mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn();
        String newId = mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();

        AuditLogEntity audit = latestAudit("USER_CREATE_PASSWORD", newId);
        assertThat(audit.getPrincipalType()).isEqualTo("USER");
        assertThat(audit.getPrincipalId()).isEqualTo(adminId.toString());
    }

    @Test
    void criterion3_adminReset_auditCarriesSamePrincipal() throws Exception {
        UiUserEntity target = new UiUserEntity();
        target.setId(UUID.randomUUID());
        String username = "sec69-rst-" + UUID.randomUUID().toString().substring(0, 8);
        target.setUsername(username);
        target.setFullName(username);
        target.setEmail(username + "@zorrodev.test");
        target.setPasswordHash(passwordHasher.hash("MyStr0ng!P@ssw0rd"));
        target.setRole("USER");
        target.setUserType("HUMAN");
        target.setActive(true);
        target.setCreatedAt(Instant.now());
        target.setUpdatedAt(Instant.now());
        userRepository.save(target);

        mockMvc.perform(post("/users/" + target.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        AuditLogEntity audit = latestAudit("USER_RESET_SENT", target.getId().toString());
        assertThat(audit.getPrincipalType()).isEqualTo("USER");
        assertThat(audit.getPrincipalId()).isEqualTo(adminId.toString());
    }
}
