package com.zorrodev.bpm.engine.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class TimerSchedulerOverlapGuardTest {

    @Test
    void fireDueTimers_skipsWhenPreviousBatchStillRunning() throws Exception {
        TimerBatchProcessor batchProcessor = mock(TimerBatchProcessor.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(batchProcessor).processBatch();

        java.util.concurrent.Executor dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        TimerScheduler scheduler = new TimerScheduler(batchProcessor, dispatcher);

        scheduler.fireDueTimers();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.isRunning()).isTrue();

        // second tick while first still running must be skipped
        scheduler.fireDueTimers();

        release.countDown();
        // WO-OPS-11 п.2: ждём ФАКТ завершения батча (флаг снят), не фиксированные 300мс.
        await().atMost(Duration.ofSeconds(5)).until(() -> !scheduler.isRunning());

        verify(batchProcessor, times(1)).processBatch();
    }
}
