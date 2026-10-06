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
 * <p>WO-C8-36 (F-3): у TTL теперь ЕСТЬ обе границы, потому что «коротко нельзя»
 * было только в комментарии.
 *
 * <ul>
 *   <li><b>Пол {@value #MIN_TTL_SECONDS}с (30 мин).</b> Окно confirm-loss воркера —
 *       его {@code resultCache}, 10 минут. Маркер, удалённый раньше, чем брокер
 *       переотдаст ту же отправку, снова разрешает списать бюджет ретраев — то
 *       есть критерий 2 WO тихо нарушается. Значение меньше окна воркера было
 *       не «немного меньше запаса», а двойным расходом бюджета на одном
 *       логическом сбое.</li>
 *   <li><b>Потолок {@value #MAX_TTL_SECONDS}с (30 суток).</b> Таблица растёт со
 *       скоростью «отправок результата». Слишком длинный TTL этого не чинит, а
 *       маскирует: маркеры от давно завершившихся отправок копятся и таблица
 *       растёт без видимой причины.</li>
 * </ul>
 *
 * <p>Вне границ — clamp + WARN, ровно как у {@code completion-confirm-timeout}
 * (P-41: новая ручка рядом с проверяемой не должна быть ловушкой с другой
 * стороны). В частности {@code ttl<=0} больше не «чистит всё»: неположительное
 * значение — это почти наверняка ошибка конфигурации, а не намерение.
 */
@Slf4j
@Component
public class CompletionDedupCleanupJob {

    /**
     * Пол: окно confirm-loss воркера (resultCache, 10 мин) плюс трёхкратный запас.
     */
    static final int MIN_TTL_SECONDS = 1_800;
    /** Потолок: 30 суток. */
    static final int MAX_TTL_SECONDS = 2_592_000;

    private final CompletionDedupStore store;

    @Value("${zorrobpm.engine.completion-dedup.ttl-seconds:3600}")
    private int ttlSeconds = 3600;

    /** Последнее значение вне границ — чтобы неWarn'ить на каждом проходе расписания. */
    private volatile int lastWarnedRawTtl = Integer.MIN_VALUE;

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
            Timestamp.from(Instant.now().minus(Duration.ofSeconds(effectiveTtlSeconds()))));
    }

    /**
     * WO-C8-36 (F-3): фактически применённый TTL — зажатый по обеим границам.
     * Отдельный метод (а не clamp внутри {@link #cleanExpired()}), чтобы тест
     * проверял именно решение о границах, не завися от наличия строк в таблице.
     */
    int effectiveTtlSeconds() {
        int raw = ttlSeconds;
        if (raw >= MIN_TTL_SECONDS && raw <= MAX_TTL_SECONDS) {
            return raw;
        }
        int clamped = raw < MIN_TTL_SECONDS ? MIN_TTL_SECONDS : MAX_TTL_SECONDS;
        // Warn один раз на КАЖДОЕ новое значение вне границ: не на каждом проходе
        // (расписание живёт вечно), но и не молча при смене значения оператором.
        if (lastWarnedRawTtl != raw) {
            lastWarnedRawTtl = raw;
            log.warn("completion-dedup ttl-seconds={} is outside [{}, {}] — clamped to {}. "
                    + "Below the floor, a marker is deleted while the worker still holds "
                    + "the redelivered job in its result cache, and the retry budget is "
                    + "spent twice for one logical failure.",
                raw, MIN_TTL_SECONDS, MAX_TTL_SECONDS, clamped);
        }
        return clamped;
    }

    /** Тест-хук: значение из конфигурации без зажатия. */
    int rawTtlSecondsForTest() {
        return ttlSeconds;
    }

    void setTtlSecondsForTest(int seconds) {
        this.ttlSeconds = seconds;
    }
}
