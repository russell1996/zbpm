package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-1 criterion #1 + #4:
 * Concurrent claim — job fires exactly once (V6: 2 real threads + CountDownLatch).
 * Old code (markFired read-modify-write) → 2 fires. Claim → 1 fire.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class TimerClaimConcurrencyTest {

    @Autowired
    private DBService dbService;

    @Autowired
    private TimerJobRepository timerJobRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void concurrentClaim_firesExactlyOnce() throws Exception {
        // Create a due timer job in its own transaction
        UUID jobId = transactionTemplate.execute(status -> {
            TimerJobEntity entity = new TimerJobEntity();
            entity.setId(UUID.randomUUID());
            entity.setActivityId(UUID.randomUUID());
            entity.setProcessInstanceId(UUID.randomUUID());
            entity.setDueAt(Instant.now().minusSeconds(1));
            entity.setCreatedAt(Instant.now());
            entity.setFired(false);
            timerJobRepository.save(entity);
            return entity.getId();
        });

        // 2 threads try to claim the same job simultaneously
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger claimSuccesses = new AtomicInteger(0);

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    // Each thread runs claim in its own transaction
                    boolean claimed = transactionTemplate.execute(status ->
                        dbService.claimTimerJob(jobId));
                    if (claimed) claimSuccesses.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        // Exactly 1 thread should have claimed the job
        assertThat(claimSuccesses.get()).isEqualTo(1);

        // Verify the job is now fired
        TimerJobEntity updated = transactionTemplate.execute(status ->
            timerJobRepository.findById(jobId).orElseThrow());
        assertThat(updated.isFired()).isTrue();
    }

    @Test
    void doubleFire_preventedByClaim() throws Exception {
        UUID jobId = transactionTemplate.execute(status -> {
            TimerJobEntity entity = new TimerJobEntity();
            entity.setId(UUID.randomUUID());
            entity.setActivityId(UUID.randomUUID());
            entity.setProcessInstanceId(UUID.randomUUID());
            entity.setDueAt(Instant.now().minusSeconds(1));
            entity.setCreatedAt(Instant.now());
            entity.setFired(false);
            timerJobRepository.save(entity);
            return entity.getId();
        });

        // First claim succeeds
        boolean first = transactionTemplate.execute(status ->
            dbService.claimTimerJob(jobId));
        assertThat(first).isTrue();

        // Second claim fails (already fired)
        boolean second = transactionTemplate.execute(status ->
            dbService.claimTimerJob(jobId));
        assertThat(second).isFalse();
    }
}
