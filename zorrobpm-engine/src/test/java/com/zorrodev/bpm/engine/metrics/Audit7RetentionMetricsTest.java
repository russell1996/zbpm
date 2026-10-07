package com.zorrodev.bpm.engine.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-7: счётчики чистки events/outbox (имена серий — контракт с
 * дашбордами; тест фиксирует имена дословно).
 */
class Audit7RetentionMetricsTest {

    private SimpleMeterRegistry registry;
    private BpmMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BpmMetrics(registry);
    }

    @Test
    void eventsDeletedCounter_sumsRows() {
        metrics.retentionEventsDeleted(3L);
        metrics.retentionEventsDeleted(2L);
        assertThat(registry.find("zbpm.retention.events.deleted.total").counter().count())
            .isEqualTo(5.0);
    }

    @Test
    void outboxDeletedCounter_sumsRows() {
        metrics.retentionOutboxDeleted(4L);
        assertThat(registry.find("zbpm.retention.outbox.deleted.total").counter().count())
            .isEqualTo(4.0);
    }

    @Test
    void passDurationTimer_records() {
        metrics.recordRetentionPassDuration(Duration.ofMillis(150));
        assertThat(registry.find("zbpm.retention.pass.duration").timer().count()).isEqualTo(1L);
    }
}
