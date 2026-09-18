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
        assertThat(AdminPasswordValidator.isWeak("123456789012")).isFalse();
    }
}
