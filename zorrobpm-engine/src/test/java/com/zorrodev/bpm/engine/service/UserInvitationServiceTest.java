package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.entity.PasswordTokenEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserInvitationServiceTest {

    @Mock UiUserRepository userRepository;
    @Mock PasswordTokenRepository tokenRepository;
    @Mock TokenService tokenService;
    @Mock PasswordHasher passwordHasher;
    @Mock MailSender mailSender;
    @Mock AuditLogService auditLogService;
    @Mock PasswordResetRateLimiter rateLimiter;

    @InjectMocks UserInvitationService service;

    private final Principal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    private UiUserEntity humanUser;
    private UiUserEntity systemUser;

    @BeforeEach
    void setup() {
        humanUser = new UiUserEntity();
        humanUser.setId(UUID.randomUUID());
        humanUser.setUsername("invitee");
        humanUser.setEmail("invitee@corp.kz");
        humanUser.setUserType("HUMAN");

        systemUser = new UiUserEntity();
        systemUser.setId(UUID.randomUUID());
        systemUser.setUsername("svc");
        systemUser.setEmail("svc@corp.kz");
        systemUser.setUserType("SYSTEM");

        when(tokenService.generateRefreshToken()).thenReturn("RAW-TOKEN");
        when(tokenService.hashToken("RAW-TOKEN")).thenReturn("HASHED-TOKEN");
        lenient().when(passwordHasher.hash(anyString())).thenReturn("NEW-HASH");
        lenient().when(rateLimiter.tryAcquireForEmail(any())).thenReturn(true);
        lenient().when(rateLimiter.tryAcquireForIp(any())).thenReturn(true);
    }

    // ---- Criterion 1: invitation creates the user without a usable password, sends a link ----
    @Test
    void createInvitation_sendsEmailWithTokenLink() {
        when(userRepository.findById(humanUser.getId())).thenReturn(Optional.of(humanUser));

        service.createInvitation(humanUser.getId(), admin);

        ArgumentCaptor<PasswordTokenEntity> captor = ArgumentCaptor.forClass(PasswordTokenEntity.class);
        verify(tokenRepository).save(captor.capture());
        PasswordTokenEntity saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("INVITE");
        assertThat(saved.getUserId()).isEqualTo(humanUser.getId());

        verify(mailSender).send(eq("invitee@corp.kz"), anyString(), contains("RAW-TOKEN"));
        verify(auditLogService).record(eq(admin), eq("USER_INVITE_SENT"), isNull(), eq(humanUser.getId().toString()));
    }

    // ---- Criterion 8: token stored hashed, never raw ----
    @Test
    void createInvitation_storesHashNotRawToken() {
        when(userRepository.findById(humanUser.getId())).thenReturn(Optional.of(humanUser));

        service.createInvitation(humanUser.getId(), admin);

        ArgumentCaptor<PasswordTokenEntity> captor = ArgumentCaptor.forClass(PasswordTokenEntity.class);
        verify(tokenRepository).save(captor.capture());
        // POF: if the code persisted the raw value, this assertion fails (criterion 8).
        assertThat(captor.getValue().getTokenHash()).isEqualTo("HASHED-TOKEN");
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo("RAW-TOKEN");
    }

    @Test
    void createInvitation_rejectsSystemAccount() {
        when(userRepository.findById(systemUser.getId())).thenReturn(Optional.of(systemUser));
        assertThatThrownBy(() -> service.createInvitation(systemUser.getId(), admin))
                .isInstanceOf(EngineException.class);
        verify(mailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void createInvitation_rejectsMissingEmail() {
        humanUser.setEmail(null);
        when(userRepository.findById(humanUser.getId())).thenReturn(Optional.of(humanUser));
        assertThatThrownBy(() -> service.createInvitation(humanUser.getId(), admin))
                .isInstanceOf(EngineException.class);
    }

    // ---- Criterion 9: re-invite invalidates prior outstanding token ----
    @Test
    void createInvitation_invalidatesPriorInviteTokens() {
        when(userRepository.findById(humanUser.getId())).thenReturn(Optional.of(humanUser));

        service.createInvitation(humanUser.getId(), admin);

        verify(tokenRepository).invalidateByUserAndType(eq(humanUser.getId()), eq("INVITE"), any(Instant.class));
    }

    // ---- Criterion 4 + 6: consume sets password and makes token single-use ----
    @Test
    void consumeToken_setsPassword_andIsSingleUse() {
        PasswordTokenEntity token = token();
        when(tokenRepository.findByTokenHashAndUsedFalse("HASHED-TOKEN")).thenReturn(Optional.of(token), Optional.empty());
        when(userRepository.findById(humanUser.getId())).thenReturn(Optional.of(humanUser));

        service.consumeToken("RAW-TOKEN", "NewPassw0rd!");

        ArgumentCaptor<UiUserEntity> userCaptor = ArgumentCaptor.forClass(UiUserEntity.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("NEW-HASH");

        ArgumentCaptor<PasswordTokenEntity> tokenCaptor = ArgumentCaptor.forClass(PasswordTokenEntity.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        assertThat(tokenCaptor.getValue().isUsed()).isTrue();

        // Second use must fail (criterion 6).
        assertThatThrownBy(() -> service.consumeToken("RAW-TOKEN", "OtherPass1!"))
                .isInstanceOf(EngineException.class);
    }

    // ---- Criterion 7: expired token rejected ----
    @Test
    void consumeToken_expiredTokenRejected() {
        PasswordTokenEntity token = token();
        token.setExpiresAt(Instant.now().minusSeconds(60));
        when(tokenRepository.findByTokenHashAndUsedFalse("HASHED-TOKEN")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.consumeToken("RAW-TOKEN", "NewPassw0rd!"))
                .isInstanceOf(EngineException.class);
    }

    // ---- Criterion 10: admin reset rejects system / missing email, sends reset email ----
    @Test
    void adminReset_sendsResetEmail() {
        when(userRepository.findById(humanUser.getId())).thenReturn(Optional.of(humanUser));
        service.adminReset(humanUser.getId(), admin);
        verify(mailSender).send(eq("invitee@corp.kz"), anyString(), contains("RAW-TOKEN"));
        verify(auditLogService).record(eq(admin), eq("USER_RESET_SENT"), isNull(), eq(humanUser.getId().toString()));
    }

    @Test
    void adminReset_rejectsSystemAccount() {
        when(userRepository.findById(systemUser.getId())).thenReturn(Optional.of(systemUser));
        assertThatThrownBy(() -> service.adminReset(systemUser.getId(), admin))
                .isInstanceOf(EngineException.class);
    }

    // ---- Criterion 12: forgot-password is enumeration-safe (service level) ----
    @Test
    void requestReset_sendsEmailForEligibleUser_only() {
        UiUserEntity eligible = humanUser;
        when(userRepository.findByEmail("invitee@corp.kz")).thenReturn(Optional.of(eligible));

        service.requestReset("invitee@corp.kz", "1.2.3.4");
        verify(mailSender).send(eq("invitee@corp.kz"), anyString(), contains("RAW-TOKEN"));

        // Unknown email → no email, no exception (enumeration-safe).
        clearInvocations(mailSender);
        when(userRepository.findByEmail("ghost@corp.kz")).thenReturn(Optional.empty());
        service.requestReset("ghost@corp.kz", "1.2.3.4");
        verify(mailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void requestReset_rateLimited_doesNotSend() {
        when(rateLimiter.tryAcquireForEmail("invitee@corp.kz")).thenReturn(false);
        service.requestReset("invitee@corp.kz", "1.2.3.4");
        verify(mailSender, never()).send(anyString(), anyString(), anyString());
    }

    private PasswordTokenEntity token() {
        PasswordTokenEntity t = new PasswordTokenEntity();
        t.setId(UUID.randomUUID());
        t.setUserId(humanUser.getId());
        t.setType("INVITE");
        t.setTokenHash("HASHED-TOKEN");
        t.setEmail("invitee@corp.kz");
        t.setExpiresAt(Instant.now().plusSeconds(3600));
        t.setUsed(false);
        t.setCreatedAt(Instant.now());
        return t;
    }
}
