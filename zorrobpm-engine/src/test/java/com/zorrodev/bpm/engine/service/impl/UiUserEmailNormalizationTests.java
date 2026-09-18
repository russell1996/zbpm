package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REG-1 (H2 level): email is stored lowercase; service-level case-insensitive
 * conflict; SYSTEM null-email accounts unaffected; forgot-password finds
 * case-variants. DB-level enforcement itself is proven on real PostgreSQL
 * ({@code UiUserEmailUniquenessPgIT}) — H2 cannot express the partial functional
 * index, it only carries the plain-UNIQUE fallback.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UiUserEmailNormalizationTests {

    @Autowired private UiUserServiceImpl userService;
    @Autowired private UserInvitationService invitationService;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordTokenRepository tokenRepository;

    private CreateUiUserDTO human(String username, String email) {
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setCreationMode("PASSWORD");
        dto.setEmail(email);
        return dto;
    }

    private boolean hasLiveResetToken(UUID userId) {
        return tokenRepository.existsByUserIdAndTypeAndUsedFalseAndExpiresAtAfter(
            userId, "RESET", Instant.now());
    }

    @Transactional
    @Test
    void create_lowercasesEmailBeforeStorage() {
        UUID id = userService.create(human("caseuser", "Foo@Bar.COM"));

        UiUserEntity stored = userRepository.findById(id).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo("foo@bar.com");
    }

    @Transactional
    @Test
    void update_lowercasesEmailBeforeStorage() {
        UUID id = userService.create(human("upduser", "upd@x.com"));

        UpdateUiUserDTO dto = new UpdateUiUserDTO();
        dto.setEmail("New@Mail.COM");
        userService.update(id, dto);

        assertThat(userRepository.findById(id).orElseThrow().getEmail()).isEqualTo("new@mail.com");
    }

    @Transactional
    @Test
    void create_caseVariantOfExistingEmail_conflicts() {
        userService.create(human("firstuser", "foo@x.com"));

        CreateUiUserDTO clash = human("seconduser", "FOO@X.com");
        assertThatThrownBy(() -> userService.create(clash))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("Email already exists");
        assertThat(userRepository.findAll().stream()
            .filter(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase("foo@x.com"))
            .count()).isEqualTo(1);
    }

    @Transactional
    @Test
    void update_ownEmailDifferentCase_noSelfConflict() {
        UUID id = userService.create(human("selfuser", "foo@x.com"));

        UpdateUiUserDTO dto = new UpdateUiUserDTO();
        dto.setEmail("Foo@X.COM");
        userService.update(id, dto);

        assertThat(userRepository.findById(id).orElseThrow().getEmail()).isEqualTo("foo@x.com");
    }

    @Transactional
    @Test
    void update_emailTakenByAnotherUser_conflicts() {
        userService.create(human("owneruser", "owner@x.com"));
        UUID other = userService.create(human("otheruser", "other@x.com"));

        UpdateUiUserDTO dto = new UpdateUiUserDTO();
        dto.setEmail("OWNER@X.COM");
        assertThatThrownBy(() -> userService.update(other, dto))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("Email already exists");
    }

    @Transactional
    @Test
    void systemAccounts_withoutEmail_coexist() {
        CreateUiUserDTO first = new CreateUiUserDTO();
        first.setUsername("sys-a-" + UUID.randomUUID().toString().substring(0, 8));
        first.setUserType("SYSTEM");
        UUID idA = userService.create(first);

        CreateUiUserDTO second = new CreateUiUserDTO();
        second.setUsername("sys-b-" + UUID.randomUUID().toString().substring(0, 8));
        second.setUserType("SYSTEM");
        UUID idB = userService.create(second);

        assertThat(userRepository.findById(idA)).isPresent();
        assertThat(userRepository.findById(idB)).isPresent();
    }

    @Transactional
    @Test
    void requestReset_findsCaseVariants() {
        // Both directions: stored mixed → queried lower, stored lower → queried upper.
        UUID mixed = userService.create(human("resetuser", "Foo@Bar.com"));
        UUID lower = userService.create(human("resetuser2", "bar@foo.com"));
        assertThat(hasLiveResetToken(mixed)).isFalse();
        assertThat(hasLiveResetToken(lower)).isFalse();

        invitationService.requestReset("foo@bar.com", "10.0.0.1");
        invitationService.requestReset("BAR@FOO.COM", "10.0.0.2");

        assertThat(hasLiveResetToken(mixed)).isTrue();
        assertThat(hasLiveResetToken(lower)).isTrue();
    }
}
