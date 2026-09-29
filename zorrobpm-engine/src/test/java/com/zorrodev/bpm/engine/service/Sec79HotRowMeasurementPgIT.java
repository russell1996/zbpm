package com.zorrodev.bpm.engine.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zorrodev.bpm.engine.PostgresIT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-79 (NEW-10) criterion 3 — MEASUREMENT ONLY (no prod change): what
 * does the synchronous hot-row UPDATE in {@link PgRateLimiter#tryConsume}
 * cost on real PostgreSQL for typical data traffic?
 *
 * <p>Two runs, same statement the prod path executes (single guarded UPDATE
 * on one bucket row): (a) sequential latency distribution (p50/p99 over
 * 2000 consumes on one row); (b) 16-thread fan-in on one row (row-lock
 * serialization throughput). Numbers go to the WO report; the fix/no-fix
 * decision is made there, not here.
 */
@Tag("pg")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class Sec79HotRowMeasurementPgIT extends PostgresIT {

    @Autowired
    private PgRateLimiter pgRateLimiter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void measureHotRowUpdateCost() throws Exception {
        assertThat(jdbcTemplate.queryForObject("select version()", String.class))
            .as("must run against real PostgreSQL, not H2")
            .contains("PostgreSQL");

        String key = "sec79-hotrow-measure";
        pgRateLimiter.reset();
        // Warmup: fill caches/plans the way steady-state traffic would.
        for (int i = 0; i < 200; i++) {
            pgRateLimiter.tryConsume(key, 1_000_000, 3600);
        }

        List<Long> nanos = new ArrayList<>(2000);
        for (int i = 0; i < 2000; i++) {
            long t0 = System.nanoTime();
            long r = pgRateLimiter.tryConsume(key, 1_000_000, 3600);
            nanos.add(System.nanoTime() - t0);
            assertThat(r).isZero();
        }
        Collections.sort(nanos);
        long p50 = nanos.get(nanos.size() / 2);
        long p99 = nanos.get((int) (nanos.size() * 0.99));
        long max = nanos.get(nanos.size() - 1);
        System.out.printf("SEC79-HOTROW sequential: p50=%.2fms p99=%.2fms max=%.2fms (n=%d)%n",
            p50 / 1e6, p99 / 1e6, max / 1e6, nanos.size());

        int threads = 16;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                long t0 = System.nanoTime();
                for (int i = 0; i < perThread; i++) {
                    pgRateLimiter.tryConsume(key, 1_000_000_000, 3600);
                }
                return System.nanoTime() - t0;
            }));
        }
        long wall0 = System.nanoTime();
        start.countDown();
        long wallMax = 0;
        for (Future<Long> f : futures) {
            wallMax = Math.max(wallMax, f.get(120, TimeUnit.SECONDS));
        }
        long wall = System.nanoTime() - wall0;
        pool.shutdown();
        long total = (long) threads * perThread;
        System.out.printf("SEC79-HOTROW concurrent: threads=%d total=%d wall=%.2fs throughput=%.0f/s slowest-thread=%.2fs%n",
            threads, total, wall / 1e9, total / (wall / 1e9), wallMax / 1e9);

        assertThat(total).isEqualTo(4000L);
    }
}
