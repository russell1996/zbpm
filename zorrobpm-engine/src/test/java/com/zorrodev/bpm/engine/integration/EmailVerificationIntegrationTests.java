package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.SelfRegistrationService;
import com.zorrodev.bpm.engine.service.UserInvitationService;
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
 * WO-REG-4 (engine, H2): verify-email moves PENDING_EMAIL_VERIFICATION → PENDING_APPROVAL,
 * idempotent, notifies live SUPER_ADMINs, login stays 401, no SUPER_ADMIN ≠ 500.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class EmailVerificationIntegrationTests {

    @Autowired private SelfRegistrationService registrationService;
    @Autowired private UserInvitationService invitationService;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordTokenRepository tokenRepository;
    @Autowired private StubMailSender mailSender;
    @Autowired private com.zorrodev.bpm.engine.service.impl.UiUserServiceImpl uiUserService;

    private final List<UUID> cleanupIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        mailSender.clear();
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                tokenRepository.deleteAll(tokenRepository.findAll().stream()
                    .filter(t -> id.equals(t.getUserId())).toList());
                userRepository.deleteById(id);
            } catch (Exception e) {
                // best effort
            }
        }
        cleanupIds.clear();
    }

    private String registerAndExtractToken(String username, String email) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setFullName("Verify User");
        dto.setEmail(email);
        registrationService.register(dto, "10.0.0.1");
        // Token is in the last mail to this email
        String body = mailSender.getSent().stream()
            .filter(m -> email.equalsIgnoreCase(m.to()))
            .map(StubMailSender.MailRecord::body)
            .reduce((a, b) -> b).orElseThrow();
        Matcher m = Pattern.compile("token=([^\\s]+)").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private UiUserEntity onlyUserWithEmail(String email) {
        List<UiUserEntity> found = userRepository.findAll().stream()
            .filter(u -> email.equalsIgnoreCase(u.getEmail()))
            .toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Test
    void validToken_movesToPendingApproval() {
        String email = "verify1-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        String raw = registerAndExtractToken("verify1", email);
        UiUserEntity before = onlyUserWithEmail(email);
        cleanupIds.add(before.getId());
        assertThat(before.getRegistrationStatus()).isEqualTo("PENDING_EMAIL_VERIFICATION");
        assertThat(before.getEmailVerifiedAt()).isNull();

        registrationService.verifyEmail(raw);

        UiUserEntity after = userRepository.findById(before.getId()).orElseThrow();
        assertThat(after.getRegistrationStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(after.getEmailVerifiedAt()).isNotNull();
        assertThat(after.isActive()).isFalse();
    }

    @Test
    void repeatToken_isErrorAndDoesNotRollBack() {
        String email = "verify2-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        String raw = registerAndExtractToken("verify2", email);
        UiUserEntity user = onlyUserWithEmail(email);
        cleanupIds.add(user.getId());

        registrationService.verifyEmail(raw);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRegistrationStatus())
            .isEqualTo("PENDING_APPROVAL");

        assertThatThrownBy(() -> registrationService.verifyEmail(raw))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("Invalid or expired token");

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRegistrationStatus())
            .isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void notifiesAllActiveSuperAdmins_only() {
        // Create two active SUPER_ADMINs and one inactive
        UiUserEntity active1 = createSuperAdmin("sup-notify-a", true);
        UiUserEntity active2 = createSuperAdmin("sup-notify-b", true);
        UiUserEntity inactive = createSuperAdmin("sup-notify-c", false);
        cleanupIds.add(active1.getId());
        cleanupIds.add(active2.getId());
        cleanupIds.add(inactive.getId());

        String email = "verify3-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        String raw = registerAndExtractToken("verify3", email);
        cleanupIds.add(onlyUserWithEmail(email).getId());
        mailSender.clear();

        registrationService.verifyEmail(raw);

        List<String> recipients = mailSender.getSent().stream().map(StubMailSender.MailRecord::to).toList();
        assertThat(recipients).contains(active1.getEmail(), active2.getEmail());
        assertThat(recipients).doesNotContain(inactive.getEmail());
    }

    @Test
    void login_pendingApproval_still401() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "verify4-" + suffix + "@x.com";
        String username = "verify4-" + suffix;
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username);
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setFullName("Verify");
        dto.setEmail(email);
        registrationService.register(dto, "10.0.0.2");
        UiUserEntity user = onlyUserWithEmail(email);
        cleanupIds.add(user.getId());
        String raw = extractTokenFor(email);
        registrationService.verifyEmail(raw);

        com.zorrodev.bpm.contract.dto.LoginDTO login = new com.zorrodev.bpm.contract.dto.LoginDTO();
        login.setUsername(username);
        login.setPassword("MyStr0ng!P@ssw0rd");
        assertThat(uiUserService.login(login)).isEmpty();
    }

    @Test
    void noLiveSuperAdmin_doesNotFail() {
        // Deactivate all SUPER_ADMINs for this test, then verify still succeeds
        List<UiUserEntity> supers = userRepository.findAll().stream()
            .filter(u -> "SUPER_ADMIN".equals(u.getRole()) && u.isActive())
            .toList();
        List<Boolean> prevActives = new ArrayList<>();
        for (UiUserEntity u : supers) {
            prevActives.add(u.isActive());
            u.setActive(false);
            userRepository.save(u);
        }
        try {
            String email = "verify5-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
            String raw = registerAndExtractToken("verify5", email);
            cleanupIds.add(onlyUserWithEmail(email).getId());
            mailSender.clear();

            registrationService.verifyEmail(raw);

            assertThat(onlyUserWithEmail(email).getRegistrationStatus()).isEqualTo("PENDING_APPROVAL");
            assertThat(mailSender.getSent()).isEmpty();
        } finally {
            for (int i = 0; i < supers.size(); i++) {
                supers.get(i).setActive(prevActives.get(i));
                userRepository.save(supers.get(i));
            }
        }
    }

    // Helpers

    private String extractTokenFor(String email) {
        String body = mailSender.getSent().stream()
            .filter(m -> email.equalsIgnoreCase(m.to()))
            .map(StubMailSender.MailRecord::body)
            .reduce((a, b) -> b).orElseThrow();
        Matcher m = Pattern.compile("token=([^\\s]+)").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private UiUserEntity createSuperAdmin(String prefix, boolean active) {
        UiUserEntity e = new UiUserEntity();
        e.setId(UUID.randomUUID());
        e.setUsername(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        e.setPasswordHash("hashed");
        e.setFullName("Super " + prefix);
        e.setEmail(prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com");
        e.setRole("SUPER_ADMIN");
        e.setActive(active);
        e.setUserType("HUMAN");
        e.setForcePasswordChange(false);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setRegistrationStatus("ACTIVE");
        userRepository.save(e);
        return e;
    }
}
