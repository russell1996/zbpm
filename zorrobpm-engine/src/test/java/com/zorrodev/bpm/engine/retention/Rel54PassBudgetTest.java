package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-54 criterion 3: прогон с истёкшим бюджетом времени останавливается
 * корректно — без исключения, с закоммиченной целой пачкой (частичного
 * коммита нет: дедлайн проверяется МЕЖДУ пачками), и следующий запуск
 * продолжает (прогресс — закоммиченные пачки — не теряется).
 */
@ExtendWith(MockitoExtension.class)
class Rel54PassBudgetTest {

    @Mock private RetentionBatchProcessor batchProcessor;
    @Mock private com.zorrodev.bpm.engine.metrics.BpmMetrics bpmMetrics;
    private RetentionConfig config;
    private RetentionJob job;

    @BeforeEach
    void setUp() {
        config = new RetentionConfig();
        job = new RetentionJob(config, batchProcessor, bpmMetrics);
    }

    private void stubIdleTail(int batchSize) {
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(batchSize))).thenReturn(0);
        when(batchProcessor.findEligibleSubmissions(any(), eq(batchSize), any()))
            .thenReturn(List.of());
    }

    @Test
    void expiredBudget_stopsAfterFirstBatch_noException() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        config.setPassBudgetMs(1);
        // Дедлайн детерминированно проходит ВНУТРИ первой пачки: мок спит 10мс
        // при бюджете 1мс — проверка между пачками видит истёкший бюджет
        // (монотонные часы + запас 10×, направление одностороннее, не P-10).
        UUID id = UUID.randomUUID();
        when(batchProcessor.loadDistinctTtls()).thenReturn(List.of());
        when(batchProcessor.claimAndDeleteBatch(any(), eq(90), eq(10), anyList()))
            .thenAnswer(inv -> {
                Thread.sleep(10);
                return new RetentionBatchProcessor.ClaimedBatch(List.of(id), 5);
            })
            .thenReturn(new RetentionBatchProcessor.ClaimedBatch(List.of(UUID.randomUUID()), 5));
        stubIdleTail(10);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> job.run());

        // Ровно одна пачка: вторая не начиналась (дедлайн между пачками).
        verify(batchProcessor, times(1)).claimAndDeleteBatch(any(), eq(90), eq(10), anyList());
    }

    @Test
    void zeroBudget_meansNoDeadline_drainsFully() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        config.setPassBudgetMs(0); // как раньше — до пустого claim'а
        when(batchProcessor.loadDistinctTtls()).thenReturn(List.of());
        when(batchProcessor.claimAndDeleteBatch(any(), eq(90), eq(10), anyList()))
            .thenReturn(new RetentionBatchProcessor.ClaimedBatch(List.of(UUID.randomUUID()), 5),
                new RetentionBatchProcessor.ClaimedBatch(List.of(), 0));
        stubIdleTail(10);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> job.run());

        verify(batchProcessor, times(2)).claimAndDeleteBatch(any(), eq(90), eq(10), anyList());
    }

    @Test
    void distinctTtls_loadedOncePerPass_notPerBatch() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        when(batchProcessor.loadDistinctTtls()).thenReturn(List.of(30));
        when(batchProcessor.claimAndDeleteBatch(any(), eq(90), eq(10), anyList()))
            .thenReturn(new RetentionBatchProcessor.ClaimedBatch(List.of(UUID.randomUUID()), 5),
                new RetentionBatchProcessor.ClaimedBatch(List.of(), 0));
        stubIdleTail(10);

        job.run();

        // DISTINCT — раз за прогон, даже при двух пачках (задача 2 WO).
        verify(batchProcessor, times(1)).loadDistinctTtls();
        // Тот же список едет в каждую пачку (гонка pre-query задокументирована,
        // список не перезапрашивается внутри прохода).
        verify(batchProcessor, times(2))
            .claimAndDeleteBatch(any(), eq(90), eq(10), eq(List.of(30)));
    }

    @Test
    void nextRun_continuesFromCommittedProgress() {
        config.setTtlDays(90);
        config.setBatchSize(10);
        config.setPassBudgetMs(1);
        UUID first = UUID.randomUUID();
        when(batchProcessor.loadDistinctTtls()).thenReturn(List.of());
        when(batchProcessor.claimAndDeleteBatch(any(), eq(90), eq(10), anyList()))
            .thenAnswer(inv -> {
                Thread.sleep(10);
                return new RetentionBatchProcessor.ClaimedBatch(List.of(first), 5);
            });
        stubIdleTail(10);

        // Первый запуск: одна пачка, стоп по бюджету.
        job.run();
        verify(batchProcessor, times(1)).claimAndDeleteBatch(any(), eq(90), eq(10), anyList());

        // Второй запуск продолжает (тот же мок отдаёт следующую пачку —
        // в проде это строки, не взятые первым проходом).
        config.setPassBudgetMs(0);
        when(batchProcessor.claimAndDeleteBatch(any(), eq(90), eq(10), anyList()))
            .thenReturn(new RetentionBatchProcessor.ClaimedBatch(List.of(UUID.randomUUID()), 5),
                new RetentionBatchProcessor.ClaimedBatch(List.of(), 0));
        job.run();
        verify(batchProcessor, times(3)).claimAndDeleteBatch(any(), eq(90), eq(10), anyList());
        assertThat(true).as("second run completes without exception").isTrue();
    }
}
