package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.PasswordTokenEntity;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REG-2 (PostgreSQL level): registration columns exist with comments and the
 * ACTIVE default for pre-existing rows; EMAIL_VERIFY tokens ride the shared
 * token infrastructure (hash/TTL/atomic single-use) without a new table.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class RegistrationSchemaPgIT extends PostgresIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UiUserServiceImpl userService;
    @Autowired private UserInvitationService invitationService;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordTokenRepository tokenRepository;
    @Autowired private TokenService tokenService;

    private final List<UUID> cleanupUsers = new ArrayList<>();
    private final List<UUID> cleanupTokens = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID id : cleanupTokens) {
            try {
                tokenRepository.deleteById(id);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        for (UUID id : cleanupUsers) {
            try {
                userRepository.deleteById(id);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        cleanupTokens.clear();
        cleanupUsers.clear();
    }

    private UUID createHuman(String username, String email) {
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setCreationMode("PASSWORD");
        dto.setEmail(email);
        UUID id = userService.create(dto);
        cleanupUsers.add(id);
        return id;
    }

    private UUID issueVerifyToken(UUID userId, String raw, String type, Instant expiresAt) {
        PasswordTokenEntity token = new PasswordTokenEntity();
        token.setId(UUID.randomUUID());
        token.setUserId(userId);
        token.setType(type);
        token.setTokenHash(tokenService.hashToken(raw));
        token.setEmail("verify@x.com");
        token.setExpiresAt(expiresAt);
        token.setUsed(false);
        token.setCreatedAt(Instant.now());
        tokenRepository.save(token);
        cleanupTokens.add(token.getId());
        return token.getId();
    }

    @Test
    void registrationColumns_existWithCommentsAndActiveDefault() {
        // Structural: NOT NULL + DEFAULT 'ACTIVE' on the live schema.
        String nullable = jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns " +
                "WHERE table_name='ui_users' AND column_name='registration_status'", String.class);
        String def = jdbc.queryForObject(
            "SELECT column_default FROM information_schema.columns " +
                "WHERE table_name='ui_users' AND column_name='registration_status'", String.class);
        assertThat(nullable).isEqualTo("NO");
        assertThat(def).containsIgnoringCase("ACTIVE");

        // Comments on all six new columns (criterion 1: "с комментариями").
        Integer commented = jdbc.queryForObject(
            "SELECT count(*) FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid " +
                "WHERE c.relname='ui_users' AND a.attname IN " +
                "('registration_status','email_verified_at','approved_at','approved_by'," +
                "'rejected_at','rejected_reason') " +
                "AND col_description(c.oid,a.attnum) IS NOT NULL AND col_description(c.oid,a.attnum) <> ''",
            Integer.class);
        assertThat(commented).isEqualTo(6);

        // Pre-existing rows (every row in the table, incl. bootstrap) read ACTIVE.
        Integer nonActive = jdbc.queryForObject(
            "SELECT count(*) FROM ui_users WHERE registration_status IS DISTINCT FROM 'ACTIVE'",
            Integer.class);
        assertThat(nonActive).isEqualTo(0);
    }

    @Test
    void existingUsers_keepLoggingInAfterMigration() {
        // Criterion 1 senior regression: the login gate is untouched by the new columns.
        UUID id = createHuman("reglogin", "reglogin@x.com");
        assertThat(jdbc.queryForObject(
            "SELECT registration_status FROM ui_users WHERE id=?", String.class, id))
            .isEqualTo("ACTIVE");

        LoginDTO login = new LoginDTO();
        login.setUsername(userRepository.findById(id).orElseThrow().getUsername());
        login.setPassword("MyStr0ng!P@ssw0rd");
        assertThat(userService.login(login)).isPresent();
    }

    @Test
    void emailVerifyToken_consumesOnceAtomically() {
        UUID userId = createHuman("regverify", "regverify@x.com");
        String raw = "verify-raw-" + UUID.randomUUID();

        issueVerifyToken(userId, raw, UserInvitationService.TYPE_EMAIL_VERIFY,
            Instant.now().plusSeconds(3600));

        assertThat(invitationService.consumeEmailVerifyToken(raw)).isEqualTo(userId);
        assertThatThrownBy(() -> invitationService.consumeEmailVerifyToken(raw))
            .isInstanceOf(com.zorrodev.bpm.contract.exception.EngineException.class)
            .hasMessageContaining("Invalid or expired token");
    }

    @Test
    void emailVerifyToken_rejectsWrongTypeAndExpired() {
        UUID userId = createHuman("regverify2", "regverify2@x.com");

        String resetRaw = "reset-raw-" + UUID.randomUUID();
        issueVerifyToken(userId, resetRaw, UserInvitationService.TYPE_RESET,
            Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> invitationService.consumeEmailVerifyToken(resetRaw))
            .isInstanceOf(com.zorrodev.bpm.contract.exception.EngineException.class)
            .hasMessageContaining("Invalid or expired token");

        String expiredRaw = "expired-raw-" + UUID.randomUUID();
        issueVerifyToken(userId, expiredRaw, UserInvitationService.TYPE_EMAIL_VERIFY,
            Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> invitationService.consumeEmailVerifyToken(expiredRaw))
            .isInstanceOf(com.zorrodev.bpm.contract.exception.EngineException.class)
            .hasMessageContaining("Invalid or expired token");

        assertThatThrownBy(() -> invitationService.consumeEmailVerifyToken(" "))
            .isInstanceOf(com.zorrodev.bpm.contract.exception.EngineException.class)
            .hasMessageContaining("Token is required");
    }
}
