package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-69 П.1: lost updates счётчика {@code attempts} при конкурентных
 * результатах одной outbox-записи.
 *
 * <p>До фикса {@code OutboxDeliveryResultListener.on} делал read-modify-write:
 * {@code findById} → {@code recordFailure(id, attempts+1)} абсолютным значением.
 * K параллельных nack одной записи читали одно и то же {@code attempts} и все
 * писали {@code +1} от него — красные команды REL-68 наблюдали {@code attempts=4}
 * при 5 отправках. Следствие: запись дольше остаётся в ретрае, чем
 * {@code max-retries}, карантин срабатывает позже нужного.
 *
 * <p>Фикс: атомарный инкремент на стороне БД
 * ({@code UPDATE outbox SET attempts = attempts + 1 ...}), итог читается
 * отдельным select в той же транзакции. Тот же приём применён к inline-пути
 * {@code OutboxBatchProcessor.processOne} (та же read-modify-write слабость).
 *
 * <p>Гонка ГАРАНТИРОВАНА конструкцией, а не везением: все K потоков стоят на
 * {@link CyclicBarrier} и входят в свои (каждый — своя транзакция) {@code on}
 * одновременно, долбя одну строку. При старом коде все SELECT проходят до
 * первого COMMIT (сдвиг релиза барьера — микросекунды, транзакция — миллисекунды),
 * поэтому все пишут одно значение — финал {@code 1}, а не {@code K}.
 * Мутация «вернуть абсолют» краснит СТАБИЛЬНО (10/10 в отчёте).
 */
@Tag("pg")
public class OutboxAttemptsAtomicIncrementPgIT extends PostgresIT {

    /** Число параллельных неудач — столько же должно добавиться к счётчику. */
    private static final int PARALLEL_FAILURES = 8;

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRepository outboxRepository;
    @Autowired OutboxDeliveryResultListener resultListener;
    @Autowired TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE outbox RESTART IDENTITY");
    }

    private UUID seedServiceTaskEntry(int attempts) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO outbox (id, payload, created_at, published, attempts, status, kind) "
                + "VALUES (?, ?, ?, false, ?, 'PENDING', 'SERVICE_TASK')",
            id, "{\"serviceTaskId\":\"" + UUID.randomUUID() + "\"}",
            Timestamp.from(Instant.now()), attempts);
        return id;
    }

    private int readAttempts(UUID id) {
        return jdbc.queryForObject("SELECT attempts FROM outbox WHERE id = ?", Integer.class, id);
    }

    private String readStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM outbox WHERE id = ?", String.class, id);
    }

    /**
     * K параллельных nack одной записи в отдельных транзакциях (каждый поток —
     * своя транзакция через прокси бина) → ровно K к счётчику, без карантина.
     * maxRetries задран, чтобы все K остались на пути инкремента.
     */
    @Test
    void concurrentFailures_countEveryAttempt() throws Exception {
        ReflectionTestUtils.setField(resultListener, "maxRetries", 1000);
        UUID id = seedServiceTaskEntry(0);

        runConcurrentNacks(id, PARALLEL_FAILURES);

        assertThat(readAttempts(id))
            .as("WO-REL-69 П.1: %d параллельных nack обязаны дать attempts=%d, "
                + "lost updates нет", PARALLEL_FAILURES, PARALLEL_FAILURES)
            .isEqualTo(PARALLEL_FAILURES);
        assertThat(readStatus(id))
            .as("WO-REL-69 П.1: при maxRetries=1000 запись остаётся PENDING")
            .isEqualTo("PENDING");
    }

    /**
     * Гонка НА ПОРОГЕ карантина: запись в одном шаге от maxRetries, K потоков
     * одновременно. Финал детерминирован: статус FAILED, РОВНО ОДНО событие
     * {@code outbox.quarantined} (условный markFailed — только первый переход
     * эмитит, WO-REL-22 B3), attempts НЕ растёт (пин семантики WO-INT-5/WO-REL-19:
     * «карантинный nack не инкрементит» — условный инкремент ниже потолка тоже
     * ничего не пишет, когда потолок уже достигнут).
     *
     * <p>Честная метка: на СТАРОМ коде этот тест ЗЕЛЁНЫЙ (старый код на пороге
     * тоже не писал attempts) — это не POF, а guard потолка: ловит фикс, который
     * «починил» гонку ценой превышения maxRetries-1. POF этого WO — первый тест.
     */
    @Test
    void concurrentFailuresAtThreshold_quarantineEmittedExactlyOnce_attemptsCapped() throws Exception {
        ReflectionTestUtils.setField(resultListener, "maxRetries", 3);
        UUID id = seedServiceTaskEntry(2); // 2 + 1 = 3 >= maxRetries(3) → карантин

        runConcurrentNacks(id, 4);

        assertThat(readStatus(id))
            .as("WO-REL-69 П.1: запись на пороге обязана уйти в карантин")
            .isEqualTo("FAILED");
        assertThat(readAttempts(id))
            .as("WO-REL-69 П.1: карантинный переход не инкрементит "
                + "(пин семантики INT-5/REL-19 и потолка условного инкремента)")
            .isEqualTo(2);
        Long quarantinedEvents = jdbc.queryForObject(
            "SELECT COUNT(*) FROM events WHERE type = 'outbox.quarantined' "
                + "AND data ->> 'outboxId' = ?",
            Long.class, id.toString());
        assertThat(quarantinedEvents)
            .as("WO-REL-69 П.1 + WO-REL-22 B3: переход в FAILED эмитит ровно одно событие")
            .isEqualTo(1L);
    }

    /**
     * Все потоки — через барьер (одновременный вход), каждый — своя транзакция.
     * Без sleep-подгонок: границы — только таймауты ожидания (fail-closed при
     * клине, а не молчаливый пропуск).
     */
    private void runConcurrentNacks(UUID id, int threads) throws Exception {
        CyclicBarrier gate = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(30, TimeUnit.SECONDS);
                    txTemplate.executeWithoutResult(
                        s -> resultListener.on(
                            new OutboxDeliveryResult(id.toString(), false, "concurrent-no-route")));
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
