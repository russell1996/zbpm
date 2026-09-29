package com.zorrodev.bpm.engine.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-QW-2 criterion 4: the idempotent status-guard no-op in
 * {@code CompletionService} increments {@code zbpm.activity.transition.ignored}
 * (reason tag {@code stale_status}) — observability without changing the
 * guard itself or its log level.
 */
class ActivityTransitionIgnoredMetricTest {

    @Test
    void staleStatus_incrementsCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        BpmMetrics metrics = new BpmMetrics(registry);
        assertThat(registry.get("zbpm.activity.transition.ignored").tag("reason", "stale_status")
            .counter().count()).isZero();
        metrics.activityTransitionIgnored("stale_status");
        metrics.activityTransitionIgnored("stale_status");
        assertThat(registry.get("zbpm.activity.transition.ignored").tag("reason", "stale_status")
            .counter().count()).isEqualTo(2.0);
    }

    @Test
    void unknownReason_doesNotIncrement() {
        MeterRegistry registry = new SimpleMeterRegistry();
        BpmMetrics metrics = new BpmMetrics(registry);
        metrics.activityTransitionIgnored("no_such_reason");
        assertThat(registry.get("zbpm.activity.transition.ignored").tag("reason", "stale_status")
            .counter().count()).isZero();
    }
}
