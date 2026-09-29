package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.contract.dto.MailCheckResultDTO;
import com.zorrodev.bpm.contract.dto.MailSettingsDTO;
import com.zorrodev.bpm.engine.entity.MailSettingsEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.MailSettingsRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * WO-INT-6: super-admin mail settings management.
 * <ul>
 *   <li>GET returns settings with the password NEVER included (only a passwordSet flag).</li>
 *   <li>PUT saves (encrypting the password) and writes an audit entry WITHOUT the password value.</li>
 *   <li>WO-INT-8: {@code checkConnection} probes the CURRENT (possibly unsaved) form values, sends
 *       no email. {@code testSendToSelf} sends a real test email but only from the SAVED config
 *       ({@link MailConfigResolver}) — no password (or any other value) travels from the frontend.
 *       Both recipients are always the caller themselves, so neither routes through
 *       MailRecipientPolicy (P-66 resolution: the recipient is always the principal).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailSettingsService {

    private final MailSettingsRepository settingsRepository;
    private final MailSettingsCrypto crypto;
    private final UiUserRepository uiUserRepository;
    private final AuditLogService auditLogService;
    private final MailTransportFactory transportFactory;
    private final MailHealthService mailHealthService;
    private final MailConfigResolver configResolver;
    private final MailActionRateLimiter rateLimiter;

    public MailSettingsDTO getSettings() {
        MailSettingsDTO dto = new MailSettingsDTO();
        Optional<MailSettingsEntity> row = settingsRepository.findFirstByOrderByIdAsc();
        if (row.isEmpty()) {
            dto.setPasswordSet(false);
            return dto;
        }
        MailSettingsEntity e = row.get();
        dto.setHost(e.getHost());
        dto.setPort(e.getPort());
        dto.setUsername(e.getUsername());
        dto.setFrom(e.getSender());
        dto.setAllowedRecipients(e.getAllowedRecipients());
        dto.setPasswordSet(e.getPasswordEncrypted() != null && !e.getPasswordEncrypted().isBlank());
        // password field is intentionally left null
        return dto;
    }

    public MailSettingsDTO saveSettings(MailSettingsDTO dto, Principal principal) {
        requireSuperAdmin(principal);

        MailSettingsEntity e = settingsRepository.findFirstByOrderByIdAsc().orElseGet(() -> {
            MailSettingsEntity ne = new MailSettingsEntity();
            ne.setCreatedAt(Instant.now());
            return ne;
        });
        e.setId(MailSettingsEntity.SINGLE_ROW_ID);

        List<String> changed = new ArrayList<>();
        if (!same(e.getHost(), dto.getHost())) { e.setHost(dto.getHost()); changed.add("host"); }
        if (!same(e.getPort(), dto.getPort())) { e.setPort(dto.getPort()); changed.add("port"); }
        if (!same(e.getUsername(), dto.getUsername())) { e.setUsername(dto.getUsername()); changed.add("username"); }
        if (!same(e.getSender(), dto.getFrom())) { e.setSender(dto.getFrom()); changed.add("from"); }
        if (!same(e.getAllowedRecipients(), dto.getAllowedRecipients())) {
            e.setAllowedRecipients(dto.getAllowedRecipients()); changed.add("allowedRecipients");
        }

        String plainPassword = dto.getPassword() != null ? new String(dto.getPassword()) : null;
        if (plainPassword != null && !plainPassword.isBlank()) {
            if (!crypto.isConfigured()) {
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR,
                    "Mail password encryption key is not configured (ZORROBPM_MAIL_PASSWORD_ENCRYPTION_KEY)");
            }
            e.setPasswordEncrypted(crypto.encrypt(plainPassword));
            changed.add("password");
        }

        e.setUpdatedAt(Instant.now());
        settingsRepository.save(e);

        // Criterion 8: every change is audited; the password value is NEVER included.
        String target = changed.isEmpty() ? "none" : String.join(",", changed);
        auditLogService.record(principal, "MAIL_SETTINGS_UPDATE", null, target);

        return getSettings();
    }

    /**
     * WO-INT-8 criterion 1: "Проверить" — probes the CURRENT form values (may be unsaved),
     * sends no email. Reuses {@link MailHealthService}'s probing logic, only the source of the
     * host/port/username/password changed (not saved config, the raw form values).
     */
    public MailCheckResultDTO checkConnection(MailSettingsDTO dto, Principal principal) {
        requireSuperAdmin(principal);

        if (dto == null || dto.getHost() == null || dto.getHost().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Host is required to check the connection");
        }

        UUID callerId = principalUserId(principal);
        if (!rateLimiter.tryAcquire(callerId)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                "Too many mail check/test requests, try again later");
        }

        Boolean reachable = mailHealthService.probeReachable(
            dto.getHost(), dto.getPort(), dto.getUsername(), dto.getPassword());
        boolean ok = Boolean.TRUE.equals(reachable);

        MailCheckResultDTO result = new MailCheckResultDTO();
        result.setReachable(ok);
        result.setErrorCode(ok ? null : "UNREACHABLE");
        return result;
    }

    /**
     * WO-INT-8 criterion 2: "Отправить тестовое письмо" — a REAL send, but only from the SAVED
     * config ({@link MailConfigResolver#getEffectiveConfig()}), never from the request body — the
     * frontend no longer sends a DTO here at all, so there is no password (or any other value) to
     * smuggle in. Available only once a config is actually saved (400 otherwise).
     */
    public void testSendToSelf(Principal principal) {
        requireSuperAdmin(principal);

        UUID selfId = principalUserId(principal);

        // Validate BEFORE consuming a rate-limit token (same order as checkConnection) — a caller
        // with no email or an unsaved config would otherwise burn their hourly budget on every
        // retry without ever reaching the SMTP server, and lock themselves out of the action that
        // WOULD have worked once they fixed the real problem.
        UiUserEntity self = uiUserRepository.findById(selfId).orElse(null);
        if (self == null || self.getEmail() == null || self.getEmail().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST,
                "У вашей учётки нет email, некуда слать тестовое письмо");
        }
        String selfEmail = self.getEmail();

        ResolvedMailConfig cfg = configResolver.getEffectiveConfig();
        if (cfg == null || cfg.host() == null || cfg.host().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Mail settings are not saved yet");
        }

        if (!rateLimiter.tryAcquire(selfId)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                "Too many mail check/test requests, try again later");
        }

        JavaMailSender sender = transportFactory.build(cfg);

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false);
            helper.setFrom(cfg.from());
            helper.setTo(selfEmail);
            helper.setSubject("ZBPM — Test Email");
            helper.setText(renderTestEmailBody(selfEmail), false);

            sender.send(message);
        } catch (Exception e) {
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Test email to self {} failed: {}", selfEmail, cause);
            throw new ResponseStatusException(BAD_GATEWAY, "SMTP error: " + cause);
        }
    }

    private String renderTestEmailBody(String recipient) {
        try (var is = new ClassPathResource("mail/test-email.txt").getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8)
                .replace("${recipient}", recipient)
                .replace("${timestamp}", Instant.now().toString());
        } catch (Exception e) {
            throw new IllegalStateException("Mail template mail/test-email.txt is missing", e);
        }
    }

    private void requireSuperAdmin(Principal principal) {
        if (principal == null || !principal.isSuperAdmin()) {
            throw new ResponseStatusException(FORBIDDEN, "Mail settings require SUPER_ADMIN");
        }
    }

    private UUID principalUserId(Principal principal) {
        if (principal instanceof Principal.UserPrincipal u) {
            return u.userId();
        }
        throw new ResponseStatusException(FORBIDDEN, "Mail settings require a user principal");
    }

    private static boolean same(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
