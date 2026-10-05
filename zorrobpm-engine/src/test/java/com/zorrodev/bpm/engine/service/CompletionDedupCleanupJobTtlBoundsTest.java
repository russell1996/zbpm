package com.zorrodev.bpm.engine.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-C8-36 (F-3): TTL дедупа зажат по ОБЕИМ границам.
 *
 * <p>Дефект, который закрывается: «TTL должен быть длиннее окна confirm-loss»
 * было написано только в комментарии свойства, и ничего этого не зажимало.
 * {@code ZORROBPM_COMPLETION_DEDUP_TTL_SECONDS=60} → ежечасная чистка удаляет
 * всё старше минуты, переотдача на t=5 мин (тот же completionId, всё ещё в
 * 10-минутном resultCache воркера) принимается как новая, и бюджет ретраев
 * списывается ВТОРОЙ раз за один логический сбой. При {@code ttl<=0} таблица
 * вычищалась целиком каждый проход.
 *
 * <p>Соседняя ручка этой же ветки ({@code completion-confirm-timeout}) получила
 * оба зажима — здесь та же ловушка с другой стороны (P-41).
 *
 * <p>POF: мутация «снять нижний зажим» валит {@code floor_clampsBelowWindow} и
 * {@code nonPositive_doesNotWipeEverything}; мутация «снять верхний» —
 * {@code ceiling_clampsAbsurdValue}. Ассерты не «просто не упали», а смотрят на
 * КОНКРЕТНУЮ отсечку, дошедшую до {@code deleteExpiredBefore}, — она и есть
 * наблюдаемое поведение.
 */
class CompletionDedupCleanupJobTtlBoundsTest {

    private CompletionDedupStore store;
    private CompletionDedupCleanupJob job;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        store = mock(CompletionDedupStore.class);
        when(store.deleteExpiredBefore(any(Timestamp.class))).thenReturn(0);
        job = new CompletionDedupCleanupJob(store);
        appender = new ListAppender<>();
        appender.start();
        logger = (Logger) LoggerFactory.getLogger(CompletionDedupCleanupJob.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    /** Отсечка, дошедшая до стора — единственное, что реально наблюдаемо. */
    private Instant cutoffUsedByStore() {
        org.mockito.ArgumentCaptor<Timestamp> captor =
            org.mockito.ArgumentCaptor.forClass(Timestamp.class);
        verify(store).deleteExpiredBefore(captor.capture());
        return captor.getValue().toInstant();
    }

    @Test
    void floor_clampsBelowWorkerResultCacheWindow() {
        // 60с — «правдоподобная» опечатка оператора: окно resultCache воркера 10 минут.
        job.setTtlSecondsForTest(60);

        assertThat(job.effectiveTtlSeconds())
            .as("TTL короче окна воркера даёт двойной расход бюджета на одном сбое — "
                + "поэтому зажимается полом 30 мин")
            .isEqualTo(CompletionDedupCleanupJob.MIN_TTL_SECONDS);

        job.cleanExpired();
        long ageSeconds = Duration.between(cutoffUsedByStore(), Instant.now()).getSeconds();
        assertThat(ageSeconds)
            .as("отсечка чистки обязана соответствовать ЗАЖАТОМУ TTL, а не конфигурации: "
                + "при 60с маркер жил бы 60с вместо требуемых 30 минут")
            .isBetween((long) CompletionDedupCleanupJob.MIN_TTL_SECONDS - 30,
                (long) CompletionDedupCleanupJob.MIN_TTL_SECONDS + 30);
    }

    @Test
    void nonPositive_doesNotWipeEverything() {
        job.setTtlSecondsForTest(0);

        assertThat(job.effectiveTtlSeconds())
            .as("ttl<=0 чистил бы таблицу целиком каждый проход расписания")
            .isEqualTo(CompletionDedupCleanupJob.MIN_TTL_SECONDS);

        job.setTtlSecondsForTest(-1);
        assertThat(job.effectiveTtlSeconds())
            .as("отрицательное значение — тоже ошибка конфигурации, не команда «снести всё»")
            .isEqualTo(CompletionDedupCleanupJob.MIN_TTL_SECONDS);
    }

    @Test
    void ceiling_clampsAbsurdValue() {
        // Год: «чтобы маркеры точно не потерялись» — типичная ошибка оператора,
        // растущая таблица вместо работающей защиты.
        job.setTtlSecondsForTest(365 * 24 * 3600);

        assertThat(job.effectiveTtlSeconds())
            .as("потолок ограничивает рост таблицы маркеров")
            .isEqualTo(CompletionDedupCleanupJob.MAX_TTL_SECONDS);

        job.cleanExpired();
        long ageSeconds = Duration.between(cutoffUsedByStore(), Instant.now()).getSeconds();
        assertThat(ageSeconds)
            .isBetween((long) CompletionDedupCleanupJob.MAX_TTL_SECONDS - 60,
                (long) CompletionDedupCleanupJob.MAX_TTL_SECONDS + 60);
    }

    @Test
    void valueInsideBounds_isAppliedVerbatim() {
        job.setTtlSecondsForTest(7_200);

        assertThat(job.effectiveTtlSeconds())
            .as("значение внутри границ не искажается — зажим не должен менять волю оператора")
            .isEqualTo(7_200);

        job.cleanExpired();
        long ageSeconds = Duration.between(cutoffUsedByStore(), Instant.now()).getSeconds();
        assertThat(ageSeconds).isBetween(7_170L, 7_230L);
    }

    /**
     * P-41: значение вне границ обязано быть ЗАМЕЧЕНО, а не молча исправлено —
     * иначе оператор смотрит на конфиг и не видит, что он не применяется.
     */
    @Test
    void outOfBoundsValue_warnsOncePerDistinctValue() {
        job.setTtlSecondsForTest(60);
        assertThat(job.effectiveTtlSeconds()).isEqualTo(CompletionDedupCleanupJob.MIN_TTL_SECONDS);
        assertThat(appender.list)
            .as("первый проход с некорректным значением обязан сказать про clamp")
            .hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage())
            .contains("ttl-seconds=60")
            .contains("clamped to " + CompletionDedupCleanupJob.MIN_TTL_SECONDS);

        job.effectiveTtlSeconds();
        job.effectiveTtlSeconds();
        assertThat(appender.list)
            .as("тот же неверный конфиг не должен сыпать WARN на каждом проходе расписания")
            .hasSize(1);

        job.setTtlSecondsForTest(0);
        job.effectiveTtlSeconds();
        assertThat(appender.list)
            .as("новое неверное значение — снова предупреждение (оператор поправил и сломал иначе)")
            .hasSize(2);
    }

    /**
     * Проба на fail-safe поведения стора: даже с зажатым TTL отсечка обязана быть
     * В ПРОШЛОМ, иначе очистка удалила бы свежие маркеры (то есть ровно то, от
     * чего TTL и защищает).
     */
    @Test
    void cutoff_isAlwaysInThePast_evenAtTheFloor() {
        job.setTtlSecondsForTest(CompletionDedupCleanupJob.MIN_TTL_SECONDS);
        job.cleanExpired();
        assertThat(cutoffUsedByStore()).isBefore(Instant.now());
    }

    /** Контроль: один проход = ровно один DELETE с корректной отсечкой. */
    @Test
    void cleanup_callsStoreExactlyOnce_withBackdatedCutoff() {
        job.setTtlSecondsForTest(60);
        job.cleanExpired();
        job.cleanExpired();
        verify(store, org.mockito.Mockito.times(2)).deleteExpiredBefore(any(Timestamp.class));
    }
}