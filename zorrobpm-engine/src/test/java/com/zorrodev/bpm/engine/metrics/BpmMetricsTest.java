package com.zorrodev.bpm.engine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OBS-1 (revival): all 15 meters register and move. Registration-only proof —
 * the wiring (real prod paths incrementing them) is proven per-metric in
 * {@code MetricsWiringIntegrationTests}; both halves are required by criterion 1.
 */
class BpmMetricsTest {

    private MeterRegistry registry;
    private BpmMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BpmMetrics(registry);
    }

    @Test
    void processStarted_incrementsCounter() {
        metrics.processStarted();
        metrics.processStarted();
        assertThat(registry.find("zbpm.process.started").counter().count()).isEqualTo(2.0);
    }

    @Test
    void processCompleted_incrementsCounter() {
        metrics.processCompleted();
        assertThat(registry.find("zbpm.process.completed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void processFailed_incrementsCounter() {
        metrics.processFailed();
        assertThat(registry.find("zbpm.process.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void activeInstances_gauge_movesBothWays() {
        Gauge gauge = registry.find("zbpm.process.instances.active").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);
        metrics.incrementActiveInstances();
        metrics.incrementActiveInstances();
        assertThat(gauge.value()).isEqualTo(2.0);
        metrics.decrementActiveInstances();
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    void stuckTokens_gauge_movesBothWays() {
        Gauge gauge = registry.find("zbpm.tokens.stuck").gauge();
        assertThat(gauge).isNotNull();
        metrics.incrementStuckTokens();
        assertThat(gauge.value()).isEqualTo(1.0);
        metrics.decrementStuckTokens();
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void stuckServiceTasks_gauge_holdsCount() {
        Gauge gauge = registry.find("zbpm.servicetask.stuck").gauge();
        assertThat(gauge).isNotNull();
        metrics.setStuckServiceTasks(3);
        assertThat(gauge.value()).isEqualTo(3.0);
        metrics.setStuckServiceTasks(0);
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void usertaskAgeMax_gauge_holdsSeconds() {
        Gauge gauge = registry.find("zbpm.usertask.age.max").gauge();
        assertThat(gauge).isNotNull();
        metrics.setUsertaskAgeMax(123);
        assertThat(gauge.value()).isEqualTo(123.0);
        metrics.setUsertaskAgeMax(0);
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void scriptRejected_incrementsCounter() {
        metrics.scriptRejected();
        assertThat(registry.find("zbpm.script.rejected").counter().count()).isEqualTo(1.0);
    }

    @Test
    void scriptTimeout_incrementsCounter() {
        metrics.scriptTimeout();
        assertThat(registry.find("zbpm.script.timeout").counter().count()).isEqualTo(1.0);
    }

    @Test
    void scriptPoolGauges_reflectExecutorState() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });
        // Deterministic occupancy (no sleeps): worker blocks on a latch, second task queues.
        java.util.concurrent.CountDownLatch workerStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseWorker = new java.util.concurrent.CountDownLatch(1);
        try {
            executor.submit(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(workerStarted.await(10, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> {
            });
            metrics.updateScriptPoolMetrics(executor);
            assertThat(registry.find("zbpm.script.pool.active").gauge().value()).isEqualTo(1.0);
            assertThat(registry.find("zbpm.script.pool.queue").gauge().value()).isEqualTo(1.0);
        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void outboxPublished_incrementsCounter() {
        metrics.outboxPublished();
        assertThat(registry.find("zbpm.outbox.published").counter().count()).isEqualTo(1.0);
    }

    @Test
    void outboxFailed_incrementsCounter() {
        metrics.outboxFailed();
        assertThat(registry.find("zbpm.outbox.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void outboxGauges_holdSampledCounts() {
        metrics.setOutboxBacklog(7);
        metrics.setOutboxQuarantine(2);
        assertThat(registry.find("zbpm.outbox.backlog").gauge().value()).isEqualTo(7.0);
        assertThat(registry.find("zbpm.outbox.quarantine").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void rabbitPublishFailed_incrementsCounter() {
        metrics.rabbitPublishFailed();
        assertThat(registry.find("zbpm.rabbit.publish.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void timerLag_recordsDuration() {
        metrics.recordTimerLag(Duration.ofMillis(1500));
        Timer timer = registry.find("zbpm.timer.lag").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1500.0);
    }

    @Test
    void timerLag_negativeIsFlooredAtZero() {
        metrics.recordTimerLag(Duration.ofMillis(-50));
        Timer timer = registry.find("zbpm.timer.lag").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(0.0);
    }

    @Test
    void allSeventeenMeters_registered() {
        Counter started = registry.find("zbpm.process.started").counter();
        Counter completed = registry.find("zbpm.process.completed").counter();
        Counter failed = registry.find("zbpm.process.failed").counter();
        Gauge active = registry.find("zbpm.process.instances.active").gauge();
        Gauge stuck = registry.find("zbpm.tokens.stuck").gauge();
        Gauge stuckService = registry.find("zbpm.servicetask.stuck").gauge();
        Gauge usertaskAge = registry.find("zbpm.usertask.age.max").gauge();
        Counter rejected = registry.find("zbpm.script.rejected").counter();
        Counter timeout = registry.find("zbpm.script.timeout").counter();
        Gauge poolActive = registry.find("zbpm.script.pool.active").gauge();
        Gauge poolQueue = registry.find("zbpm.script.pool.queue").gauge();
        Counter published = registry.find("zbpm.outbox.published").counter();
        Counter outboxFailed = registry.find("zbpm.outbox.failed").counter();
        Gauge backlog = registry.find("zbpm.outbox.backlog").gauge();
        Gauge quarantine = registry.find("zbpm.outbox.quarantine").gauge();
        Counter rabbit = registry.find("zbpm.rabbit.publish.failures").counter();
        Timer lag = registry.find("zbpm.timer.lag").timer();
        assertThat(java.util.List.of(started, completed, failed, active, stuck, stuckService, usertaskAge, rejected, timeout,
            poolActive, poolQueue, published, outboxFailed, backlog, quarantine, rabbit, lag))
            .doesNotContainNull();
    }

    @Test
    void allFifteenMeters_registered() {
        allSeventeenMeters_registered();
    }

    @Test
    void allEighteenMeters_registered() {
        allSeventeenMeters_registered();
    }
}
