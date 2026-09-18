package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.contract.dto.VerifyEmailDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * WO-REG-3: public self-registration. Reuses instead of duplicating:
 * <ul>
 *   <li>all HUMAN validations + normalization + uniqueness + race translation live
 *       in {@code UiUserService.create} (WO-REG-1) — called as-is;</li>
 *   <li>token issue/invalidate + hashing/TTL live in {@code UserInvitationService}
 *       (WO-REG-2, package-visible, same package here);</li>
 *   <li>rate limiting uses the {@code PasswordResetRateLimiter} class with its own
 *       bean and keys (separate quotas from forgot-password).</li>
 * </ul>
 * Server-decided, never from the client: {@code role="USER"}, {@code userType="HUMAN"},
 * {@code active=false}, {@code registration_status="PENDING_EMAIL_VERIFICATION"}.
 * Conflicts are honest ("username/email taken") by CTO decision — the compensating
 * control is the rate limit, not hiding (unlike enumeration-safe forgot-password).
 * No audit row: {@code AuditLogService} drops null-principal records, and a public
 * endpoint has no principal — nothing to write, not a gap.
 */
@Slf4j
@Service
public class SelfRegistrationService {

    private final UiUserService uiUserService;
    private final UserInvitationService invitationService;
    private final UiUserRepository userRepository;
    private final MailSender mailSender;
    private final AuditLogService auditLogService;
    private final PasswordResetRateLimiter registrationRateLimiter;

    // Explicit constructor (not Lombok): the @Qualifier below is load-bearing —
    // @RequiredArgsConstructor does not propagate field annotations to the ctor
    // parameter, and @Primary would otherwise win over name matching (proven live:
    // the rate test passed with the reset bean's quotas until this ctor landed).
    public SelfRegistrationService(UiUserService uiUserService,
            UserInvitationService invitationService,
            UiUserRepository userRepository,
            MailSender mailSender,
            AuditLogService auditLogService,
            @Qualifier("registrationRateLimiter") PasswordResetRateLimiter registrationRateLimiter) {
        this.uiUserService = uiUserService;
        this.invitationService = invitationService;
        this.userRepository = userRepository;
        this.mailSender = mailSender;
        this.auditLogService = auditLogService;
        this.registrationRateLimiter = registrationRateLimiter;
    }

    @Value("${zorrobpm.mail.link-base-url:http://localhost:5173}")
    private String linkBaseUrl;
    @Value("${zorrobpm.security.email-verify-ttl-hours:24}")
    private int emailVerifyTtlHours;

    @Transactional
    public void register(RegisterDTO dto, String clientIp) {
        // Cheap reject first (mirrors requestReset ordering): per-email AND per-IP,
        // explicit error — registration already discloses taken names, so unlike
        // forgot-password there is nothing to hide by staying silent.
        if (!registrationRateLimiter.tryAcquireForEmail(dto.getEmail())) {
            throw new EngineException("Too many registration attempts, please try again later");
        }
        if (!registrationRateLimiter.tryAcquireForIp(clientIp)) {
            throw new EngineException("Too many registration attempts, please try again later");
        }
        CreateUiUserDTO create = new CreateUiUserDTO();
        create.setUsername(dto.getUsername());
        create.setPassword(dto.getPassword());
        create.setFullName(dto.getFullName());
        create.setEmail(dto.getEmail());
        // Inactive until BOTH gates pass (email proof in WO-REG-4, SUPER_ADMIN in WO-REG-5).
        // Role/userType deliberately unset: create() defaults them to USER/HUMAN server-side.
        create.setActive(false);
        UUID userId = uiUserService.create(create);

        UiUserEntity entity = userRepository.findById(userId)
            .orElseThrow(() -> new NoSuchElementException("User not found"));
        entity.setRegistrationStatus("PENDING_EMAIL_VERIFICATION");
        userRepository.save(entity);

        invitationService.invalidatePriorTokens(userId, UserInvitationService.TYPE_EMAIL_VERIFY);
        String raw = invitationService.issueToken(userId, UserInvitationService.TYPE_EMAIL_VERIFY,
            entity.getEmail(), emailVerifyTtlHours);
        String link = linkBaseUrl + "/ui/verify-email?token=" + raw;
        mailSender.send(entity.getEmail(), "ZBPM: подтверждение почты",
            "Подтвердите почту по ссылке: " + link);
        log.info("Self-registration {} created, verification email queued", userId);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        // WO-REL-43, раунд 2: порядок блокировок ЮЗЕР → ТОКЕН с обеих сторон.
        // Раунд 1 брал lock на токене (consume-UPDATE) ДО лока на юзере, а cleanup
        // идёт наоборот (findByIdForUpdate юзера, затем DELETE токенов) — инверсия
        // порядка = deadlock при столкновении на одной строке (CTO: 4/4 на чистой
        // БД, дебаг дословно в HOLD). Поэтому: read токена БЕЗ лока и без consume
        // (peek), затем FOR UPDATE на юзере, и только потом атомарный consume
        // (UPDATE токена). Single-use не ослаблен: решает consume-UPDATE —
        // конкурент между peek и consume проигрывает (consumed==0 → reject).
        UUID userId = invitationService.peekEmailVerifyTokenOwner(rawToken);
        UiUserEntity user = userRepository.findByIdForUpdate(userId)
            // Cleanup уже удалил строку: ссылка устарела, не сломана — честная
            // 4xx, никогда raw 500 и никогда тихий успех в никуда (критерий 2).
            .orElseThrow(() -> new EngineException(
                "Registration expired — the verification link is no longer valid, please register again"));
        // Атомарный single-use consume ПОСЛЕ лока: проигравший гонку падает той же
        // фразой, что протухший токен (одна фраза, no enumeration).
        invitationService.consumeEmailVerifyToken(rawToken);

        // 2. Idempotent status transition: only PENDING_EMAIL_VERIFICATION moves forward.
        // If already PENDING_APPROVAL/ACTIVE/REJECTED (repeat click, or admin raced ahead),
        // leave state as is — already verified is not an error to be rolled back.
        if (!"PENDING_EMAIL_VERIFICATION".equals(user.getRegistrationStatus())) {
            log.info("Verify email idempotent for {}: status {} already beyond PENDING_EMAIL_VERIFICATION",
                userId, user.getRegistrationStatus());
            return;
        }
        user.setEmailVerifiedAt(java.time.Instant.now());
        user.setRegistrationStatus("PENDING_APPROVAL");
        userRepository.save(user);
        auditLogService.record(null, "USER_EMAIL_VERIFIED", null, userId.toString());

        // 3. Notify every live SUPER_ADMIN (active=true, role SUPER_ADMIN). No live ones
        // must not fail the user's own verification — log warn, continue (criterion 5).
        try {
            java.util.List<UiUserEntity> supers = userRepository.findByRoleAndActive("SUPER_ADMIN", true);
            if (supers.isEmpty()) {
                log.warn("No active SUPER_ADMIN to notify for verified user {}", userId);
            } else {
                String adminLink = linkBaseUrl + "/ui/admin/registrations";
                for (UiUserEntity admin : supers) {
                    if (admin.getEmail() == null || admin.getEmail().isBlank()) {
                        log.warn("SUPER_ADMIN {} has no email, skip notify", admin.getId());
                        continue;
                    }
                    try {
                        mailSender.send(admin.getEmail(), "ZBPM: новая заявка на регистрацию",
                            "Пользователь " + user.getUsername() + " (" + user.getEmail() + ") подтвердил почту. Очередь: " + adminLink);
                    } catch (Exception e) {
                        log.warn("Failed to notify SUPER_ADMIN {} for verified user {}: {}", admin.getId(), userId, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SUPER_ADMIN notify failed for verified user {}: {}", userId, e.getMessage());
        }
    }
}
