package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.contract.dto.MailCheckResultDTO;
import com.zorrodev.bpm.contract.dto.MailSettingsDTO;
import com.zorrodev.bpm.engine.entity.MailSettingsEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.MailSettingsRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailSettingsServiceTest {

    private static final String KEY_HEX =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Mock MailSettingsRepository settingsRepository;
    @Mock UiUserRepository uiUserRepository;
    @Mock AuditLogService auditLogService;
    @Mock MailTransportFactory transportFactory;
    @Mock JavaMailSenderImpl javaMailSender;
    @Mock MailHealthService mailHealthService;
    @Mock MailConfigResolver configResolver;
    @Mock com.zorrodev.bpm.engine.service.PgRateLimiter pgRateLimiter;

    private MailSettingsCrypto crypto;
    private MailActionRateLimiter rateLimiter;

    @BeforeEach
    void setupRateLimiter() {
        crypto = new MailSettingsCrypto(KEY_HEX);
        rateLimiter = new MailActionRateLimiter(pgRateLimiter);
    }

    private MailSettingsService service() {
        return new MailSettingsService(settingsRepository, crypto, uiUserRepository, auditLogService,
            transportFactory, mailHealthService, configResolver, rateLimiter);
    }

    private Principal.UserPrincipal superAdmin() {
        return new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    }

    @Test
    void criterion5_getSettings_neverReturnsPassword() {
        MailSettingsEntity e = new MailSettingsEntity();
        e.setHost("h"); e.setPort(587); e.setUsername("u");
        e.setPasswordEncrypted(crypto.encrypt("topsecret"));
        e.setSender("f@x"); e.setAllowedRecipients("a@x");
        when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(e));

        MailSettingsDTO dto = service().getSettings();

        assertThat(dto.getPassword()).isNull();
        assertThat(dto.getPasswordSet()).isTrue();
        assertThat(dto.getHost()).isEqualTo("h");
    }

    @Test
    void criterion5_saveSettings_storesEncryptedPassword_notPlaintext() {
        when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        MailSettingsDTO in = new MailSettingsDTO();
        in.setHost("h"); in.setPort(587); in.setUsername("u");
        in.setPassword("topsecret"); in.setFrom("f@x"); in.setAllowedRecipients("a@x");

        service().saveSettings(in, superAdmin());

        ArgumentCaptor<MailSettingsEntity> captor = ArgumentCaptor.forClass(MailSettingsEntity.class);
        verify(settingsRepository).save(captor.capture());
        MailSettingsEntity saved = captor.getValue();
        assertThat(saved.getPasswordEncrypted()).isNotEqualTo("topsecret");
        assertThat(crypto.decrypt(saved.getPasswordEncrypted())).isEqualTo("topsecret");
    }

    @Test
    void criterion8_saveSettings_auditsWithoutPasswordValue() {
        when(settingsRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        MailSettingsDTO in = new MailSettingsDTO();
        in.setHost("h"); in.setPort(587); in.setUsername("u"); in.setPassword("topsecret");
        in.setFrom("f@x"); in.setAllowedRecipients("");

        service().saveSettings(in, superAdmin());

        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> targetCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(any(Principal.class), actionCaptor.capture(), any(), targetCaptor.capture());
        assertThat(actionCaptor.getValue()).isEqualTo("MAIL_SETTINGS_UPDATE");
        assertThat(targetCaptor.getValue()).contains("password");
        assertThat(targetCaptor.getValue()).doesNotContain("topsecret");
    }

    @Test
    void criterion4_saveSettings_nonSuperAdmin_forbidden() {
        Principal.UserPrincipal user = new Principal.UserPrincipal(UUID.randomUUID(), "u", "USER");
        MailSettingsDTO in = new MailSettingsDTO();
        assertThatThrownBy(() -> service().saveSettings(in, user))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void criterion2_testSendToSelf_noEmail_clearError_notNpe() {
        UUID selfId = UUID.randomUUID();
        Principal.UserPrincipal admin = new Principal.UserPrincipal(selfId, "admin", "SUPER_ADMIN");
        UiUserEntity self = new UiUserEntity();
        self.setEmail(""); // blank email
        when(uiUserRepository.findById(selfId)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> service().testSendToSelf(admin))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> {
                ResponseStatusException r = (ResponseStatusException) e;
                assertThat(r.getStatusCode().value()).isEqualTo(400);
                assertThat(r.getReason()).contains("нет email");
            });
        verify(transportFactory, never()).build(any(ResolvedMailConfig.class));
    }

    @Test
    void criterion2_testSendToSelf_notSaved_clearError_not500() {
        UUID selfId = UUID.randomUUID();
        Principal.UserPrincipal admin = new Principal.UserPrincipal(selfId, "admin", "SUPER_ADMIN");
        UiUserEntity self = new UiUserEntity();
        self.setEmail("admin@corp.kz");
        when(uiUserRepository.findById(selfId)).thenReturn(Optional.of(self));
        when(configResolver.getEffectiveConfig()).thenReturn(
            new ResolvedMailConfig(null, null, null, null, null, null));

        assertThatThrownBy(() -> service().testSendToSelf(admin))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
        verify(transportFactory, never()).build(any(ResolvedMailConfig.class));
    }

    @Test
    void criterion2_testSendToSelf_sendsToSelfAddress_usingSavedConfig_noBodyPassword() throws Exception {
        UUID selfId = UUID.randomUUID();
        Principal.UserPrincipal admin = new Principal.UserPrincipal(selfId, "admin", "SUPER_ADMIN");
        UiUserEntity self = new UiUserEntity();
        self.setEmail("admin@corp.kz");
        when(uiUserRepository.findById(selfId)).thenReturn(Optional.of(self));
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.x", 587, "u", "p", "f@x", "");
        when(configResolver.getEffectiveConfig()).thenReturn(cfg);
        when(transportFactory.build(cfg)).thenReturn(javaMailSender);

        Session session = Session.getInstance(new Properties());
        when(javaMailSender.createMimeMessage()).thenReturn(new MimeMessage(session));
        AtomicReference<MimeMessage> sent = new AtomicReference<>();
        doAnswer(inv -> { sent.set(inv.getArgument(0)); return null; }).when(javaMailSender).send(any(MimeMessage.class));

        service().testSendToSelf(admin);

        verify(javaMailSender).send(any(MimeMessage.class));
        String recipients = java.util.Arrays.toString(sent.get().getRecipients(jakarta.mail.Message.RecipientType.TO));
        assertThat(recipients).contains("admin@corp.kz");
    }

    @Test
    void criterion2_testSendToSelf_smtpError_returnedVerbatim() throws Exception {
        UUID selfId = UUID.randomUUID();
        Principal.UserPrincipal admin = new Principal.UserPrincipal(selfId, "admin", "SUPER_ADMIN");
        UiUserEntity self = new UiUserEntity();
        self.setEmail("admin@corp.kz");
        when(uiUserRepository.findById(selfId)).thenReturn(Optional.of(self));
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.x", 587, "u", "p", "f@x", "");
        when(configResolver.getEffectiveConfig()).thenReturn(cfg);
        when(transportFactory.build(cfg)).thenReturn(javaMailSender);
        when(javaMailSender.createMimeMessage()).thenReturn(
            new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("550 relay access denied"))
            .when(javaMailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> service().testSendToSelf(admin))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> {
                ResponseStatusException r = (ResponseStatusException) e;
                assertThat(r.getStatusCode().value()).isEqualTo(502);
                assertThat(r.getReason()).contains("550 relay access denied");
            });
    }

    @Test
    void criterion2_testSendToSelf_rateLimited_429_notException() {
        UUID selfId = UUID.randomUUID();
        Principal.UserPrincipal admin = new Principal.UserPrincipal(selfId, "admin", "SUPER_ADMIN");
        rateLimiter.setCapacity(1);
        when(pgRateLimiter.tryConsume(anyString(), eq(1), eq(3600)))
            .thenReturn(0L).thenReturn(3600L);
        UiUserEntity self = new UiUserEntity();
        self.setEmail("admin@corp.kz");
        when(uiUserRepository.findById(selfId)).thenReturn(Optional.of(self));
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.x", 587, "u", "p", "f@x", "");
        when(configResolver.getEffectiveConfig()).thenReturn(cfg);
        when(transportFactory.build(cfg)).thenReturn(javaMailSender);
        when(javaMailSender.createMimeMessage()).thenReturn(
            new MimeMessage(Session.getInstance(new Properties())));

        service().testSendToSelf(admin); // consumes the only token

        assertThatThrownBy(() -> service().testSendToSelf(admin))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(429));
    }

    @Test
    void criterion1_checkConnection_reachable_noSaveRequired_noEmailSent() {
        UUID selfId = UUID.randomUUID();
        Principal.UserPrincipal admin = new Principal.UserPrincipal(selfId, "admin", "SUPER_ADMIN");
        MailSettingsDTO in = new MailSettingsDTO();
        in.setHost("smtp.x"); in.setPort(587); in.setUsername("u"); in.setPassword("p");
        when(mailHealthService.probeReachable("smtp.x", 587, "u", "p")).thenReturn(true);

        MailCheckResultDTO result = service().checkConnection(in, admin);

        assertThat(result.isReachable()).isTrue();
        assertThat(result.getErrorCode()).isNull();
        verify(settingsRepository, never()).save(any());
        verify(transportFactory, never()).build(any(ResolvedMailConfig.class));
        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void criterion1_checkConnection_unreachable_falseWithErrorCode() {
        UUID selfId = UUID.randomUUID();
        Principal.UserPrincipal admin = new Principal.UserPrincipal(selfId, "admin", "SUPER_ADMIN");
        MailSettingsDTO in = new MailSettingsDTO();
        in.setHost("bad.host"); in.setPort(1);
        when(mailHealthService.probeReachable("bad.host", 1, null, null)).thenReturn(false);

        MailCheckResultDTO result = service().checkConnection(in, admin);

        assertThat(result.isReachable()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("UNREACHABLE");
    }

    @Test
    void criterion1_checkConnection_noHost_clearError_notNpe() {
        UUID selfId = UUID.randomUUID();
        Principal.UserPrincipal admin = new Principal.UserPrincipal(selfId, "admin", "SUPER_ADMIN");
        MailSettingsDTO in = new MailSettingsDTO();

        assertThatThrownBy(() -> service().checkConnection(in, admin))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
        verify(mailHealthService, never()).probeReachable(any(), any(), any(), any());
    }

    @Test
    void criterion3_checkConnection_nonSuperAdmin_forbidden() {
        Principal.UserPrincipal user = new Principal.UserPrincipal(UUID.randomUUID(), "u", "USER");
        MailSettingsDTO in = new MailSettingsDTO();
        in.setHost("h");
        assertThatThrownBy(() -> service().checkConnection(in, user))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
        verify(mailHealthService, never()).probeReachable(any(), any(), any(), any());
    }

    @Test
    void criterion5_checkConnection_rateLimited_429() {
        UUID selfId = UUID.randomUUID();
        Principal.UserPrincipal admin = new Principal.UserPrincipal(selfId, "admin", "SUPER_ADMIN");
        rateLimiter.setCapacity(1);
        when(pgRateLimiter.tryConsume(anyString(), eq(1), eq(3600)))
            .thenReturn(0L).thenReturn(3600L);
        MailSettingsDTO in = new MailSettingsDTO();
        in.setHost("smtp.x");
        when(mailHealthService.probeReachable(any(), any(), any(), any())).thenReturn(true);

        service().checkConnection(in, admin); // consumes the only token

        assertThatThrownBy(() -> service().checkConnection(in, admin))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(429));
    }

    @Test
    void criterion5_testSendToSelf_validationDoesNotConsumeRateLimitToken() throws Exception {
        UUID selfId = UUID.randomUUID();
        Principal.UserPrincipal admin = new Principal.UserPrincipal(selfId, "admin", "SUPER_ADMIN");
        rateLimiter.setCapacity(1);

        // First call: invalid (no email) -> 400, should NOT consume the token
        UiUserEntity noEmail = new UiUserEntity();
        noEmail.setEmail("");
        when(uiUserRepository.findById(selfId)).thenReturn(Optional.of(noEmail));

        assertThatThrownBy(() -> service().testSendToSelf(admin))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));

        // Second call: valid -> should succeed, not 429, proving first call didn't burn the token
        UiUserEntity validUser = new UiUserEntity();
        validUser.setEmail("admin@corp.kz");
        when(uiUserRepository.findById(selfId)).thenReturn(Optional.of(validUser));
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.x", 587, "u", "p", "f@x", "");
        when(configResolver.getEffectiveConfig()).thenReturn(cfg);
        when(transportFactory.build(cfg)).thenReturn(javaMailSender);
        when(javaMailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        // send succeeds
        service().testSendToSelf(admin);
        verify(javaMailSender).send(any(MimeMessage.class));
    }
}
