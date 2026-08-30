package com.zorrodev.bpm.engine.mail;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * WO-INT-8 criterion 5: throttles "Проверить" and "Отправить тестовое письмо" per calling
 * SUPER_ADMIN user id — both open a real SMTP connection, and an unbounded loop (an accidental
 * double-click storm, or a compromised super-admin account) risks the same provider ban WO-REL-19
 * hit in production ({@code mail.megaline.kz} blocked the account on a real retry storm). One
 * shared budget across both actions: they hit the same server, so what matters is total requests
 * to that server, not which button sent them. Same Caffeine-bucket pattern as
 * {@link com.zorrodev.bpm.engine.service.PasswordResetRateLimiter} — not reimplemented from
 * scratch.
 */
@Component
public class MailActionRateLimiter {

    @Value("${zorrobpm.mail.rate-limit.capacity:5}")
    private int capacity = 5;
    @Value("${zorrobpm.mail.rate-limit.window-seconds:3600}")
    private int windowSeconds = 3600;

    private final Cache<UUID, Bucket> buckets = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build();

    public void setCapacity(int capacity) { this.capacity = capacity; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }

    public boolean tryAcquire(UUID userId) {
        if (userId == null) return true;
        return acquire(userId, capacity, windowSeconds);
    }

    private synchronized boolean acquire(UUID key, int capacity, int windowSeconds) {
        long now = System.currentTimeMillis();
        Bucket bucket = buckets.get(key, k -> new Bucket(capacity, windowSeconds));
        return bucket.tryConsume(now);
    }

    public void reset() {
        buckets.invalidateAll();
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
