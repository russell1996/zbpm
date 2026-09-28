package com.zorrodev.bpm.engine.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * WO-ACL-18 criterion 13: throttles password-reset REQUESTS per email address AND per client IP,
 * so an attacker cannot flood reset emails to a victim (or enumerate) without burning their own
 * budget. Now backed by PostgreSQL (cluster-safe) via {@code PgRateLimiter}, replacing the
 * per-instance Caffeine caches that were also used by the original {@code RateLimitFilter}.
 * Configurable capacities/windows via constructor (WO-QW-9/NEW4-07: property
 * injection lives on constructor parameters — resolved exactly once, by whoever
 * calls the constructor. The previous field-level {@code @Value} was re-applied
 * by Spring to EVERY managed bean post-construction, silently overwriting the
 * registration bean's {@code register-*}-tuned values back to the
 * {@code reset-*}-properties/defaults).
 *
 * <p>WO-SCALE-2: both beans of this class (the {@code @Primary} forgot-password
 * one and the {@code registrationRateLimiter} one) share a single
 * {@code PgRateLimiter} — and therefore a single table. Quota separation
 * between them (the WO-REG-3 invariant: "its own bean and keys, separate
 * quotas from forgot-password") is preserved by the {@code keyPrefix}:
 * {@code "reset:"} by default, {@code "register:"} on the registration bean
 * (see {@code RegistrationRateLimitConfiguration}). Without it a
 * forgot-password storm on one address would eat the registration budget
 * for the same address and vice versa.
 */
@Component
// WO-REG-3: default choice now that a second bean of this class exists
// ("registrationRateLimiter", own quotas). Existing single-injection points
// keep resolving here, unchanged; the new bean is only ever referenced by qualifier.
@Primary
public class PasswordResetRateLimiter {

    private final PgRateLimiter pgRateLimiter;

    private int emailCapacity;
    private int emailWindowSeconds;
    private int ipCapacity;
    private int ipWindowSeconds;

    private String keyPrefix = "reset:";

    /**
     * WO-QW-9 (NEW4-07): {@code @Value} on constructor parameters, not on
     * fields. The {@code @Component @Primary} default ("reset") bean is built
     * by component-scan with THESE {@code reset-*}-properties; the
     * {@code registrationRateLimiter} bean is built by
     * {@code RegistrationRateLimitConfiguration} with ITS {@code register-*}
     * values passed straight into this constructor — Spring never re-applies
     * anything post-construction, so the factory's tuning survives.
     */
    public PasswordResetRateLimiter(PgRateLimiter pgRateLimiter,
            @Value("${zorrobpm.security.rate-limit.reset-email-capacity:5}") int emailCapacity,
            @Value("${zorrobpm.security.rate-limit.reset-email-window-seconds:3600}") int emailWindowSeconds,
            @Value("${zorrobpm.security.rate-limit.reset-ip-capacity:20}") int ipCapacity,
            @Value("${zorrobpm.security.rate-limit.reset-ip-window-seconds:3600}") int ipWindowSeconds) {
        this.pgRateLimiter = pgRateLimiter;
        this.emailCapacity = emailCapacity;
        this.emailWindowSeconds = emailWindowSeconds;
        this.ipCapacity = ipCapacity;
        this.ipWindowSeconds = ipWindowSeconds;
    }

    public void setEmailCapacity(int capacity) { this.emailCapacity = capacity; }
    public void setEmailWindowSeconds(int windowSeconds) { this.emailWindowSeconds = windowSeconds; }
    public void setIpCapacity(int capacity) { this.ipCapacity = capacity; }
    public void setIpWindowSeconds(int windowSeconds) { this.ipWindowSeconds = windowSeconds; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

    /** WO-QW-9 (NEW4-08): окно истощённого бакета — значение для Retry-After. */
    public int getEmailWindowSeconds() { return emailWindowSeconds; }
    /** WO-QW-9 (NEW4-08): окно истощённого бакета — значение для Retry-After. */
    public int getIpWindowSeconds() { return ipWindowSeconds; }

    public boolean tryAcquireForEmail(String email) {
        if (email == null) return true;
        return pgRateLimiter.tryConsume(keyPrefix + "email:" + email.toLowerCase(java.util.Locale.ROOT), emailCapacity, emailWindowSeconds) == 0;
    }

    public boolean tryAcquireForIp(String ip) {
        if (ip == null) return true;
        return pgRateLimiter.tryConsume(keyPrefix + "ip:" + ip, ipCapacity, ipWindowSeconds) == 0;
    }

    public void reset() {
        pgRateLimiter.reset();
    }
}
