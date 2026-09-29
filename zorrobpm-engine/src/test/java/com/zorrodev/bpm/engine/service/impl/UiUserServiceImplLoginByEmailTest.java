package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mapper.UiUserMapper;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.TokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WO-AUTH-1: вход по email (username ИЛИ email в поле логина).
 * Порядок: username → email. Constant-time (WO-SEC-17 M7): ровно один
 * passwordHasher.matches на попытку независимо от пути.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UiUserServiceImplLoginByEmailTest {

    @Mock UiUserRepository repository;
    @Mock UiUserMapper mapper;
    @Mock PasswordHasher passwordHasher;
    @Mock TokenService tokenService;

    @InjectMocks UiUserServiceImpl service;

    private static UiUserEntity user(String username, String email) {
        UiUserEntity u = new UiUserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername(username);
        u.setEmail(email);
        u.setPasswordHash("real-hash");
        u.setActive(true);
        u.setRole("USER");
        return u;
    }

    private static LoginDTO dto(String identifier, String password) {
        LoginDTO d = new LoginDTO();
        d.setUsername(identifier);
        d.setPassword(password);
        return d;
    }

    @Test
    void login_byEmail_succeeds() {
        UiUserEntity u = user("ivan", "ivan@example.com");
        when(repository.findByUsername("ivan@example.com")).thenReturn(Optional.empty());
        when(repository.findByEmail("ivan@example.com")).thenReturn(Optional.of(u));
        when(passwordHasher.matches("secret", "real-hash")).thenReturn(true);
        when(tokenService.issue(any(), anyString(), anyString(), anyInt())).thenReturn("jwt");

        assertThat(service.login(dto("ivan@example.com", "secret"))).isPresent();
    }

    @Test
    void login_byEmail_caseInsensitive() {
        UiUserEntity u = user("ivan", "ivan@example.com");
        when(repository.findByUsername("Foo@X.com")).thenReturn(Optional.empty());
        when(repository.findByEmail("foo@x.com")).thenReturn(Optional.of(u));
        when(passwordHasher.matches("secret", "real-hash")).thenReturn(true);
        when(tokenService.issue(any(), anyString(), anyString(), anyInt())).thenReturn("jwt");

        assertThat(service.login(dto("Foo@X.com", "secret"))).isPresent();
        verify(repository).findByEmail("foo@x.com");
    }

    @Test
    void login_wrongPasswordByEmail_sameRefusalAsUnknown() {
        UiUserEntity u = user("ivan", "ivan@example.com");
        when(repository.findByUsername("ivan@example.com")).thenReturn(Optional.empty());
        when(repository.findByEmail("ivan@example.com")).thenReturn(Optional.of(u));
        when(passwordHasher.matches("wrong", "real-hash")).thenReturn(false);

        assertThat(service.login(dto("ivan@example.com", "wrong"))).isEmpty();

        // Несуществующий email: тот же наблюдаемый отказ, без утечки «есть такой email»
        when(repository.findByUsername("nobody@example.com")).thenReturn(Optional.empty());
        when(repository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(passwordHasher.hash("wrong")).thenReturn("dummy");
        assertThat(service.login(dto("nobody@example.com", "wrong"))).isEmpty();
    }

    @Test
    void login_usernameWinsOverForeignEmail() {
        // username "x" юзера A буквально совпадает с email юзера B → входит A
        UiUserEntity a = user("x", "a@example.com");
        when(repository.findByUsername("x")).thenReturn(Optional.of(a));
        when(passwordHasher.matches("secret", "real-hash")).thenReturn(true);
        when(tokenService.issue(any(), anyString(), anyString(), anyInt())).thenReturn("jwt");

        assertThat(service.login(dto("x", "secret"))).isPresent();
        verify(repository, never()).findByEmail(anyString());
    }

    @Test
    void login_unknownIdentifier_stillSingleMatches() {
        when(repository.findByUsername("ghost")).thenReturn(Optional.empty());
        when(repository.findByEmail("ghost")).thenReturn(Optional.empty());
        when(passwordHasher.hash("pw")).thenReturn("dummy");

        assertThat(service.login(dto("ghost", "pw"))).isEmpty();
        verify(passwordHasher, times(1)).matches(anyString(), anyString());
    }

    @Test
    void login_byUsername_stillSingleMatches() {
        UiUserEntity u = user("ivan", "ivan@example.com");
        when(repository.findByUsername("ivan")).thenReturn(Optional.of(u));
        when(passwordHasher.matches("secret", "real-hash")).thenReturn(true);
        when(tokenService.issue(any(), anyString(), anyString(), anyInt())).thenReturn("jwt");

        assertThat(service.login(dto("ivan", "secret"))).isPresent();
        verify(passwordHasher, times(1)).matches(anyString(), anyString());
    }

    @Test
    void login_byEmail_stillSingleMatches() {
        UiUserEntity u = user("ivan", "ivan@example.com");
        when(repository.findByUsername("ivan@example.com")).thenReturn(Optional.empty());
        when(repository.findByEmail("ivan@example.com")).thenReturn(Optional.of(u));
        when(passwordHasher.matches("secret", "real-hash")).thenReturn(true);
        when(tokenService.issue(any(), anyString(), anyString(), anyInt())).thenReturn("jwt");

        assertThat(service.login(dto("ivan@example.com", "secret"))).isPresent();
        verify(passwordHasher, times(1)).matches(anyString(), anyString());
    }

    @Test
    void login_systemAccountWithoutEmailOrPassword_refusedBothWays() {
        UiUserEntity sys = new UiUserEntity();
        sys.setId(UUID.randomUUID());
        sys.setUsername("svc");
        sys.setEmail(null);
        sys.setPasswordHash("real-hash");
        sys.setActive(true);
        sys.setRole("USER");
        when(repository.findByUsername("svc")).thenReturn(Optional.of(sys));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);

        assertThat(service.login(dto("svc", "anything"))).isEmpty();

        when(repository.findByUsername("somesvc@example.com")).thenReturn(Optional.empty());
        when(repository.findByEmail("somesvc@example.com")).thenReturn(Optional.empty());
        when(passwordHasher.hash("anything")).thenReturn("dummy");
        assertThat(service.login(dto("somesvc@example.com", "anything"))).isEmpty();
    }
}
