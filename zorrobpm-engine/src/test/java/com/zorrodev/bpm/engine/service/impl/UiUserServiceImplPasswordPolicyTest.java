package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.mapper.UiUserMapper;
import com.zorrodev.bpm.engine.repository.RefreshTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.TokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-SEC-46: Password complexity enforcement on create/update.
 *
 * CRIT-1: create() with weak password → rejected
 * CRIT-2: update() with weak password → rejected, old password unchanged
 * CRIT-3: strong password still works (regression)
 * CRIT-4: error message doesn't reveal blocklist details
 */
@ExtendWith(MockitoExtension.class)
class UiUserServiceImplPasswordPolicyTest {

    @Mock UiUserRepository repository;
    @Mock UiUserMapper mapper;
    @Mock PasswordHasher passwordHasher;
    @Mock TokenService tokenService;
    @Mock RefreshTokenRepository refreshTokenRepository;

    @InjectMocks UiUserServiceImpl service;

    // --- CRIT-1: create() with weak password → rejected ---

    @Test
    void crit1_create_shortPassword_rejected() {
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername("newuser");
        dto.setPassword("short");

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("complexity");

        verify(repository, never()).save(any());
    }

    @Test
    void crit1_create_blocklistedPassword_rejected() {
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername("newuser");
        dto.setPassword("admin"); // blocklisted (will fail on length first)

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("complexity");

        verify(repository, never()).save(any());
    }

    // --- CRIT-2: update() with weak password → rejected ---

    @Test
    void crit2_update_shortPassword_rejected() {
        UUID userId = UUID.randomUUID();
        com.zorrodev.bpm.engine.entity.UiUserEntity existing = new com.zorrodev.bpm.engine.entity.UiUserEntity();
        existing.setId(userId);
        existing.setUsername("user1");
        existing.setPasswordHash("old-hash");

        when(repository.findById(userId)).thenReturn(Optional.of(existing));

        UpdateUiUserDTO dto = new UpdateUiUserDTO();
        dto.setPassword("weak");

        assertThatThrownBy(() -> service.update(userId, dto))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("complexity");

        // Old password hash unchanged
        assertThat(existing.getPasswordHash()).isEqualTo("old-hash");
        verify(repository, never()).save(any());
    }

    @Test
    void crit2_update_blocklistedPassword_rejected() {
        UUID userId = UUID.randomUUID();
        com.zorrodev.bpm.engine.entity.UiUserEntity existing = new com.zorrodev.bpm.engine.entity.UiUserEntity();
        existing.setId(userId);
        existing.setUsername("user1");
        existing.setPasswordHash("old-hash");

        when(repository.findById(userId)).thenReturn(Optional.of(existing));

        UpdateUiUserDTO dto = new UpdateUiUserDTO();
        dto.setPassword("password"); // blocklisted (will fail on length first)

        assertThatThrownBy(() -> service.update(userId, dto))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("complexity");

        assertThat(existing.getPasswordHash()).isEqualTo("old-hash");
    }

    // --- CRIT-3: strong password still works ---

    @Test
    void crit3_create_strongPassword_succeeds() {
        when(repository.existsByUsername("newuser")).thenReturn(false);
        when(passwordHasher.hash("MyStr0ng!P@ssw0rd")).thenReturn("hashed");

        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername("newuser");
        dto.setPassword("MyStr0ng!P@ssw0rd");

        UUID result = service.create(dto);

        assertThat(result).isNotNull();
        verify(repository).save(any());
    }

    @Test
    void crit3_update_strongPassword_succeeds() {
        UUID userId = UUID.randomUUID();
        com.zorrodev.bpm.engine.entity.UiUserEntity existing = new com.zorrodev.bpm.engine.entity.UiUserEntity();
        existing.setId(userId);
        existing.setUsername("user1");
        existing.setPasswordHash("old-hash");

        when(repository.findById(userId)).thenReturn(Optional.of(existing));
        when(passwordHasher.hash("MyStr0ng!P@ssw0rd")).thenReturn("new-hash");

        UpdateUiUserDTO dto = new UpdateUiUserDTO();
        dto.setPassword("MyStr0ng!P@ssw0rd");

        UUID result = service.update(userId, dto);

        assertThat(result).isEqualTo(userId);
        verify(repository).save(any());
    }

    // --- CRIT-4: error message doesn't reveal blocklist ---

    @Test
    void crit4_errorMessage_generic() {
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername("newuser");
        dto.setPassword("123456");

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(EngineException.class)
            .hasMessage("Password does not meet complexity requirements")
            .satisfies(e -> {
                // Must NOT mention blocklist, "weak", "admin", or specific rules
                String msg = e.getMessage().toLowerCase();
                assertThat(msg).doesNotContain("blocklist");
                assertThat(msg).doesNotContain("weak");
                assertThat(msg).doesNotContain("admin");
                assertThat(msg).doesNotContain("12 characters");
            });
    }

    // --- update() without password change → no complexity check ---

    @Test
    void update_noPasswordChange_skipsValidation() {
        UUID userId = UUID.randomUUID();
        com.zorrodev.bpm.engine.entity.UiUserEntity existing = new com.zorrodev.bpm.engine.entity.UiUserEntity();
        existing.setId(userId);
        existing.setUsername("user1");
        existing.setPasswordHash("old-hash");

        when(repository.findById(userId)).thenReturn(Optional.of(existing));

        UpdateUiUserDTO dto = new UpdateUiUserDTO();
        dto.setFullName("New Name");
        // No password set

        UUID result = service.update(userId, dto);

        assertThat(result).isEqualTo(userId);
        verify(repository).save(any());
    }
}
