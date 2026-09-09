package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SCALE-2 criteria 1, 3, 4 on real PostgreSQL.
 *
 * <p>Criterion 1 (cluster-safety): two INDEPENDENT {@link PgRateLimiter}
 * instances (separate {@code JdbcTemplate}s over the same PG DataSource —
 * the closest in-test model of two replicas sharing one database) see one
 * shared counter: exhaustion via instance A is immediately visible to B.
 *
 * <p>Criterion 3 (no extra pass): 32 threads hammer one key at the capacity
 * boundary — exactly {@code capacity} requests are allowed, never more.
 *
 * <p>Criterion 4 (no unbounded growth): {@link RateLimitCleanupJob} deletes
 * only rows untouched for over a day and leaves live buckets alone.
 *
 * <p>POF (G-N): weakening the guarded UPDATE (dropping the
 * {@code AND (window_start <= ? OR tokens > 0)} gate) turns the race test
 * RED with {@code allowed > capacity}; restoring it → GREEN.
 */
@Tag("pg")
class RateLimitClusterPgIT extends PostgresIT {

    @Autowired PgRateLimiter instanceA;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource pgDataSource;
    @Autowired RateLimitCleanupJob cleanupJob;

    private PgRateLimiter instanceB() {
        return new PgRateLimiter(new JdbcTemplate(pgDataSource));
    }

    private void deleteKey(String key) {
        jdbc.update("DELETE FROM rate_limit_bucket WHERE bucket_key = ?", key);
    }

    @Test
    void criterion1_twoInstances_shareOneCounter() {
        String key = "cluster-" + UUID.randomUUID();
        try {
            // Exhaust the whole capacity through instance A only.
            for (int i = 0; i < 5; i++) {
                assertThat(instanceA.tryConsume(key, 5, 3600))
                    .as("consume %d via A", i)
                    .isEqualTo(0L);
            }
            // Instance B — which never saw this key — must observe exhaustion.
            assertThat(instanceB().tryConsume(key, 5, 3600))
                .as("B must see A's exhaustion (shared counter)")
                .isEqualTo(3600L);
            assertThat(jdbc.queryForObject(
                "SELECT tokens FROM rate_limit_bucket WHERE bucket_key = ?", Integer.class, key))
                .isEqualTo(0);
        } finally {
            deleteKey(key);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void criterion3_concurrentConsume_neverExceedsCapacity() throws Exception {
        String key = "race-" + UUID.randomUUID();
        int capacity = 10;
        int threads = 32;
        try {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger allowed = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        gate.await();
                        if (instanceA.tryConsume(key, capacity, 3600) == 0L) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                    return null;
                });
            }
            gate.countDown();
            assertThat(done.await(50, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(allowed.get())
                .as("exactly capacity=%d requests allowed out of %d racers", capacity, threads)
                .isEqualTo(capacity);
            assertThat(jdbc.queryForObject(
                "SELECT tokens FROM rate_limit_bucket WHERE bucket_key = ?", Integer.class, key))
                .isEqualTo(0);
        } finally {
            deleteKey(key);
        }
    }

    @Test
    void criterion4_cleanupJob_deletesOnlyStaleRows() {
        String stale = "stale-" + UUID.randomUUID();
        String fresh = "fresh-" + UUID.randomUUID();
        Instant now = Instant.now();
        try {
            jdbc.update("INSERT INTO rate_limit_bucket (bucket_key, window_start, tokens, updated_at) VALUES (?, ?, ?, ?)",
                stale, Timestamp.from(now.minusSeconds(3 * 86400)), 0,
                Timestamp.from(now.minusSeconds(2 * 86400)));
            jdbc.update("INSERT INTO rate_limit_bucket (bucket_key, window_start, tokens, updated_at) VALUES (?, ?, ?, ?)",
                fresh, Timestamp.from(now), 3, Timestamp.from(now));
            cleanupJob.run();
            assertThat(countKey(stale)).as("stale row deleted").isEqualTo(0);
            assertThat(countKey(fresh)).as("live row kept").isEqualTo(1);
        } finally {
            deleteKey(stale);
            deleteKey(fresh);
        }
    }

    private int countKey(String key) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM rate_limit_bucket WHERE bucket_key = ?", Integer.class, key);
        return n == null ? 0 : n;
    }
}
