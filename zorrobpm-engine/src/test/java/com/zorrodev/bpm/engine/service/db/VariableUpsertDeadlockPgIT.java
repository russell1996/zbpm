package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-44 (CRITICAL прод-инцидент): две настоящие конкурентные транзакции
 * апсертят один и тот же набор переменных ОДНОГО process instance в
 * ПРОТИВОПОЛОЖНОМ порядке имён. До фикса — живой {@code deadlock detected}
 * (та же пара UPSERT-стейтментов, что в прод-логах); после фикса (каноническая
 * сортировка по имени в {@code setVariables}) — обе проходят, одна лишь ждёт
 * локи другой, но не по кругу.
 *
 * <p>Только реальный PostgreSQL: H2 не воспроизводит построчные локи/детектор
 * deadlock'ов. Строки помечены уникальным process_instance_id и чистятся в
 * {@code AfterEach} (сюита делит БД).
 */
class VariableUpsertDeadlockPgIT extends PostgresIT {

    /** Итераций гонки: каждая — свежая пара коротких транзакций в lockstep. */
    private static final int ROUNDS = 30;

    @Autowired
    private VariableDbOperations variableDbOperations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID processInstanceId;
    private UUID processDefinitionId;

    @AfterEach
    void cleanup() {
        if (processInstanceId != null) {
            jdbcTemplate.update("DELETE FROM variable_history WHERE process_instance_id = ?", processInstanceId);
            jdbcTemplate.update("DELETE FROM variables WHERE process_instance_id = ?", processInstanceId);
            jdbcTemplate.update("DELETE FROM process_instances WHERE id = ?", processInstanceId);
        }
        if (processDefinitionId != null) {
            jdbcTemplate.update("DELETE FROM process_definitions WHERE id = ?", processDefinitionId);
        }
    }

    /** FK variables → process_instances → process_definitions: минимальный сид. */
    private UUID seedProcessInstance() {
        processDefinitionId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at, deployment_state) "
            + "VALUES (?, ?, 1, ?, ?, now(), 'ACTIVE')",
            processDefinitionId, "rel44-" + processDefinitionId, "rel44",
            "rel44-" + processDefinitionId);
        processInstanceId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at) VALUES (?, ?, now())",
            processInstanceId, processDefinitionId);
        return processInstanceId;
    }

    private static ProcessVariable pv(String name, String value) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(value);
        pv.setType(ProcessVariableType.STRING);
        return pv;
    }

    @Test
    void oppositeNameOrder_noDeadlock_bothCommit() throws Exception {
        UUID pi = seedProcessInstance();
        // Строки существуют заранее (как в проде — обе ветки пишут в уже
        // заведённые имена): гонка идёт по UPDATE-пути UPSERT'а.
        variableDbOperations.setVariables(pi, null, List.of(pv("rel44.alpha", "v-a"), pv("rel44.beta", "v-b")));

        List<ProcessVariable> forward = List.of(pv("rel44.alpha", "v-a"), pv("rel44.beta", "v-b"));
        List<ProcessVariable> reversed = List.of(pv("rel44.beta", "v-b"), pv("rel44.alpha", "v-a"));

        CyclicBarrier gate = new CyclicBarrier(2);
        AtomicBoolean deadlockSeen = new AtomicBoolean(false);
        AtomicReference<String> firstDeadlock = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> {
                try {
                    for (int i = 0; i < ROUNDS && unexpected.get() == null; i++) {
                        // Роняем барьер ПЕРЕД каждой итерацией: обе транзакции
                        // стартуют в lockstep. Deadlock НЕ выводит поток из
                        // цикла (иначе партнёр повиснет на барьере и его
                        // TimeoutException замаскирует первопричину) — пишем
                        // факт и идём на следующий раунд, барьер цел.
                        gate.await(15, TimeUnit.SECONDS);
                        try {
                            transactionTemplate.executeWithoutResult(
                                status -> variableDbOperations.setVariables(pi, null, forward));
                        } catch (Throwable e) {
                            if (isDeadlock(e)) {
                                deadlockSeen.set(true);
                                firstDeadlock.compareAndSet(null, e.toString());
                            } else {
                                unexpected.compareAndSet(null, e);
                            }
                        }
                    }
                } catch (Throwable e) {
                    // Сюда попадает только сломанный барьер: если deadlock уже
                    // зафиксирован — это его следствие, иначе — сюрприз.
                    if (!deadlockSeen.get()) {
                        unexpected.compareAndSet(null, e);
                    }
                }
            });
            Future<?> b = pool.submit(() -> {
                try {
                    for (int i = 0; i < ROUNDS && unexpected.get() == null; i++) {
                        gate.await(15, TimeUnit.SECONDS);
                        try {
                            transactionTemplate.executeWithoutResult(
                                status -> variableDbOperations.setVariables(pi, null, reversed));
                        } catch (Throwable e) {
                            if (isDeadlock(e)) {
                                deadlockSeen.set(true);
                                firstDeadlock.compareAndSet(null, e.toString());
                            } else {
                                unexpected.compareAndSet(null, e);
                            }
                        }
                    }
                } catch (Throwable e) {
                    if (!deadlockSeen.get()) {
                        unexpected.compareAndSet(null, e);
                    }
                }
            });
            a.get(180, TimeUnit.SECONDS);
            b.get(180, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        if (unexpected.get() != null) {
            throw new AssertionError("unexpected (non-deadlock) failure in race threads", unexpected.get());
        }
        assertThat(deadlockSeen.get())
            .as("ABBA deadlock on variables upsert (first: %s) — WO-REL-44 fix missing?", firstDeadlock.get())
            .isFalse();

        // Обе транзакции коммитились: обе переменные на месте с ожидаемыми значениями.
        assertThat(variableDbOperations.getVariableTextValue(pi, "rel44.alpha")).hasValue("v-a");
        assertThat(variableDbOperations.getVariableTextValue(pi, "rel44.beta")).hasValue("v-b");
    }

    /** PG deadlock: SQLState 40P01 где-то в цепочке причин (Spring маппит его в CannotAcquireLock). */
    private static boolean isDeadlock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.postgresql.util.PSQLException psql
                    && "40P01".equals(psql.getSQLState())) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.contains("deadlock detected")) {
                return true;
            }
        }
        return false;
    }
}
