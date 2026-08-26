package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.RefreshTokenEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.RefreshTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.service.UiUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-59 #6: an admin (or self) password reset must revoke ALL of the user's refresh tokens,
 * otherwise a previously issued/held refresh token keeps minting access tokens after the password
 * was changed. Before the fix the reset path updated only the password hash and left every refresh
 * token valid.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class UiUserPasswordResetRevokesTokensTests {

    @Autowired private UiUserService uiUserService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;

    @Test
    void adminPasswordReset_revokesAllRefreshTokens() {
        UiUserEntity u = new UiUserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("sec59-" + UUID.randomUUID());
        u.setPasswordHash(passwordHasher.hash("OldPassw0rd!"));
        u.setFullName("sec59");
        u.setRole("USER");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        RefreshTokenEntity t = new RefreshTokenEntity();
        t.setId(UUID.randomUUID());
        t.setUserId(u.getId());
        t.setTokenHash("sec59-refresh-" + UUID.randomUUID());
        t.setExpiresAt(Instant.now().plusSeconds(3600));
        t.setRevoked(false);
        t.setCreatedAt(Instant.now());
        refreshTokenRepository.save(t);

        UpdateUiUserDTO dto = new UpdateUiUserDTO();
        dto.setPassword("NewPassw0rd!");

        uiUserService.update(u.getId(), dto);

        assertThat(refreshTokenRepository.findByTokenHashAndRevokedFalse(t.getTokenHash())).isEmpty();
    }
}
