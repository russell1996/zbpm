package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.RegistrationAdminService;
import com.zorrodev.bpm.engine.service.SelfRegistrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REG-5: approve/reject queue — criteria 2-6 (engine level).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class RegistrationAdminIntegrationTests {

    @Autowired private SelfRegistrationService registrationService;
    @Autowired private RegistrationAdminService adminService;
    @Autowired private UiUserRepository userRepository;
    @Autowired private StubMailSender mailSender;
    @Autowired private com.zorrodev.bpm.engine.service.impl.UiUserServiceImpl uiUserService;

    private final List<UUID> cleanupIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        mailSender.clear();
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                userRepository.deleteById(id);
            } catch (Exception e) {
                // best effort
            }
        }
        cleanupIds.clear();
    }

    private Principal superAdmin() {
        UiUserEntity e = new UiUserEntity();
        e.setId(UUID.randomUUID());
        e.setUsername("sup-" + UUID.randomUUID().toString().substring(0, 8));
        e.setPasswordHash("hashed");
        e.setFullName("Super");
        e.setEmail("sup-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com");
        e.setRole("SUPER_ADMIN");
        e.setActive(true);
        e.setUserType("HUMAN");
        e.setForcePasswordChange(false);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setRegistrationStatus("ACTIVE");
        userRepository.save(e);
        cleanupIds.add(e.getId());
        return new Principal.UserPrincipal(e.getId(), e.getUsername(), e.getRole());
    }

    private String registerAndExtractToken(String username, String email) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setFullName("User");
        dto.setEmail(email);
        registrationService.register(dto, "10.0.0.1");
        String body = mailSender.getSent().stream()
            .filter(m -> email.equalsIgnoreCase(m.to()))
            .map(StubMailSender.MailRecord::body)
            .reduce((a, b) -> b).orElseThrow();
        Matcher m = Pattern.compile("token=([^\\s\"]+)").matcher(body);
        assertThat(m.find()).isTrue();
        UiUserEntity user = userRepository.findAll().stream()
            .filter(u -> email.equalsIgnoreCase(u.getEmail())).findFirst().orElseThrow();
        if (!cleanupIds.contains(user.getId())) {
            cleanupIds.add(user.getId());
        }
        return m.group(1);
    }

    private UiUserEntity onlyUserWithEmail(String email) {
        return userRepository.findAll().stream()
            .filter(u -> email.equalsIgnoreCase(u.getEmail())).findFirst().orElseThrow();
    }

    @Test
    void approve_pendingEmailVerification_failsWithEmailNotVerified() {
        // POF-critical: without the explicit check, this would silently activate.
        String email = "approve-pending-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("approve-pending-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setFullName("User");
        dto.setEmail(email);
        registrationService.register(dto, "10.0.0.2");
        UiUserEntity user = onlyUserWithEmail(email);
        assertThat(user.getRegistrationStatus()).isEqualTo("PENDING_EMAIL_VERIFICATION");
        Principal sup = superAdmin();

        assertThatThrownBy(() -> adminService.approveRegistration(user.getId(), sup))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("EMAIL_NOT_VERIFIED"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRegistrationStatus())
            .isEqualTo("PENDING_EMAIL_VERIFICATION");
        assertThat(userRepository.findById(user.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void approve_pendingApproval_succeedsAndLoginWorks() {
        String email = "approve-ok-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        String raw = registerAndExtractToken("approve-ok", email);
        UiUserEntity user = onlyUserWithEmail(email);
        registrationService.verifyEmail(raw);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRegistrationStatus())
            .isEqualTo("PENDING_APPROVAL");
        Principal sup = superAdmin();
        mailSender.clear();

        adminService.approveRegistration(user.getId(), sup);

        UiUserEntity approved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(approved.isActive()).isTrue();
        assertThat(approved.getRegistrationStatus()).isEqualTo("ACTIVE");
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(approved.getApprovedBy()).isEqualTo(((Principal.UserPrincipal) sup).userId());
        // Mail to user (not checked for exact subject, just that something sent)
        assertThat(mailSender.getSent().stream().anyMatch(m -> email.equalsIgnoreCase(m.to()))).isTrue();
        com.zorrodev.bpm.contract.dto.LoginDTO login = new com.zorrodev.bpm.contract.dto.LoginDTO();
        login.setUsername(user.getUsername());
        login.setPassword("MyStr0ng!P@ssw0rd");
        assertThat(uiUserService.login(login)).isPresent();
    }

    @Test
    void reject_fromBothStatuses_andSecondDecisionFails() {
        String email1 = "reject-pending-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        RegisterDTO dto1 = new RegisterDTO();
        dto1.setUsername("reject1-" + UUID.randomUUID().toString().substring(0, 8));
        dto1.setPassword("MyStr0ng!P@ssw0rd");
        dto1.setFullName("User");
        dto1.setEmail(email1);
        registrationService.register(dto1, "10.0.0.3");
        UiUserEntity u1 = onlyUserWithEmail(email1);
        Principal sup = superAdmin();
        mailSender.clear();
        String reason = "spam";

        adminService.rejectRegistration(u1.getId(), reason, sup);
        UiUserEntity rejected1 = userRepository.findById(u1.getId()).orElseThrow();
        assertThat(rejected1.getRegistrationStatus()).isEqualTo("REJECTED");
        assertThat(rejected1.isActive()).isFalse();
        assertThat(rejected1.getRejectedReason()).isEqualTo(reason);
        // Mail does not contain reason
        String rejectionMail = mailSender.getSent().stream()
            .filter(m -> email1.equalsIgnoreCase(m.to())).map(StubMailSender.MailRecord::body).findFirst().orElse("");
        assertThat(rejectionMail).doesNotContain(reason);

        // Second decision on same user → 409
        assertThatThrownBy(() -> adminService.approveRegistration(u1.getId(), sup))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("REGISTRATION_ALREADY_DECIDED"));

        // Pending approval path
        String email2 = "reject-approval-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        String raw2 = registerAndExtractToken("reject2", email2);
        UiUserEntity u2 = onlyUserWithEmail(email2);
        registrationService.verifyEmail(raw2);
        mailSender.clear();
        adminService.rejectRegistration(u2.getId(), null, sup);
        assertThat(userRepository.findById(u2.getId()).orElseThrow().getRegistrationStatus()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> adminService.rejectRegistration(u2.getId(), null, sup))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("REGISTRATION_ALREADY_DECIDED"));
    }

    @Test
    void listPending_onlyPendingApproval() throws Exception {
        // Create 4 users in different states + 2 pending approvals to check ordering
        String emailPendingVerify = "list-pv-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        RegisterDTO dtoPv = new RegisterDTO();
        dtoPv.setUsername("list-pv-" + UUID.randomUUID().toString().substring(0, 8));
        dtoPv.setPassword("MyStr0ng!P@ssw0rd");
        dtoPv.setFullName("User");
        dtoPv.setEmail(emailPendingVerify);
        registrationService.register(dtoPv, "10.0.0.4");
        UiUserEntity pv = onlyUserWithEmail(emailPendingVerify);

        String emailPa1 = "list-pa1-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        String rawPa1 = registerAndExtractToken("list-pa1", emailPa1);
        UiUserEntity pa1 = onlyUserWithEmail(emailPa1);
        registrationService.verifyEmail(rawPa1);
        // WO-OPS-11 п.2: вместо sleep(10) — детерминированный порядок через
        // монотонный createdAt (мс-зернистость БД может склеить соседние метки,
        // тогда «oldest first» — гонка часов, а не кода). +1мс гарантирует факт.
        pa1 = userRepository.findById(pa1.getId()).orElseThrow();
        pa1.setCreatedAt(pa1.getCreatedAt().minusMillis(1));
        userRepository.save(pa1);
        String emailPa2 = "list-pa2-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        String rawPa2 = registerAndExtractToken("list-pa2", emailPa2);
        UiUserEntity pa2 = onlyUserWithEmail(emailPa2);
        registrationService.verifyEmail(rawPa2);

        String emailActive = "list-active-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        String rawActive = registerAndExtractToken("list-active", emailActive);
        UiUserEntity activeUser = onlyUserWithEmail(emailActive);
        registrationService.verifyEmail(rawActive);
        Principal sup = superAdmin();
        adminService.approveRegistration(activeUser.getId(), sup);

        String emailRejected = "list-rej-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        RegisterDTO dtoRej = new RegisterDTO();
        dtoRej.setUsername("list-rej-" + UUID.randomUUID().toString().substring(0, 8));
        dtoRej.setPassword("MyStr0ng!P@ssw0rd");
        dtoRej.setFullName("User");
        dtoRej.setEmail(emailRejected);
        registrationService.register(dtoRej, "10.0.0.5");
        UiUserEntity rej = onlyUserWithEmail(emailRejected);
        adminService.rejectRegistration(rej.getId(), "no", sup);

        List<UiUser> pending = adminService.listPendingRegistrations();
        List<UUID> ids = pending.stream().map(UiUser::getId).toList();
        assertThat(ids).contains(pa1.getId(), pa2.getId());
        assertThat(ids).doesNotContain(pv.getId());
        assertThat(ids).doesNotContain(activeUser.getId());
        assertThat(ids).doesNotContain(rej.getId());
        // Order: oldest first (created_at asc) — pa1 before pa2
        assertThat(ids.indexOf(pa1.getId())).isLessThan(ids.indexOf(pa2.getId()));
    }
}
