package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashThenMatches() {
        String hash = hasher.hash("s3cret!");
        assertThat(hasher.matches("s3cret!", hash)).isTrue();
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        String hash = hasher.hash("s3cret!");
        assertThat(hasher.matches("wrong", hash)).isFalse();
    }

    @Test
    void saltMakesEachHashUnique() {
        assertThat(hasher.hash("same")).isNotEqualTo(hasher.hash("same"));
    }

    @Test
    void malformedStoredHashIsRejected() {
        assertThat(hasher.matches("x", "not-a-valid-hash")).isFalse();
    }
}
