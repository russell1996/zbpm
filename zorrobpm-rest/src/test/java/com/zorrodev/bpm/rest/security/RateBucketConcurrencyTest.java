package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-22: concurrency guard for the login throttle, kept through WO-SCALE-2.
 *
 * Before fix (Caffeine era): window reset (CAS + tokens.set) and
 * tokens.decrementAndGet() were not atomic — at the window boundary more
 * tokens than capacity could be consumed. After fix: synchronized
 * tryConsume() made reset + consumption atomic.
 *
 * WO-SCALE-2: the bucket now lives in the shared table behind
 * PgRateLimiter (guarded single-statement UPDATE); this test hammers the
 * real filter path from 50 threads and asserts allowed <= capacity. The
 * precise window-boundary shape is covered by
 * RateLimitClusterPgIT.criterion3 on real PG; here the window (1s) is
 * longer than the pre-sleep, so this is a burst-contention test, not a
 * boundary test.
 */
class RateBucketConcurrencyTest {

    /**
     * 50 threads hit the login path simultaneously on one IP.
     * Capacity=5: at most 5 requests may pass, the rest get 429 —
     * through the real filter → PgRateLimiter path.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void tryConsume_atWindowBoundary_neverExceedsCapacity() throws Exception {
        int capacity = 5;
        int threads = 50;

        // Create the shared bucket row with one warm-up request (window is 1s,
        // the sleep below is shorter — the window does NOT expire; the race
        // is burst contention on the remaining tokens, not a boundary reset).
        RateLimitFilter filter = new RateLimitFilter();
        filter.setPgRateLimiter(TestRateLimitBuckets.create());
        filter.setCapacity(capacity);
        filter.setWindowSeconds(1);
        filter.setRateLimitEnabled(true);

        // First request warms the shared bucket (consumes 1 of 5 tokens);
        // the 50 racing threads below then contend for the remaining 4.
        var createReq = new org.springframework.mock.web.MockHttpServletRequest("POST", "/auth/login");
        createReq.setRemoteAddr("test-ip");
        var createResp = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = org.mockito.Mockito.mock(jakarta.servlet.FilterChain.class);
        filter.setCapacity(capacity);
        filter.setWindowSeconds(1);
        filter.setRateLimitEnabled(true);
        filter.doFilterInternal(createReq, createResp, chain);

        // WO-OPS-14: короткий settle-sleep(150мс) убран — warm-up выше
        // синхронен (тот же поток, тот же in-memory бакет), «оседать» нечему;
        // startLatch и так выравнивает старт всех 50 потоков. Окно 1с при
        // суммарном времени <150мс не истекает — гонка остаётся burst-contention.

        // Now launch 50 threads simultaneously
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    var req = new org.springframework.mock.web.MockHttpServletRequest("POST", "/auth/login");
                    req.setRemoteAddr("test-ip");
                    var resp = new org.springframework.mock.web.MockHttpServletResponse();
                    var c = org.mockito.Mockito.mock(jakarta.servlet.FilterChain.class);
                    filter.doFilterInternal(req, resp, c);
                    if (resp.getStatus() == 429) {
                        deniedCount.incrementAndGet();
                    } else {
                        allowedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads at once
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify: allowed + denied = threads, allowed <= capacity
        assertThat(allowedCount.get() + deniedCount.get()).isEqualTo(threads);
        assertThat(allowedCount.get())
            .as("Allowed requests (%d) must not exceed capacity (%d)", allowedCount.get(), capacity)
            .isLessThanOrEqualTo(capacity);
    }
}
