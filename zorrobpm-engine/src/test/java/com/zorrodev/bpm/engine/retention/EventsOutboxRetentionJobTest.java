package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.event.SseLiveCursorTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WO-AUDIT-7: маршрутизация новых проходов в {@link RetentionJob}
 * (unit-уровень, моки — маршрутизация, не SQL; сам SQL — в PgIT рядом).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventsOutboxRetentionJobTest {

    @Mock private RetentionBatchProcessor batchProcessor;
    @Mock private com.zorrodev.bpm.engine.metrics.BpmMetrics bpmMetrics;
    private RetentionConfig config;
    private RetentionJob job;

    @BeforeEach
    void setUp() {
        config = new RetentionConfig();
        job = new RetentionJob(config, batchProcessor, bpmMetrics, new SseLiveCursorTracker());
        when(batchProcessor.claimAndDeleteBatch(any(), anyInt(), anyInt(), any()))
            .thenReturn(new RetentionBatchProcessor.ClaimedBatch(List.of(), 0));
        when(batchProcessor.findEligibleSubmissions(any(), anyInt(), any()))
            .thenReturn(List.of());
    }

    @Test
    void allTtlZero_doesNothing() {
        // Дефолт: все три TTL 0 → run() возвращается сразу (поведение не меняется).
        assertThat(new RetentionConfig().getEventsTtlDays()).isEqualTo(0);
        assertThat(new RetentionConfig().getOutboxTtlDays()).isEqualTo(0);
        assertThat(new RetentionConfig().isDryRun()).isFalse();
        job.run();
        verifyNoInteractions(batchProcessor);
    }

    @Test
    void eventsTtlOnly_runsEventsPass_skipsInstancePass() {
        config.setEventsTtlDays(30);
        when(batchProcessor.claimAndDeleteEventsBatch(any(), eq(25), any()))
            .thenReturn(new RetentionBatchProcessor.EventsBatch(List.of(), 0));

        job.run();

        verify(batchProcessor).claimAndDeleteEventsBatch(any(), eq(25), any());
        verify(batchProcessor, never()).claimAndDeleteBatch(any(), anyInt(), anyInt(), any());
        verify(batchProcessor, never()).deleteOrphanedBoundaryTimers(any(), anyInt());
    }

    @Test
    void outboxTtlOnly_runsOutboxPass_skipsInstancePass() {
        config.setOutboxTtlDays(30);
        when(batchProcessor.claimAndDeleteOutboxBatch(any(), eq(25)))
            .thenReturn(new RetentionBatchProcessor.OutboxBatch(List.of(), 0));

        job.run();

        verify(batchProcessor).claimAndDeleteOutboxBatch(any(), eq(25));
        verify(batchProcessor, never()).claimAndDeleteBatch(any(), anyInt(), anyInt(), any());
    }

    @Test
    void dryRun_countsButNeverDeletes() {
        config.setEventsTtlDays(30);
        config.setOutboxTtlDays(30);
        config.setDryRun(true);
        when(batchProcessor.countEligibleEvents(any(), any())).thenReturn(42L);
        when(batchProcessor.countEligibleOutbox(any())).thenReturn(7L);

        job.run();

        verify(batchProcessor).countEligibleEvents(any(), any());
        verify(batchProcessor).countEligibleOutbox(any());
        verify(batchProcessor, never()).claimAndDeleteEventsBatch(any(), anyInt(), any());
        verify(batchProcessor, never()).claimAndDeleteOutboxBatch(any(), anyInt());
    }

    @Test
    void dryRun_reportsDeletionCountToMetrics() {
        config.setEventsTtlDays(30);
        when(batchProcessor.claimAndDeleteEventsBatch(any(), eq(25), any()))
            .thenReturn(new RetentionBatchProcessor.EventsBatch(List.of(1L, 2L), 2),
                new RetentionBatchProcessor.EventsBatch(List.of(), 0));

        job.run();

        verify(bpmMetrics).retentionEventsDeleted(2L);
    }

    @Test
    void outboxPass_reportsDeletionCountToMetrics() {
        config.setOutboxTtlDays(30);
        when(batchProcessor.claimAndDeleteOutboxBatch(any(), eq(25)))
            .thenReturn(new RetentionBatchProcessor.OutboxBatch(
                List.of(java.util.UUID.randomUUID()), 1),
                new RetentionBatchProcessor.OutboxBatch(List.of(), 0));

        job.run();

        verify(bpmMetrics).retentionOutboxDeleted(1L);
    }

    @Test
    void eventsPass_usesLivePinFromTracker() {
        config.setEventsTtlDays(30);
        SseLiveCursorTracker tracker = new SseLiveCursorTracker();
        tracker.track("slow", 17L);
        RetentionJob pinnedJob = new RetentionJob(config, batchProcessor, bpmMetrics, tracker);
        when(batchProcessor.claimAndDeleteEventsBatch(any(), eq(25), eq(OptionalLong.of(17L))))
            .thenReturn(new RetentionBatchProcessor.EventsBatch(List.of(), 0));

        pinnedJob.run();

        verify(batchProcessor).claimAndDeleteEventsBatch(any(), eq(25), eq(OptionalLong.of(17L)));
    }
}
