package com.zorrodev.bpm.engine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * WO-C8-36 (H-2): TTL-очистка маркеров дедупа существующим механизмом
 * по расписанию (тот же паттерн, что {@link RateLimitCleanupJob} и
 * {@link IdempotencyCleanupJob} — отдельный компонент, а не вложенный в
 * {@code CompletionDedupStore} вызов: очистка не должна быть частью
 * транзакции обработки completion'а).
 *
 * <p>TTL по умолчанию час — с запасом относительно окна confirm-loss у воркера
 * (кеш результатов 10 минут). Короче нельзя: маркер, удалённый раньше, чем
 * брокер переотдаст ту же отправку, снова разрешит списать бюджет. Длиннее
 * не нужно: таблица растёт со скоростью «отправок результата», а не с
 * историей процессов.
 */
@Slf4j
@Component
public class CompletionDedupCleanupJob {

    private final CompletionDedupStore store;

    @Value("${zorrobpm.engine.completion-dedup.ttl-seconds:3600}")
    private int ttlSeconds = 3600;

    public CompletionDedupCleanupJob(CompletionDedupStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${zorrobpm.engine.completion-dedup.cleanup-interval-ms:3600000}")
    public void run() {
        try {
            int deleted = cleanExpired();
            if (deleted > 0) {
                log.info("CompletionDedupCleanup: deleted {} expired dedup markers", deleted);
            }
        } catch (Exception e) {
            // P-42: сбой одного прохода не должен ронять расписание — маркеры
            // переживут до следующего, повторная отправка просто увидит свой маркер.
            log.warn("CompletionDedupCleanup failed — continuing: {}", e.getMessage());
        }
    }

    /** Публично для тестов (расписание зовёт {@link #run()}). */
    public int cleanExpired() {
        return store.deleteExpiredBefore(
            Timestamp.from(Instant.now().minus(Duration.ofSeconds(ttlSeconds))));
    }
}
