package com.zorrodev.bpm.engine.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * WO-ACL-18 criterion 13: throttles password-reset REQUESTS per email address AND per client IP,
 * so an attacker cannot flood reset emails to a victim (or enumerate) without burning their own
 * budget. Now backed by PostgreSQL (cluster-safe) via {@code PgRateLimiter}, replacing the
 * per-instance Caffeine caches that were also used by the original {@code RateLimitFilter}.
 * Configurable capacities/windows via setters (used by tests to shrink the window).
 */
@Component
// WO-REG-3: default choice now that a second bean of this class exists
// ("registrationRateLimiter", own quotas). Existing single-injection points
// keep resolving here, unchanged; the new bean is only ever referenced by qualifier.
@Primary
public class PasswordResetRateLimiter {

    private final PgRateLimiter pgRateLimiter;

    @Value("${zorrobpm.security.rate-limit.reset-email-capacity:5}")
    private int emailCapacity = 5;
    @Value("${zorrobpm.security.rate-limit.reset-email-window-seconds:3600}")
    private int emailWindowSeconds = 3600;
    @Value("${zorrobpm.security.rate-limit.reset-ip-capacity:20}")
    private int ipCapacity = 20;
    @Value("${zorrobpm.security.rate-limit.reset-ip-window-seconds:3600}")
    private int ipWindowSeconds = 3600;

    public PasswordResetRateLimiter(PgRateLimiter pgRateLimiter) {
        this.pgRateLimiter = pgRateLimiter;
    }

    public void setEmailCapacity(int capacity) { this.emailCapacity = capacity; }
    public void setEmailWindowSeconds(int windowSeconds) { this.emailWindowSeconds = windowSeconds; }
    public void setIpCapacity(int capacity) { this.ipCapacity = capacity; }
    public void setIpWindowSeconds(int windowSeconds) { this.ipWindowSeconds = windowSeconds; }

    public boolean tryAcquireForEmail(String email) {
        if (email == null) return true;
        return pgRateLimiter.tryConsume(email.toLowerCase(), emailCapacity, emailWindowSeconds) == 0;
    }

    public boolean tryAcquireForIp(String ip) {
        if (ip == null) return true;
        return pgRateLimiter.tryConsume(ip, ipCapacity, ipWindowSeconds) == 0;
    }

    public void reset() {
        pgRateLimiter.reset();
    }
}