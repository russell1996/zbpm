package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.service.PgRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * WO-INT-8 criterion 5: throttles "Проверить" and "Отправить тестовое письмо" per calling
 * SUPER_ADMIN user id — both open a real SMTP connection, and an unbounded loop (an accidental
 * double-click storm, or a compromised super-admin account) risks the same provider ban WO-REL-19
 * hit in production ({@code mail.megaline.kz} blocked the account on a real retry storm). One
 * shared budget across both actions: they hit the same server, so what matters is total requests
 * to that server, not which button sent them. Now backed by PostgreSQL (cluster-safe) via
 * {@code PgRateLimiter}, replacing the per-instance Caffeine bucket.
 */
@Component
public class MailActionRateLimiter {

    @Value("${zorrobpm.mail.rate-limit.capacity:5}")
    private int capacity = 5;
    @Value("${zorrobpm.mail.rate-limit.window-seconds:3600}")
    private int windowSeconds = 3600;

    private final PgRateLimiter pgRateLimiter;

    public MailActionRateLimiter(PgRateLimiter pgRateLimiter) {
        this.pgRateLimiter = pgRateLimiter;
    }

    public void setCapacity(int capacity) { this.capacity = capacity; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }

    public boolean tryAcquire(UUID userId) {
        if (userId == null) return true;
        return pgRateLimiter.tryConsume("mail:action:" + userId, capacity, windowSeconds) == 0;
    }

    public void reset() {
        pgRateLimiter.reset();
    }
}
