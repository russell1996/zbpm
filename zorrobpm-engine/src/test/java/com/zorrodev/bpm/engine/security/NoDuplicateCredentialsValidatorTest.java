package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-SEC-82 (NEW3-04): the soft {@code CredentialsValidator} (WARN +
 * {@code enforce-db-creds} flag, default false) duplicated the hard
 * {@code DbRabbitPasswordValidator} (WO-SEC-80 — unconditional FATAL outside
 * dev/test) on the same risk and is removed. This test pins the removal:
 * the soft class must be ABSENT from the classpath.
 *
 * <p>POF: written BEFORE the deletion — RED while the class exists
 * (no {@code ClassNotFoundException}), GREEN after.
 */
class NoDuplicateCredentialsValidatorTest {

    @Test
    void softCredentialsValidator_isAbsentFromClasspath() {
        assertThatThrownBy(() -> Class.forName(
            "com.zorrodev.bpm.engine.security.CredentialsValidator"))
            .isInstanceOf(ClassNotFoundException.class);
    }
}
