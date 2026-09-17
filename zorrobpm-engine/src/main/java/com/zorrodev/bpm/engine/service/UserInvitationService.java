package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.entity.PasswordTokenEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.RefreshTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.AdminPasswordValidator;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * WO-ACL-18: invitation + password-reset links.
 *
 * <p>One mechanism for both: a single-use token (stored only as its SHA-256 hash) that, when
 * consumed, sets the user's password. Invitation tokens are issued when an admin creates a user
 * in INVITE mode; reset tokens are issued by the admin "reset password" button or by the public
 * "forgot password" flow.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserInvitationService {

    public static final String TYPE_INVITE = "INVITE";
    public static final String TYPE_RESET = "RESET";
    /** WO-REG-2: email-ownership proof for self-registration (no password inside). */
    public static final String TYPE_EMAIL_VERIFY = "EMAIL_VERIFY";

    private final UiUserRepository userRepository;
    private final PasswordTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenService tokenService;
    private final PasswordHasher passwordHasher;
    private final MailSender mailSender;
    private final AuditLogService auditLogService;
    private final PasswordResetRateLimiter rateLimiter;

    @Value("${zorrobpm.mail.link-base-url:http://localhost:5173}")
    private String linkBaseUrl;
    @Value("${zorrobpm.security.invitation-ttl-hours:24}")
    private int invitationTtlHours;
    @Value("${zorrobpm.security.reset-ttl-hours:24}")
    private int resetTtlHours;

    /** Issue + email an invitation for a just-created user (INVITE path). */
    @Transactional
    public String createInvitation(UUID userId, Principal principal) {
        UiUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new EngineException("User not found"));
        if ("SYSTEM".equals(user.getUserType())) {
            throw new EngineException("System accounts cannot be invited");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new EngineException("User has no email — cannot send invitation");
        }
        invalidatePriorTokens(userId, TYPE_INVITE);
        String raw = issueToken(userId, TYPE_INVITE, user.getEmail(), invitationTtlHours);
        String link = linkBaseUrl + "/ui/accept-invitation?token=" + raw;
        mailSender.send(user.getEmail(), "ZBPM: приглашение в систему",
                "Вас пригласили в ZBPM. Ваш логин: " + user.getUsername()
                        + "\nУстановите пароль по ссылке: " + link);
        auditLogService.record(principal, "USER_INVITE_SENT", null, userId.toString());
        return raw;
    }

    /** Admin "reset password" button — issues a reset link to the user's email. */
    @Transactional
    public String adminReset(UUID userId, Principal principal) {
        UiUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new EngineException("User not found"));
        if ("SYSTEM".equals(user.getUserType())) {
            throw new EngineException("System accounts cannot be reset by email");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new EngineException("User has no email — cannot send reset link");
        }
        // WO-ACL-19 (P1): throttle admin-initiated resets on the recipient email, reusing the
        // same bucket as the public "forgot password" flow so an admin cannot spam reset
        // emails (and invalidate prior tokens) by clicking repeatedly.
        if (!rateLimiter.tryAcquireForEmail(user.getEmail().toLowerCase(java.util.Locale.ROOT))) {
            throw new EngineException("Too many reset requests for this email, please try again later");
        }
        invalidatePriorTokens(userId, TYPE_RESET);
        String raw = issueToken(userId, TYPE_RESET, user.getEmail(), resetTtlHours);
        String link = linkBaseUrl + "/ui/reset-password?token=" + raw;
        mailSender.send(user.getEmail(), "ZBPM: сброс пароля",
                "Сбросьте пароль по ссылке: " + link);
        auditLogService.record(principal, "USER_RESET_SENT", null, userId.toString());
        return raw;
    }

    /**
     * WO-ACL-18 criterion 12: public "forgot password" — ENUMERATION-SAFE.
     * Always returns normally (the HTTP layer answers 200 identically) regardless of whether the
     * email maps to a real, eligible account. Rate-limited per email AND per client IP.
     */
    @Transactional
    public void requestReset(String email, String clientIp) {
        if (email == null || email.isBlank()) return;
        if (!rateLimiter.tryAcquireForEmail(email.toLowerCase(java.util.Locale.ROOT))) return;
        if (!rateLimiter.tryAcquireForIp(clientIp)) return;
        Optional<UiUserEntity> user = userRepository.findByEmail(email.toLowerCase(java.util.Locale.ROOT));
        if (user.isEmpty() || "SYSTEM".equals(user.get().getUserType())
                || user.get().getEmail() == null || user.get().getEmail().isBlank()) {
            return;
        }
        invalidatePriorTokens(user.get().getId(), TYPE_RESET);
        String raw = issueToken(user.get().getId(), TYPE_RESET, user.get().getEmail(), resetTtlHours);
        String link = linkBaseUrl + "/ui/reset-password?token=" + raw;
        mailSender.send(user.get().getEmail(), "ZBPM: сброс пароля",
                "Сбросьте пароль по ссылке: " + link);
    }

    /** Consume a one-time token (INVITE or RESET) and set the password. */
    @Transactional
    public void consumeToken(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) throw new EngineException("Token is required");
        if (newPassword == null || newPassword.isBlank()) throw new EngineException("Password is required");
        if (AdminPasswordValidator.isWeak(newPassword)) {
            throw new EngineException("Password does not meet complexity requirements");
        }

        String hash = tokenService.hashToken(rawToken);
        PasswordTokenEntity token = tokenRepository.findByTokenHashAndUsedFalse(hash)
                .orElseThrow(() -> new EngineException("Invalid or expired token"));
        // WO-SEC-63 (F03): only password-path token types may enter here — an EMAIL_VERIFY
        // token (email-ownership proof) must never double as a password setter.
        if (!TYPE_INVITE.equals(token.getType()) && !TYPE_RESET.equals(token.getType())) {
            throw new EngineException("Invalid or expired token");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new EngineException("Invalid or expired token");
        }
        UiUserEntity user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new EngineException("User not found"));

        user.setPasswordHash(passwordHasher.hash(newPassword));
        user.setForcePasswordChange(false);
        // WO-SEC-63 (F03): password recovery must revoke live sessions like every other
        // password change (self-service/admin — same guarantee): a captured refresh token
        // must not survive the legitimate owner's recovery, and outstanding access
        // tokens die via the version bump. Same transaction as the password write and
        // the single-use consume below — all-or-nothing.
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokenRepository.revokeAllByUserId(user.getId());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        // WO-ACL-18 criterion 6: the token is now spent — a second use must fail.
        // Atomic, DB-level single-use: only ONE concurrent consumer can flip used=false -> true.
        int consumed = tokenRepository.consumeByTokenHash(hash, Instant.now());
        if (consumed == 0) {
            // Lost the race (or already consumed) — reject rather than silently succeed.
            throw new EngineException("Invalid or expired token");
        }
        auditLogService.record(null, "USER_PASSWORD_SET", null, user.getId().toString());
    }

    /** True while an unexpired invitation token is outstanding (criterion 5). */
    public boolean isInvited(UUID userId) {
        return tokenRepository.existsByUserIdAndTypeAndUsedFalseAndExpiresAtAfter(
                userId, TYPE_INVITE, Instant.now());
    }

    /**
     * WO-REG-2: consumes an EMAIL_VERIFY token — same validation + atomic single-use
     * consume as the password flow, but WITHOUT setting a password (the registrant
     * already chose one at registration). Returns the verified user id for the caller
     * (WO-REG-4) to stamp {@code emailVerifiedAt}. RESET/INVITE tokens are refused
     * here even if valid — different meaning, must not double as email proof.
     */
    @Transactional
    public UUID consumeEmailVerifyToken(String rawToken) {
        PasswordTokenEntity token = findValidEmailVerifyToken(rawToken);
        UUID userId = token.getUserId();
        int consumed = tokenRepository.consumeByTokenHash(tokenService.hashToken(rawToken), Instant.now());
        if (consumed == 0) {
            throw new EngineException("Invalid or expired token");
        }
        return userId;
    }

    /**
     * WO-REL-43, раунд 2: read-only фаза {@link #consumeEmailVerifyToken} — SELECT +
     * проверки БЕЗ consume-UPDATE (а значит, без row lock на токене). Нужна
     * {@code SelfRegistrationService.verifyEmail}, чтобы взять row lock на
     * пользователе ДО consume: иначе порядок блокировок verify (токен → юзер)
     * инвертирован относительно cleanup (юзер → токен) = deadlock при столкновении
     * на одной строке (поймано CTO живым прогоном 4/4, не теорией). Разбиение
     * безопасно: single-use решает атомарный UPDATE в consume-фазе — второй
     * конкурент между read и consume проигрывает (consumed==0 → reject), а не
     * дублирует эффект. Package-visible (тот же пакет, least privilege).
     */
    UUID peekEmailVerifyTokenOwner(String rawToken) {
        return findValidEmailVerifyToken(rawToken).getUserId();
    }

    private PasswordTokenEntity findValidEmailVerifyToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new EngineException("Token is required");
        String hash = tokenService.hashToken(rawToken);
        PasswordTokenEntity token = tokenRepository.findByTokenHashAndUsedFalse(hash)
            .filter(t -> TYPE_EMAIL_VERIFY.equals(t.getType()))
            .orElseThrow(() -> new EngineException("Invalid or expired token"));
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new EngineException("Invalid or expired token");
        }
        return token;
    }

    // WO-REG-2: package-visible for the future SelfRegistrationService (WO-REG-3) —
    // same-package reuse without duplicating token generation/hashing. Least privilege:
    // not public API, not protected (no subclassing planned).
    String issueToken(UUID userId, String type, String email, int ttlHours) {
        String raw = tokenService.generateRefreshToken();
        PasswordTokenEntity token = new PasswordTokenEntity();
        token.setId(UUID.randomUUID());
        token.setUserId(userId);
        token.setType(type);
        // Criterion 8: only the hash is persisted — the raw token lives solely in the email link.
        token.setTokenHash(tokenService.hashToken(raw));
        token.setEmail(email);
        token.setExpiresAt(Instant.now().plusSeconds((long) ttlHours * 3600L));
        token.setUsed(false);
        token.setCreatedAt(Instant.now());
        tokenRepository.save(token);
        return raw;
    }

    // WO-REG-2: same visibility note as issueToken above.
    void invalidatePriorTokens(UUID userId, String type) {
        tokenRepository.invalidateByUserAndType(userId, type, Instant.now());
    }
}
