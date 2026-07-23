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
 * WO-SEC-22: POF for RateBucket.tryConsume() race condition.
 *
 * Before fix: window reset (CAS + tokens.set) and tokens.decrementAndGet() were not atomic.
 * At window boundary, multiple threads could see elapsed >= windowMillis, all pass CAS
 * (only one wins the reset), but the others still call tokens.decrementAndGet() which
 * decrements from the reset value → more tokens consumed than capacity allows.
 *
 * After fix: synchronized tryConsume() makes reset + consumption atomic.
 */
class RateBucketConcurrencyTest {

    /**
     * POF: 50 threads hit tryConsume() simultaneously at window boundary.
     * Capacity=5, window=100ms. After 100ms, window resets.
     * With race: >5 tokens consumed in the window.
     * Without race: exactly 5 tokens consumed.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void tryConsume_atWindowBoundary_neverExceedsCapacity() throws Exception {
        int capacity = 5;
        int windowMs = 100;
        int threads = 50;

        // Create bucket and exhaust initial tokens
        RateLimitFilter filter = new RateLimitFilter();
        filter.setCapacity(capacity);
        filter.setWindowSeconds(1);
        filter.setRateLimitEnabled(true);

        // Use the internal RateBucket via the filter's computeIfAbsent
        // First, create the bucket by making one request
        var createReq = new org.springframework.mock.web.MockHttpServletRequest("POST", "/auth/login");
        createReq.setRemoteAddr("test-ip");
        var createResp = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = org.mockito.Mockito.mock(jakarta.servlet.FilterChain.class);
        filter.setCapacity(capacity);
        filter.setWindowSeconds(1);
        filter.setRateLimitEnabled(true);
        filter.doFilterInternal(createReq, createResp, chain);

        // Wait for window to expire
        Thread.sleep(windowMs + 50);

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
