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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    // --- Criterion #2 + WO-SEC-63 (F21): exactly ONE PBKDF2 call for known AND unknown users ---

    @Test
    void login_unknownUser_runsExactlyOneMatches_againstDummyHash() {
        // Unknown username → repository returns empty
        when(repository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        LoginDTO dto = new LoginDTO();
        dto.setUsername("nonexistent");
        dto.setPassword("somepassword");

        Optional<?> result = service.login(dto);

        assertThat(result).isEmpty();
        // F21: no hash() call at all — the dummy is PRE-COMPUTED, so an unknown login costs
        // exactly one matches() (same as a known login). The old code ran hash()+matches()
        // (TWO PBKDF2 runs) for unknown users — a timing oracle and double CPU burn.
        verify(passwordHasher, never()).hash(anyString());
        verify(passwordHasher, times(1)).matches("somepassword", PasswordHasher.CONSTANT_TIME_DUMMY_HASH);
    }

    @Test
    void login_unknownAndKnownUser_sameKdfCallCount() {
        // F21 criterion 7: both paths cost exactly ONE KDF call — count, don't eyeball timing.
        when(repository.findByUsername("ghost")).thenReturn(Optional.empty());
        LoginDTO unknownDto = new LoginDTO();
        unknownDto.setUsername("ghost");
        unknownDto.setPassword("somepassword");
        assertThat(service.login(unknownDto)).isEmpty();
        verify(passwordHasher, times(1)).matches(eq("somepassword"), anyString());
        verify(passwordHasher, never()).hash(anyString());

        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("real");
        user.setPasswordHash("real-hash");
        user.setActive(true);
        user.setRole("USER");
        when(repository.findByUsername("real")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("somepassword", "real-hash")).thenReturn(false);
        LoginDTO knownDto = new LoginDTO();
        knownDto.setUsername("real");
        knownDto.setPassword("somepassword");
        assertThat(service.login(knownDto)).isEmpty();
        // total across both logins: exactly 2 matches(), 0 hash() — symmetric cost
        verify(passwordHasher, times(2)).matches(eq("somepassword"), anyString());
        verify(passwordHasher, never()).hash(anyString());
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
        // PBKDF2 was called exactly once for wrong password (symmetric with unknown-user path)
        verify(passwordHasher, times(1)).matches("wrongpass", "real-hash");
        verify(passwordHasher, never()).hash(anyString());
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
        when(tokenService.issue(any(), anyString(), anyString(), anyInt())).thenReturn("jwt-token");

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
