package com.zorrodev.bpm.engine.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** WO-ACL-18 criterion 13: per-email + per-IP throttle on reset REQUESTS. */
class PasswordResetRateLimiterTest {

    @Test
    void allowsUpToCapacityThenRejects_perEmail() {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter();
        limiter.setEmailCapacity(2);
        limiter.setEmailWindowSeconds(3600);

        assertThat(limiter.tryAcquireForEmail("a@b.c")).isTrue();
        assertThat(limiter.tryAcquireForEmail("a@b.c")).isTrue();
        assertThat(limiter.tryAcquireForEmail("a@b.c")).isFalse();
        // a different email is unaffected
        assertThat(limiter.tryAcquireForEmail("d@e.f")).isTrue();
    }

    @Test
    void allowsUpToCapacityThenRejects_perIp() {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter();
        limiter.setIpCapacity(3);
        limiter.setIpWindowSeconds(3600);

        assertThat(limiter.tryAcquireForIp("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquireForIp("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquireForIp("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquireForIp("1.1.1.1")).isFalse();
    }

    @Test
    void resetClearsBuckets() {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter();
        limiter.setEmailCapacity(1);
        limiter.setEmailWindowSeconds(3600);
        assertThat(limiter.tryAcquireForEmail("x@y.z")).isTrue();
        assertThat(limiter.tryAcquireForEmail("x@y.z")).isFalse();
        limiter.reset();
        assertThat(limiter.tryAcquireForEmail("x@y.z")).isTrue();
    }
}
