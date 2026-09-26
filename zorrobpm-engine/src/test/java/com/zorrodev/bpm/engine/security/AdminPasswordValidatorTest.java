package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-31c: Unit tests for AdminPasswordValidator password policy.
 */
class AdminPasswordValidatorTest {

    @Test
    void isWeak_tooShort_returnsTrue() {
        assertThat(AdminPasswordValidator.isWeak("short")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("12345678901")).isTrue(); // 11 chars
    }

    @Test
    void isWeak_blocklisted_returnsTrue() {
        assertThat(AdminPasswordValidator.isWeak("admin")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("password")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("zorrodev")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("123456")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("qwerty")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("letmein")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("ADMIN")).isTrue(); // case-insensitive
        assertThat(AdminPasswordValidator.isWeak("Password")).isTrue();
    }

    @Test
    void isWeak_null_returnsTrue() {
        assertThat(AdminPasswordValidator.isWeak(null)).isTrue();
    }

    @Test
    void isWeak_strongPassword_returnsFalse() {
        assertThat(AdminPasswordValidator.isWeak("MyStr0ng!P@ssw0rd")).isFalse();
        assertThat(AdminPasswordValidator.isWeak("xK9#mN2$pL4vQ")).isFalse();
    }

    @Test
    void isWeak_exactly12chars_returnsFalse() {
        assertThat(AdminPasswordValidator.isWeak("xK9#mN2$pL4v")).isFalse();
    }

    /**
     * WO-QW-4 (NEW-16a): длинные шаблонные пароли (≥12, проходят length-check)
     * отклоняются blocklist'ом. RED: до фикса — false (проходили).
     */
    @Test
    void isWeak_longPatternedPasswords_returnsTrue() {
        assertThat(AdminPasswordValidator.isWeak("passwordpassword")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("qwertyqwerty")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("welcome123456")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("PASSWORDPASSWORD")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("MyStr0ng!P@ssw0rd")).isFalse();
    }

    /**
     * WO-QW-5 (NEW-16a): `Password123!` и leetspeak-варианты отклоняются —
     * нормализация (регистр + substitutions + strip шума) сводит их к
     * блоклист-шаблонам. POF-мутация: прямая `contains(lower)` без
     * нормализации — этот тест КРАСНЫЙ (password123! не в списке буквально).
     */
    @Test
    void isWeak_auditorTrivialVariants_returnsTrue() {
        assertThat(AdminPasswordValidator.isWeak("Password123!")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("P@ssw0rd123!")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("PASSWORDPASSWORD!")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("p@ss-w0rd-p@ss-w0rd")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("Qw3rty12345678")).isTrue();
        assertThat(AdminPasswordValidator.isWeak("L3tm31n!L3tm31n!")).isTrue();
        // Сильные рядом — не задеты (contains только для шаблонов ≥6,
        // короткие слова точным совпадением: administrator-производные живы).
        assertThat(AdminPasswordValidator.isWeak("MyStr0ng!P@ssw0rd")).isFalse();
        assertThat(AdminPasswordValidator.isWeak("xK9#mN2$pL4vQ")).isFalse();
    }
}
