package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mapper.UiUserMapper;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WO-REG-5: SUPER_ADMIN queue for self-registrations — approve/reject/listPending.
 * Mirrors {@code ProcessSubmissionServiceImpl} approve/reject split (self-service vs
 * SUPER_ADMIN queue) and uses {@code ApiException} with structural codes (WO-C8-33 precedent).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationAdminService {

    private final UiUserRepository userRepository;
    private final UiUserMapper uiUserMapper;
    private final MailSender mailSender;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<UiUser> listPendingRegistrations() {
        return userRepository.findByRegistrationStatusOrderByCreatedAtAsc("PENDING_APPROVAL").stream()
            .map(uiUserMapper::toDTO)
            .toList();
    }

    @Transactional
    public void approveRegistration(UUID id, Principal principal) {
        // WO-REL-39 (F18): CAS transition — the row lock serialises concurrent
        // approve/approve and approve/reject pairs. The loser blocks here until
        // the winner commits, then reads the decided status below and takes
        // the 409 path (REGISTRATION_ALREADY_DECIDED) instead of silently
        // overwriting the winner's decision.
        UiUserEntity user = userRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found", Map.of()));
        String status = user.getRegistrationStatus();
        if ("PENDING_EMAIL_VERIFICATION".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_NOT_VERIFIED",
                "Cannot approve before the applicant confirms their email",
                Map.of("userId", id.toString()));
        }
        if ("ACTIVE".equals(status) || "REJECTED".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "REGISTRATION_ALREADY_DECIDED",
                "Registration already decided: " + status,
                Map.of("userId", id.toString(), "status", String.valueOf(status)));
        }
        // Only PENDING_APPROVAL reaches here — proceed (unknown status would also proceed,
        // but no such status exists per WO-REG-2; defensive would be noise here).
        UUID adminId = requireSuperAdminId(principal);
        user.setActive(true);
        user.setRegistrationStatus("ACTIVE");
        user.setApprovedAt(Instant.now());
        user.setApprovedBy(adminId);
        userRepository.save(user);

        try {
            mailSender.send(user.getEmail(), "ZBPM: регистрация одобрена",
                "Ваша регистрация одобрена. Теперь вы можете войти.");
        } catch (Exception e) {
            log.warn("Failed to send approval mail to {}: {}", user.getEmail(), e.getMessage());
        }
        auditLogService.record(principal, "REGISTRATION_APPROVED", null, user.getId().toString());
    }

    @Transactional
    public void rejectRegistration(UUID id, String reason, Principal principal) {
        // WO-REL-39 (F18): same CAS transition as approveRegistration above —
        // reject/reject and reject/approve pairs serialise on the row lock.
        UiUserEntity user = userRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found", Map.of()));
        String status = user.getRegistrationStatus();
        boolean pendingVerification = "PENDING_EMAIL_VERIFICATION".equals(status);
        boolean pendingApproval = "PENDING_APPROVAL".equals(status);
        if (!pendingVerification && !pendingApproval) {
            throw new ApiException(HttpStatus.CONFLICT, "REGISTRATION_ALREADY_DECIDED",
                "Registration already decided: " + status,
                Map.of("userId", id.toString(), "status", String.valueOf(status)));
        }
        // Idempotent: if already REJECTED, the check above already threw; no second path needed.

        user.setRegistrationStatus("REJECTED");
        user.setRejectedAt(Instant.now());
        user.setRejectedReason(reason);
        // active stays false (rejected never logins)
        userRepository.save(user);

        try {
            // Neutral mail — reason is internal, never sent (criterion 5)
            mailSender.send(user.getEmail(), "ZBPM: регистрация отклонена",
                "Ваша заявка на регистрацию не одобрена.");
        } catch (Exception e) {
            log.warn("Failed to send rejection mail to {}: {}", user.getEmail(), e.getMessage());
        }
        auditLogService.record(principal, "REGISTRATION_REJECTED", reason, user.getId().toString());
    }

    private UUID requireSuperAdminId(Principal principal) {
        if (principal instanceof Principal.UserPrincipal u) {
            return u.userId();
        }
        // Should have been blocked by resource's requireSuperAdmin, but keep for safety
        throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "SUPER_ADMIN required", Map.of());
    }
}
