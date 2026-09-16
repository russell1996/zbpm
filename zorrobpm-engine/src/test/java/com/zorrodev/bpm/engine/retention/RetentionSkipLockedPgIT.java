package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-33 п.1 (POF, реальный PostgreSQL): конкурентный захват eligible-строк.
 *
 * <p>T1 открывает транзакцию и держит row lock на eligible-строке
 * ({@code SELECT ... FOR UPDATE}, как вторая реплика, уже взявшая строку в
 * работу). T2 вызывает РЕАЛЬНЫЙ {@code findEligibleInstances}: с
 * {@code SKIP LOCKED} заблокированная строка пропускается (T2 видит пусто),
 * без него T2 видит ту же строку (дублирующая работа) либо блокируется.
 *
 * <p>Компилируется и на pre-fix базе: RED там — T2 видит заблокированную строку.
 */
public class RetentionSkipLockedPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionBatchProcessor batchProcessor;
    @Autowired TransactionTemplate transactionTemplate;

    private UUID sharedPdId;

    private static Timestamp ago(long seconds) {
        return Timestamp.from(Instant.now().minusSeconds(seconds));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE process_instances, activities, tokens, variables, " +
            "timer_jobs, message_subscriptions, incidents, service_tasks, user_tasks, " +
            "parallel_gateways, element_listener_phase, signal_subscriptions, process_definitions " +
            "RESTART IDENTITY CASCADE");
        sharedPdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, 'skiplocked-test', 1, 'SkipLocked Test', ?, ?)",
            sharedPdId, UUID.randomUUID().toString(), ago(200));
    }

    @Test
    void lockedRow_isSkippedBySecondReplica() throws Exception {
        UUID piId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, ?, false)",
            piId, sharedPdId, ago(100), ago(50));

        // T1: держит row lock до конца теста (транзакция не коммитится, пока T2 не опросит).
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> t1Error = new AtomicReference<>();
        Thread t1 = new Thread(() -> {
            try {
                transactionTemplate.execute(status -> {
                    jdbc.queryForList(
                        "SELECT id FROM process_instances WHERE id = ? FOR UPDATE",
                        UUID.class, piId);
                    locked.countDown();
                    try {
                        if (!release.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("release latch timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    // Откат: строка остаётся, lock снимается.
                    status.setRollbackOnly();
                    return null;
                });
            } catch (Throwable t) {
                t1Error.set(t);
                locked.countDown();
            }
        });
        t1.start();
        try {
            assertThat(locked.await(30, TimeUnit.SECONDS)).as("T1 взял row lock").isTrue();
            assertThat(t1Error.get()).as("T1 без ошибок: %s", t1Error.get()).isNull();

            // T2 (вторая реплика): SKIP LOCKED обязан пропустить заблокированную строку.
            // Таймаут на случай plain-SELECT-блокировки: с фиксом блокировки нет вообще.
            List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
            assertThat(eligible)
                .as("заблокированная репликой строка не должна попасть в выборку второй реплики")
                .doesNotContain(piId);
        } finally {
            release.countDown();
            t1.join(30_000);
        }
        assertThat(t1Error.get()).as("T1 без ошибок: %s", t1Error.get()).isNull();

        // После снятия lock строка снова eligible — выборка корректна, не потеряна.
        assertThat(batchProcessor.findEligibleInstances(Instant.now(), 100)).contains(piId);
    }
}
