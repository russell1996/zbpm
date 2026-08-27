package com.zorrodev.bpm.engine.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * WO-ACL-18 criterion 13: throttles password-reset REQUESTS per email address AND per client IP,
 * so an attacker cannot flood reset emails to a victim (or enumerate) without burning their own
 * budget. Pure in-memory, per-instance — consistent with the existing {@code RateLimitFilter}
 * which is also in-memory. Configurable capacities/windows via setters (used by tests to shrink
 * the window).
 */
@Component
public class PasswordResetRateLimiter {

    @Value("${zorrobpm.security.rate-limit.reset-email-capacity:5}")
    private int emailCapacity = 5;
    @Value("${zorrobpm.security.rate-limit.reset-email-window-seconds:3600}")
    private int emailWindowSeconds = 3600;
    @Value("${zorrobpm.security.rate-limit.reset-ip-capacity:20}")
    private int ipCapacity = 20;
    @Value("${zorrobpm.security.rate-limit.reset-ip-window-seconds:3600}")
    private int ipWindowSeconds = 3600;

    private final Cache<String, Bucket> emailBuckets = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build();
    private final Cache<String, Bucket> ipBuckets = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build();

    public void setEmailCapacity(int capacity) { this.emailCapacity = capacity; }
    public void setEmailWindowSeconds(int windowSeconds) { this.emailWindowSeconds = windowSeconds; }
    public void setIpCapacity(int capacity) { this.ipCapacity = capacity; }
    public void setIpWindowSeconds(int windowSeconds) { this.ipWindowSeconds = windowSeconds; }

    public boolean tryAcquireForEmail(String email) {
        if (email == null) return true;
        return acquire(emailBuckets, email.toLowerCase(), emailCapacity, emailWindowSeconds);
    }

    public boolean tryAcquireForIp(String ip) {
        if (ip == null) return true;
        return acquire(ipBuckets, ip, ipCapacity, ipWindowSeconds);
    }

    private synchronized boolean acquire(Cache<String, Bucket> cache, String key, int capacity, int windowSeconds) {
        long now = System.currentTimeMillis();
        Bucket bucket = cache.get(key, k -> new Bucket(capacity, windowSeconds));
        return bucket.tryConsume(now);
    }

    public void reset() {
        emailBuckets.invalidateAll();
        ipBuckets.invalidateAll();
    }

    static class Bucket {
        private final int capacity;
        private final long windowMillis;
        private long windowStart;
        private int tokens;

        Bucket(int capacity, int windowSeconds) {
            this.capacity = capacity;
            this.windowMillis = (long) windowSeconds * 1000L;
            this.tokens = capacity;
            this.windowStart = System.currentTimeMillis();
        }

        synchronized boolean tryConsume(long now) {
            long elapsed = now - windowStart;
            if (elapsed >= windowMillis) {
                windowStart = now;
                tokens = capacity;
            }
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }
    }
}
