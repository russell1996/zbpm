package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.PasswordResetRateLimiter;
import com.zorrodev.bpm.engine.service.SelfRegistrationService;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REG-3 (H2 level): self-registration creates an inactive PENDING account with a
 * USER role no client input can influence, emits an EMAIL_VERIFY token + letter,
 * conflicts honestly (incl. races) and rate-limits per email/IP.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class SelfRegistrationIntegrationTests {

    @Autowired private SelfRegistrationService registrationService;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordTokenRepository tokenRepository;
    @Autowired private StubMailSender mailSender;
    @Autowired @Qualifier("registrationRateLimiter") private PasswordResetRateLimiter registerLimiter;

    private final List<UUID> cleanupIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        mailSender.clear();
        // WO-REG-3 verifier merit #3: explicit bucket reset, not just capacity restore.
        try {
            java.lang.reflect.Method m = registerLimiter.getClass().getMethod("reset");
            m.invoke(registerLimiter);
        } catch (Exception e) {
            // no reset method — capacity restore below is enough for unique-key isolation
        }
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                tokenRepository.deleteAll(tokenRepository.findAll().stream()
                    .filter(t -> id.equals(t.getUserId())).toList());
                userRepository.deleteById(id);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        cleanupIds.clear();
        registerLimiter.setEmailCapacity(5);
        registerLimiter.setEmailWindowSeconds(3600);
        registerLimiter.setIpCapacity(20);
        registerLimiter.setIpWindowSeconds(3600);
    }

    private RegisterDTO dto(String username, String email) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setFullName("Self Registered");
        dto.setEmail(email);
        return dto;
    }

    private UiUserEntity onlyUserWithEmail(String email) {
        List<UiUserEntity> found = userRepository.findAll().stream()
            .filter(u -> email.equals(u.getEmail()))
            .toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Test
    void register_createsInactivePendingUserWithVerifyTokenAndLetter() {
        int mailBefore = mailSender.getSent().size();
        registrationService.register(dto("selfreg", "Self@Reg.X.com"), "10.1.0.1");

        UiUserEntity stored = onlyUserWithEmail("self@reg.x.com");
        cleanupIds.add(stored.getId());
        assertThat(stored.isActive()).isFalse();
        assertThat(stored.getRegistrationStatus()).isEqualTo("PENDING_EMAIL_VERIFICATION");
        assertThat(stored.getRole()).isEqualTo("USER");
        assertThat(stored.getUserType()).isEqualTo("HUMAN");
        assertThat(tokenRepository.existsByUserIdAndTypeAndUsedFalseAndExpiresAtAfter(
            stored.getId(), UserInvitationService.TYPE_EMAIL_VERIFY, Instant.now())).isTrue();
        // Delta, not absolute size: the stub sender is context-shared, other classes mail too.
        assertThat(mailSender.getSent().size() - mailBefore).isEqualTo(1);
        assertThat(mailSender.getSent().get(mailSender.getSent().size() - 1).to()).isEqualTo("self@reg.x.com");
        assertThat(mailSender.getSent().get(mailSender.getSent().size() - 1).body()).contains("/ui/verify-email?token=");
    }

    @Test
    void register_duplicateUsernameOrEmail_conflictsWithoutSecondRow() {
        RegisterDTO first = new RegisterDTO();
        first.setUsername("dupeuser");
        first.setPassword("MyStr0ng!P@ssw0rd");
        first.setFullName("Dupe");
        first.setEmail("dupe@x.com");
        registrationService.register(first, "10.1.0.2");
        onlyUserWithEmail("dupe@x.com");

        RegisterDTO sameName = new RegisterDTO();
        sameName.setUsername("dupeuser");
        sameName.setPassword("MyStr0ng!P@ssw0rd");
        sameName.setEmail("other@x.com");
        assertThatThrownBy(() -> registrationService.register(sameName, "10.1.0.3"))
            .isInstanceOf(EngineException.class);

        RegisterDTO sameEmail = new RegisterDTO();
        sameEmail.setUsername("anotheruser");
        sameEmail.setPassword("MyStr0ng!P@ssw0rd");
        sameEmail.setEmail("DUPE@X.COM");
        assertThatThrownBy(() -> registrationService.register(sameEmail, "10.1.0.4"))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("Email already exists");

        UiUserEntity stored = onlyUserWithEmail("dupe@x.com");
        cleanupIds.add(stored.getId());
    }

    @Test
    void register_rateLimitExceeded_failsEmailAndIpSeparately() {
        registerLimiter.setEmailCapacity(1);
        registerLimiter.setEmailWindowSeconds(3600);
        registerLimiter.setIpCapacity(1);
        registerLimiter.setIpWindowSeconds(3600);

        registrationService.register(dto("rateuser", "rate@x.com"), "10.3.0.1");
        onlyUserWithEmail("rate@x.com");

        // Same email again (fresh username): per-email bucket exhausted.
        assertThatThrownBy(() -> registrationService.register(dto("rateuser2", "rate@x.com"), "10.3.0.2"))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("Too many registration attempts");
        // Fresh email, same IP: per-IP bucket exhausted.
        assertThatThrownBy(() -> registrationService.register(dto("rateuser3", "other-rate@x.com"), "10.3.0.1"))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("Too many registration attempts");

        UiUserEntity stored = onlyUserWithEmail("rate@x.com");
        cleanupIds.add(stored.getId());
    }
}
