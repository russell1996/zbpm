package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.mapper.UiUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WO-SEC-17 M7: constant-time login — PBKDF2 must be called for both known and unknown usernames.
 */
@ExtendWith(MockitoExtension.class)
class UiUserServiceImplConstantTimeTest {

    @Mock UiUserRepository repository;
    @Mock UiUserMapper mapper;
    @Mock PasswordHasher passwordHasher;
    @Mock TokenService tokenService;

    @InjectMocks UiUserServiceImpl service;

    // --- Criterion #2: PBKDF2 called for both known and unknown users ---

    @Test
    void login_unknownUser_stillCallsPasswordHasher() {
        // Unknown username → repository returns empty
        when(repository.findByUsername("nonexistent")).thenReturn(Optional.empty());
        // PasswordHasher.hash is called for dummy comparison (constant-time defense)
        when(passwordHasher.hash("somepassword")).thenReturn("dummy-hash");

        LoginDTO dto = new LoginDTO();
        dto.setUsername("nonexistent");
        dto.setPassword("somepassword");

        Optional<?> result = service.login(dto);

        assertThat(result).isEmpty();
        // PBKDF2 hash was called even for unknown user (constant-time defense)
        verify(passwordHasher).hash("somepassword");
    }

    @Test
    void login_wrongPassword_stillCallsPasswordHasher() {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("admin");
        user.setPasswordHash("real-hash");
        user.setActive(true);
        user.setRole("ADMIN");

        when(repository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("wrongpass", "real-hash")).thenReturn(false);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("wrongpass");

        Optional<?> result = service.login(dto);

        assertThat(result).isEmpty();
        // PBKDF2 was called for wrong password
        verify(passwordHasher).matches("wrongpass", "real-hash");
    }

    @Test
    void login_correctPassword_succeeds() {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("admin");
        user.setPasswordHash("real-hash");
        user.setActive(true);
        user.setRole("ADMIN");

        when(repository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("correctpass", "real-hash")).thenReturn(true);
        when(tokenService.issue(any(), anyString(), anyString())).thenReturn("jwt-token");

        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("correctpass");

        var result = service.login(dto);

        assertThat(result).isPresent();
        verify(passwordHasher).matches("correctpass", "real-hash");
    }

    // --- Observation 3: inactive user must still run matches() (constant-time) ---

    @Test
    void login_inactiveUser_stillCallsMatches() {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("disabled");
        user.setPasswordHash("real-hash");
        user.setActive(false);
        user.setRole("USER");

        when(repository.findByUsername("disabled")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("somepassword", "real-hash")).thenReturn(true);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("disabled");
        dto.setPassword("somepassword");

        Optional<?> result = service.login(dto);

        assertThat(result).isEmpty();
        // matches() must be called even for inactive user (constant-time)
        verify(passwordHasher).matches("somepassword", "real-hash");
    }
}
