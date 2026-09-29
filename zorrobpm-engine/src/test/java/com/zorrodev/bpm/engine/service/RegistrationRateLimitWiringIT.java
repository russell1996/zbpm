package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.TestMain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-QW-9 (NEW4-07): {@code register-*} rate-limit properties must REALLY
 * drive the {@code registrationRateLimiter} bean's behavior.
 *
 * <p>The old field-level {@code @Value} was re-applied by Spring to the
 * factory-built bean post-construction, overwriting the {@code register-*}
 * tuning with the {@code reset-*}-properties/defaults: with
 * {@code register-ip-capacity=2} the bean behaved as 20. This test pins the
 * OBSERVABLE behavior (2 pass, 3rd rejected) — not a private field value.
 *
 * <p>POF link: on the unfixed code the third acquire passes (capacity 20),
 * the third assertion goes RED. Only constructor wiring removes that RED.
 */
@SpringBootTest(classes = TestMain.class,
    properties = "zorrobpm.security.rate-limit.register-ip-capacity=2")
@ActiveProfiles("test")
class RegistrationRateLimitWiringIT {

    @Autowired
    @Qualifier("registrationRateLimiter")
    private PasswordResetRateLimiter registerLimiter;

    @AfterEach
    void resetBuckets() {
        registerLimiter.reset();
    }

    @Test
    void registerIpCapacityTwo_twoPassThirdRejected() {
        String ip = "10.9.9." + UUID.randomUUID().toString().substring(0, 8);
        assertThat(registerLimiter.tryAcquireForIp(ip)).isTrue();
        assertThat(registerLimiter.tryAcquireForIp(ip)).isTrue();
        assertThat(registerLimiter.tryAcquireForIp(ip))
            .as("register-ip-capacity=2 must reject the 3rd request (was 20 before NEW4-07)")
            .isFalse();
    }
}
