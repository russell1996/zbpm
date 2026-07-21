package com.zorrodev.bpm.engine.processdefinition;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import org.springframework.jdbc.datasource.DataSourceUtils;
import java.sql.Connection;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ARCH-2: Concurrent deployment test on real PostgreSQL.
 * Two parallel transactions deploy the same processDefinitionKey simultaneously.
 * Both must succeed with sequential versions (1 and 2), no unique-violation.
 *
 * POF: without advisory lock, the race produces duplicate versions or unique constraint violation.
 */
public class ProcessDefinitionPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate txTemplate;

    @Test
    void concurrentDeploy_sameKey_differentVersions_noViolation() throws Exception {
        String key = "concurrent-test-" + UUID.randomUUID();
        jdbc.update("DELETE FROM process_definitions WHERE code = ?", key);

        CountDownLatch readyGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // T1: advisory lock + read max + insert — all in one transaction
        Future<?> f1 = pool.submit(() -> {
            txTemplate.executeWithoutResult(status -> {
                try {
                    readyGate.await();
                    long lockKey = key.hashCode();
                    jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) conn -> {
                        try (var ps = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                            ps.setLong(1, lockKey);
                            ps.execute();
                        }
                        return null;
                    });
                    int max = jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version), 0) FROM process_definitions WHERE code = ?",
                        Integer.class, key);
                    Thread.sleep(100); // Simulate deployment delay
                    UUID id = UUID.randomUUID();
                    jdbc.update(
                        "INSERT INTO process_definitions (id, code, name, version, sha256, created_at) " +
                        "VALUES (?, ?, 'Test Process', ?, ?, CURRENT_TIMESTAMP)",
                        id, key, max + 1, "sha-" + UUID.randomUUID());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        });

        // T2: same key, same time
        Future<?> f2 = pool.submit(() -> {
            txTemplate.executeWithoutResult(status -> {
                try {
                    readyGate.await();
                    long lockKey = key.hashCode();
                    jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) conn -> {
                        try (var ps = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                            ps.setLong(1, lockKey);
                            ps.execute();
                        }
                        return null;
                    });
                    int max = jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version), 0) FROM process_definitions WHERE code = ?",
                        Integer.class, key);
                    Thread.sleep(100); // Simulate deployment delay
                    UUID id = UUID.randomUUID();
                    jdbc.update(
                        "INSERT INTO process_definitions (id, code, name, version, sha256, created_at) " +
                        "VALUES (?, ?, 'Test Process', ?, ?, CURRENT_TIMESTAMP)",
                        id, key, max + 1, "sha-" + UUID.randomUUID());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        });

        readyGate.countDown();

        f1.get();
        f2.get();
        pool.shutdown();

        var versions = jdbc.queryForList(
            "SELECT version FROM process_definitions WHERE code = ? ORDER BY version", key);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version")).isEqualTo(1);
        assertThat(versions.get(1).get("version")).isEqualTo(2);

        jdbc.update("DELETE FROM process_definitions WHERE code = ?", key);
    }
}
