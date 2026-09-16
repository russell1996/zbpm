package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-33 F37 (POF, RED-вариант для pre-fix базы): страница, ни одна строка
 * которой не удаляется (все дают ошибку), не должна гоняться по кругу вечно.
 *
 * <p>На pre-fix базе цикл submissions опрашивает ту же полную страницу снова —
 * {@code deleteSubmission} вызывается бесконечно → таймаут убивает тест (RED).
 * На ветке этот же сценарий живёт в {@code RetentionJobStuckPageTest}:
 * {@code pageDeleted==0} → выход + курсор мимо плохих строк → GREEN за
 * миллисекунды. Отличается только мок (2-arg vs 3-arg overload), который
 * навязывает сам фикс.
 */
@ExtendWith(MockitoExtension.class)
class RetentionJobStuckPageRedTest {

    @Mock private RetentionBatchProcessor batchProcessor;
    private RetentionConfig config;
    private RetentionJob job;

    @BeforeEach
    void setUp() {
        config = new RetentionConfig();
        job = new RetentionJob(config, batchProcessor);
    }

    @Test
    void fullPageOfFailingSubmissions_doesNotLoopForever() {
        config.setTtlDays(90);
        config.setBatchSize(2);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        when(batchProcessor.findEligibleInstances(any(), eq(2))).thenReturn(List.of());
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(2))).thenReturn(0);
        // Полная страница (2 = batchSize), обе строки бьются.
        when(batchProcessor.findEligibleSubmissions(any(), eq(2)))
            .thenReturn(List.of(id1, id2));
        when(batchProcessor.deleteSubmission(id1)).thenThrow(new RuntimeException("row locked"));
        when(batchProcessor.deleteSubmission(id2)).thenThrow(new RuntimeException("row locked"));

        // Pre-fix: та же страница опрашивается снова и снова → вечный цикл → RED по таймауту.
        // Post-fix: выход за миллисекунды (первый же проход) → GREEN.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> job.run());

        verify(batchProcessor, times(1)).deleteSubmission(id1);
        verify(batchProcessor, times(1)).deleteSubmission(id2);
    }
}
