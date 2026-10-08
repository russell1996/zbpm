package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.service.AdmissionLease;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.camunda.feel.impl.script.FeelScriptEngineFactory;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.script.ScriptEngine;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-ENG-35 (NEW2-16): двухфазный admission script-пула — ожидание места
 * происходит ДО транзакции вызывающего, а не внутри неё.
 *
 * <p>Почва: пул 1+1 (мест в гейте столько же), окно 2с. Все тесты — реальные
 * потоки против реального пула (V6); гонка ГАРАНТИРОВАНА конструкцией
 * (факты workerBusy/queueSize, а не sleep): filler'ы занимают слоты строго
 * до старта жертвы.
 *
 * <p>Честная рамка (что именно доказывается): эти тесты доказывают МЕХАНИЗМ
 * (с лизой — ноль ожидания внутри; без лизы — полное окно + счётчик in-tx),
 * а «соединение не удерживается» следует дедуктивно: гейт стоит ДО любой
 * {@code @Transactional}-границы (см. {@code RuntimeResource},
 * {@code ServiceTaskCompleteListener}, {@code TimerBatchProcessor} — ни один
 * не делает DB-чтений до гейта), а внутритранзакционные добивки считаются
 * счётчиком {@code zbpm.script.admission.in_tx} (сквозной IT держит его на 0
 * при сбросе через гейт).
 *
 * <p>POF-мутации (в ПРОД-коде, не в тесте):
 * <ul>
 *   <li>M1 «вернуть ожидание внутрь»: {@code admitOutsideTx} → сразу
 *   {@code return AdmissionLease.noop()} (без acquire и holder) → тест 1
 *   КРАСНЫЙ (submit ждёт окно внутри), тест 3 КРАСНЫЙ (гейт не сбрасывает).</li>
 *   <li>M3 «убрать учёт»: удалить вызов {@code submitInTxFallback} → тест 2
 *   КРАСНЫЙ (in-tx не посчитан).</li>
 *   <li>M4 «окно 0» (прецедент AUDIT-8 POF-1): {@code queueWaitMs → 0} →
 *   тест 2 КРАСНЫЙ (окна нет).</li>
 *   <li>M5 «утечка лизы»: {@code WholeOpLease.close} без release → тест 7
 *   КРАСНЫЙ; убрать release из {@code SingleEvalFuture} → тест 6 КРАСНЫЙ.</li>
 * </ul>
 */
class AdmissionOutsideTxTest {

    private static final long WINDOW_SECONDS = 2;
    private static final long WINDOW_MS = WINDOW_SECONDS * 1000;

    private ScriptServiceImpl svc;
    private SimpleMeterRegistry registry;
    private ExecutorService threads;

    private ScriptServiceImpl newSvc(long timeoutSeconds) {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        registry = new SimpleMeterRegistry();
        threads = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "admission-test");
            t.setDaemon(true);
            return t;
        });
        svc = new ScriptServiceImpl(unary, expression,
            new tools.jackson.databind.ObjectMapper(),
            new BpmMetrics(registry),
            timeoutSeconds, 1, 1, WINDOW_SECONDS);
        return svc;
    }

    @AfterEach
    void tearDown() {
        if (svc != null) {
            svc.shutdown();
        }
        if (threads != null) {
            threads.shutdownNow();
        }
    }

    private int permits() throws Exception {
        java.lang.reflect.Field f = ScriptServiceImpl.class.getDeclaredField("admissionGate");
        f.setAccessible(true);
        return ((java.util.concurrent.Semaphore) f.get(svc)).availablePermits();
    }

    private java.util.concurrent.ThreadPoolExecutor pool() throws Exception {
        java.lang.reflect.Field f = ScriptServiceImpl.class.getDeclaredField("executor");
        f.setAccessible(true);
        return (java.util.concurrent.ThreadPoolExecutor) f.get(svc);
    }

    private double inTxCount() {
        io.micrometer.core.instrument.Counter c = registry.find("zbpm.script.admission.in_tx").counter();
        return c == null ? 0.0 : c.count();
    }

    private void awaitQueueSize(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (pool().getQueue().size() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(pool().getQueue().size())
            .as("очередь пула — факт насыщения, не сон")
            .isEqualTo(expected);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void preHeldLease_evalBypassesWait_noInTx() throws Exception {
        newSvc(10);
        double inTxBefore = inTxCount();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerBusy = new CountDownLatch(1);

        // Жертва гейтуется ПЕРВОЙ (держит whole-op лизу весь тест).
        AdmissionLease victimLease = svc.admitOutsideTx();
        try {
            // Блокер — тоже гейтованный (как прод-трафик): лиза + воркер на латче.
            Future<?> blocker = threads.submit(() -> {
                try (AdmissionLease ignored = svc.admitOutsideTx()) {
                    return svc.runWithBudget(() -> {
                        workerBusy.countDown();
                        release.await(60, TimeUnit.SECONDS);
                        return "blocker-done";
                    }, "blocker");
                }
            });
            assertThat(workerBusy.await(30, TimeUnit.SECONDS))
                .as("воркер занят")
                .isTrue();

            // Submit жертвы — СТРОГО на потоке-владельце лизы (holder —
            // ThreadLocal): воркер занят, очередь ПУСТА → встаёт мгновенно,
            // без ограниченного offer-wait. Submit идёт через настоящий
            // приватный прод-метод (рефлексия — прецедент
            // AdmissionWaitHoldsTransactionTest): runWithBudget здесь не
            // годится — он ждёт и выполнения, а меряется только submit.
            //
            // Честная рамка: при свободной очереди ЛЮБОЙ путь мгновенен —
            // скорость здесь НЕ дискриминирует лизу. Лизу дискриминирует
            // white-box ассерт ниже: submit по лизе НЕ берёт новый пермит
            // (место уже учтено гейтом), а без лизы (мутация M1: noop вместо
            // acquire+holder) submit забирает разовый пермит — счётчик падает.
            java.lang.reflect.Method submit = ScriptServiceImpl.class
                .getDeclaredMethod("submitToPool",
                    java.util.concurrent.Callable.class, String.class);
            submit.setAccessible(true);
            int permitsBeforeSubmit = permits();
            long startNs = System.nanoTime();
            @SuppressWarnings("unchecked")
            Future<Object> victimFuture = (Future<Object>) submit.invoke(svc,
                (java.util.concurrent.Callable<Object>) () -> "victim-result", "victim");
            long submitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            assertThat(submitMs)
                .as("submit с лизой не ждёт admission-окно (воркер занят, очередь свободна)")
                .isLessThan(WINDOW_MS);
            assertThat(permits())
                .as("submit по whole-op лизе новый пермит НЕ берёт (M1: без holder уходит разовый)")
                .isEqualTo(permitsBeforeSubmit);
            release.countDown();
            assertThat(blocker.get(30, TimeUnit.SECONDS)).isEqualTo("blocker-done");
            assertThat(victimFuture.get(30, TimeUnit.SECONDS)).isEqualTo("victim-result");
            assertThat(inTxCount() - inTxBefore)
                .as("ни одного in-tx ожидания: всё прошло по лизе/прямому пути")
                .isZero();
        } finally {
            victimLease.close();
            release.countDown();
        }
        assertThat(permits())
            .as("все лизы возвращены — утечки слотов нет")
            .isEqualTo(2);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void mixedTraffic_reservedSubmitFallsBackBounded_countsInTx() throws Exception {
        // Честный предел дизайна: пул забит трафиком, обошедшим гейт (сырой
        // pool.execute — в проде такого нет: все прод-сабмиты идут через
        // submitToPool с пермитом; здесь — модель mixed-traffic). Пермиты при
        // этом СВОБОДНЫ — лиза выдаётся, но места в пуле реально нет:
        // reserved-submit отказывает и падает в прежний ограниченный wait
        // (не новый механизм) + считается. Лиза не притворяется, что создаёт
        // место, — она лишь выносит ожидание из транзакции, когда место есть.
        newSvc(10);
        double inTxBefore = inTxCount();
        java.util.concurrent.ThreadPoolExecutor raw = pool();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerBusy = new CountDownLatch(1);
        raw.execute(() -> {
            workerBusy.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(workerBusy.await(30, TimeUnit.SECONDS)).as("воркер занят").isTrue();
        raw.execute(() -> {
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        awaitQueueSize(1);
        assertThat(permits()).as("гейт-обхода пермиты не трогают").isEqualTo(2);

        AdmissionLease lease = svc.admitOutsideTx();
        try {
            long startNs = System.nanoTime();
            try {
                svc.runWithBudget(() -> "victim-result", "victim");
                assertThat(true).as("ожидался сброс перегрузки").isFalse();
            } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException expected) {
                long heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                assertThat(heldMs)
                    .as("добивка ограничена тем же окном (не бесконечна)")
                    .isGreaterThanOrEqualTo(WINDOW_MS);
                assertThat(heldMs).isLessThan(WINDOW_MS + 5000L);
            }
        } finally {
            lease.close();
            release.countDown();
        }
        assertThat(inTxCount() - inTxBefore)
            .as("добивка после отказа reserved-submit посчитана как in-tx")
            .isEqualTo(1.0);
        assertThat(permits()).isEqualTo(2);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void noLease_evalWaitsFullWindow_countsInTx() throws Exception {
        newSvc(10);
        double inTxBefore = inTxCount();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerBusy = new CountDownLatch(1);

        // Два НЕгейтованных блокера занимают оба места (legacy/mixed трафик):
        // b1 — воркер на латче, b2 — очередь (факт queue==1).
        Future<?> b1 = threads.submit(() -> svc.runWithBudget(() -> {
            workerBusy.countDown();
            release.await(60, TimeUnit.SECONDS);
            return null;
        }, "b1"));
        assertThat(workerBusy.await(30, TimeUnit.SECONDS)).as("воркер занят").isTrue();
        Future<?> b2 = threads.submit(() -> svc.runWithBudget(() -> {
            release.await(60, TimeUnit.SECONDS);
            return null;
        }, "b2"));
        awaitQueueSize(1);

        // Жертва БЕЗ лизы: ограниченный offer-wait — полное окно, потом 503.
        long startNs = System.nanoTime();
        try {
            svc.runWithBudget(() -> "victim-result", "victim");
            assertThat(true).as("ожидался сброс перегрузки").isFalse();
        } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException expected) {
            long heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            assertThat(expected.getRetryAfterSeconds())
                .as("тот же Retry-After, что у гейта (окно)")
                .isEqualTo((int) WINDOW_SECONDS);
            assertThat(heldMs)
                .as("без лизы вызывается полное admission-окно (контроль к тесту 1)")
                .isGreaterThanOrEqualTo(WINDOW_MS);
            assertThat(heldMs)
                .as("ожидание ограничено окном, а не бесконечно")
                .isLessThan(WINDOW_MS + 5000L);
        } finally {
            release.countDown();
            b1.get(30, TimeUnit.SECONDS);
            b2.get(30, TimeUnit.SECONDS);
        }
        assertThat(inTxCount() - inTxBefore)
            .as("ровно одно in-tx ожидание — сама жертва (b1/b2 взяли разовые "
                + "пермиты мгновенно, в fallback не ходили)")
            .isEqualTo(1.0);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void gate_shedsWithSame503WhenPermitsExhausted() throws Exception {
        newSvc(10);
        double inTxBefore = inTxCount();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerBusy = new CountDownLatch(1);

        // Оба места заняты прод-представительно: лиза + слот пула у каждого.
        Future<?> b1 = threads.submit(() -> {
            try (AdmissionLease ignored = svc.admitOutsideTx()) {
                return svc.runWithBudget(() -> {
                    workerBusy.countDown();
                    release.await(60, TimeUnit.SECONDS);
                    return null;
                }, "b1");
            }
        });
        assertThat(workerBusy.await(30, TimeUnit.SECONDS)).as("воркер занят").isTrue();
        Future<?> b2 = threads.submit(() -> {
            try (AdmissionLease ignored = svc.admitOutsideTx()) {
                return svc.runWithBudget(() -> {
                    release.await(60, TimeUnit.SECONDS);
                    return null;
                }, "b2");
            }
        });
        awaitQueueSize(1);
        assertThat(permits()).as("все пермиты разобраны").isZero();

        long startNs = System.nanoTime();
        try {
            svc.admitOutsideTx();
            assertThat(true).as("ожидался сброс гейта").isFalse();
        } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException expected) {
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            assertThat(expected.getRetryAfterSeconds())
                .as("тот же Retry-After (окно), что у внутритранзакционного сброса")
                .isEqualTo((int) WINDOW_SECONDS);
            assertThat(waitedMs)
                .as("гейт ждёт то же окно — но БЕЗ транзакции вызывающего")
                .isGreaterThanOrEqualTo(WINDOW_MS);
            assertThat(waitedMs).isLessThan(WINDOW_MS + 5000L);
        } finally {
            release.countDown();
            b1.get(30, TimeUnit.SECONDS);
            b2.get(30, TimeUnit.SECONDS);
        }
        assertThat(inTxCount() - inTxBefore)
            .as("сброс гейта не ходит в submitToPool — in-tx счётчик пуст")
            .isZero();
        assertThat(permits()).isEqualTo(2);
    }

    @Test
    void admitInsideTx_failFast() {
        newSvc(10);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> svc.admitOutsideTx())
                .as("гейт внутри транзакции — громкий отказ, а не тихое удержание соединения")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OUTSIDE");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void reentrantAdmit_noop_outerLeaseOwnsSlot() throws Exception {
        newSvc(10);
        assertThat(permits()).isEqualTo(2);
        AdmissionLease outer = svc.admitOutsideTx();
        assertThat(permits()).isEqualTo(1);
        AdmissionLease inner = svc.admitOutsideTx();
        assertThat(permits()).as("реентрантный вход пермит не берёт").isEqualTo(1);
        inner.close();
        assertThat(permits()).as("закрытие noop-лизы слот не возвращает").isEqualTo(1);
        outer.close();
        assertThat(permits()).as("слот вернул владелец").isEqualTo(2);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void singleEvalPermit_releasedOnSuccessAndTimeout() throws Exception {
        newSvc(1);
        assertThat(permits()).isEqualTo(2);

        // Успех: разовый пермит вернулся по завершении eval.
        assertThat(svc.runWithBudget(() -> "fast", "fast-eval")).isEqualTo("fast");
        assertThat(permits()).as("разовый пермит возвращён после успеха").isEqualTo(2);

        // Таймаут: задача висит, get(1с) рвёт ожиданием — слот всё равно вернулся.
        CountDownLatch release = new CountDownLatch(1);
        Future<?> hanging = threads.submit(() -> svc.runWithBudget(() -> {
            release.await(60, TimeUnit.SECONDS);
            return null;
        }, "hanging"));
        // Факт: задача на воркере (заняла разовый пермит), а не в очереди.
        long deadline = System.currentTimeMillis() + 10_000;
        while (pool().getActiveCount() != 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(pool().getActiveCount()).as("висящая задача на воркере").isEqualTo(1);
        try {
            hanging.get(30, TimeUnit.SECONDS);
            assertThat(true).as("ожидался EngineException по таймауту").isFalse();
        } catch (java.util.concurrent.ExecutionException e) {
            assertThat(e.getCause())
                .as("таймаут — EngineException прод-типа, не обёртка")
                .isInstanceOf(com.zorrodev.bpm.contract.exception.EngineException.class)
                .hasMessageContaining("timed out");
        } finally {
            release.countDown();
        }
        assertThat(permits())
            .as("разовый пермит возвращён и при таймауте — утечки нет")
            .isEqualTo(2);
    }

    @Test
    void wholeOpLease_closeIdempotent() throws Exception {
        newSvc(10);
        AdmissionLease lease = svc.admitOutsideTx();
        assertThat(permits()).isEqualTo(1);
        lease.close();
        assertThat(permits()).isEqualTo(2);
        lease.close();
        assertThat(permits())
            .as("двойное закрытие не возвращает слот дважды (иначе — накачка пермитов)")
            .isEqualTo(2);
    }

    @Test
    void gatedEvaluateExpression_functional() {
        newSvc(10);
        try (AdmissionLease ignored = svc.admitOutsideTx()) {
            // Unary-test движок: тот же путь, что условия потоков (FlowNavigator).
            assertThat(svc.evaluateScript("1 = 1", List.of())).isEqualTo(Boolean.TRUE);
        }
    }
}
