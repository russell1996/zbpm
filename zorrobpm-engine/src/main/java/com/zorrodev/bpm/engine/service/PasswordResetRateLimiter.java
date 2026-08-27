package com.zorrodev.bpm.engine.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * WO-ACL-18 criterion 13: throttles password-reset REQUESTS per email address AND per client IP,
 * so an attacker cannot flood reset emails to a victim (or enumerate) without burning their own
 * budget. Pure in-memory, per-instance — consistent with the existing {@code RateLimitFilter}
 * which is also in-memory. Configurable capacities/windows via setters (used by tests to shrink
 * the window).
 */
@Component
public class PasswordResetRateLimiter {

    private volatile int emailCapacity = 5;
    private volatile int emailWindowSeconds = 3600;
    private volatile int ipCapacity = 20;
    private volatile int ipWindowSeconds = 3600;

    private final ConcurrentHashMap<String, Bucket> emailBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

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

    private synchronized boolean acquire(ConcurrentHashMap<String, Bucket> map, String key, int capacity, int windowSeconds) {
        long now = System.currentTimeMillis();
        Bucket bucket = map.computeIfAbsent(key, k -> new Bucket(capacity, windowSeconds));
        return bucket.tryConsume(now);
    }

    public void reset() {
        emailBuckets.clear();
        ipBuckets.clear();
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
