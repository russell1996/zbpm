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

    /**
     * WO-C8-36 (F-5): каждая причина — ОТДЕЛЬНАЯ серия. Раньше meter был один с
     * жёстким {@code .tag("reason","stale_status")}, и в Prometheus phased-игноры
     * и отказы дедупа были неотличимы от stale_status — при том что счётчик
     * добавлен именно затем, чтобы оператор видел причину.
     *
     * <p>Мутация «свести всё на один тег» (вернуть один Meter с
     * {@code tag("stale_status")}) роняет ассерты на раздельных значениях.
     */
    @Test
    void eachReason_hasItsOwnSeries() {
        MeterRegistry registry = new SimpleMeterRegistry();
        BpmMetrics metrics = new BpmMetrics(registry);

        metrics.activityTransitionIgnored("stale_status");
        metrics.activityTransitionIgnored("stale_phase");
        metrics.activityTransitionIgnored("stale_phase");
        metrics.activityTransitionIgnored("duplicate_completion");
        metrics.activityTransitionIgnored("legacy_null_phase");

        assertThat(registry.get("zbpm.activity.transition.ignored").tag("reason", "stale_status")
            .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("zbpm.activity.transition.ignored").tag("reason", "stale_phase")
            .counter().count())
            .as("игнор по фазе обязан быть виден отдельно от игнора по статусу")
            .isEqualTo(2.0);
        assertThat(registry.get("zbpm.activity.transition.ignored").tag("reason", "duplicate_completion")
            .counter().count())
            .as("отказ durable-дедупа обязан отличаться от обоих прочих")
            .isEqualTo(1.0);
        assertThat(registry.get("zbpm.activity.transition.ignored").tag("reason", "legacy_null_phase")
            .counter().count())
            .as("игнор на legacy fail-open пути (E-3) — измеримость принятого риска")
            .isEqualTo(1.0);
        assertThat(registry.get("zbpm.activity.transition.ignored").counters())
            .as("никаких лишних серий с другим тегом быть не должно")
            .hasSize(BpmMetrics.ACTIVITY_TRANSITION_IGNORE_REASONS.size());
    }

    /** Причина не из списка по-прежнему молча не считается (контракт не меняем). */
    @Test
    void knownReasonsList_coversExactlyTheCountedOnes() {
        assertThat(BpmMetrics.ACTIVITY_TRANSITION_IGNORE_REASONS)
            .containsExactlyInAnyOrder("stale_status", "stale_phase", "duplicate_completion",
                "legacy_null_phase");
    }

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
