package com.zorrodev.bpm.engine.configuration;

import com.zorrodev.bpm.engine.service.PasswordResetRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WO-REG-3: a dedicated rate-limiter bean for public self-registration — same class
 * as forgot-password's, but separate buckets AND separate tunables, so the two public
 * endpoints never eat each other's quota (a forgot-password storm must not lock out
 * registrations and vice versa).
 */
@Configuration
public class RegistrationRateLimitConfiguration {

    @Bean("registrationRateLimiter")
    public PasswordResetRateLimiter registrationRateLimiter(
            @Value("${zorrobpm.security.rate-limit.register-email-capacity:5}") int emailCapacity,
            @Value("${zorrobpm.security.rate-limit.register-email-window-seconds:3600}") int emailWindowSeconds,
            @Value("${zorrobpm.security.rate-limit.register-ip-capacity:20}") int registerIpCapacity,
            @Value("${zorrobpm.security.rate-limit.register-ip-window-seconds:3600}") int registerIpWindowSeconds) {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter();
        limiter.setEmailCapacity(emailCapacity);
        limiter.setEmailWindowSeconds(emailWindowSeconds);
        limiter.setIpCapacity(registerIpCapacity);
        limiter.setIpWindowSeconds(registerIpWindowSeconds);
        return limiter;
    }
}
