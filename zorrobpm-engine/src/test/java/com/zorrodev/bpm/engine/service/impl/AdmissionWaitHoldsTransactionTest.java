package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import org.camunda.feel.impl.script.FeelScriptEngineFactory;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.script.ScriptEngine;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-8 (NEW2-16, watch): сколько транзакция держит соединение, пока ждёт
 * admission-слот FEEL-пула.
 *
 * <p>Механика (по коду, без домыслов): путь REST/AMQP completion идёт внутри
 * {@code @Transactional} (RuntimeServiceImpl/ActivityServiceImpl/
 * ServiceTaskCompleteListener). FEEL-вычисление внутри (io-mapping/condition)
 * при полном пуле+очереди ждёт в {@code offer(..., queueWaitMs)} до
 * {@code script-queue-wait-seconds} (дефолт 5с) — всё это время открытая
 * транзакция держит соединение из Hikari-пула (и PG-снапшот/локи, взятые до
 * вычисления). Граница: sustained-перегрузка × N параллельных completion'ов —
 * каждый держит соединение до 5с; пул исчерпан → остальные ждут соединения.
 *
 * <p>Тест измеряет факт удержания: перегрузка пула (все слоты заняты дольше
 * окна) × вызов из «транзакции» (эмулируется занятым слотом-семафором:
 * поток-наблюдатель стартует ДО вызова и освобождается ПОСЛЕ) — разность
 * обязана быть ≥ admission-окну. Это НЕ sequential-имитация: N реальных
 * потоков, пул реально полон (V6).
 *
 * <p>POF-мутация (в ПРОД-коде, не в тесте): {@code offer(future, queueWaitMs, …)}
 * → {@code offer(future, 0, …)} в {@code ScriptServiceImpl.submitToPool}
 * (ожидания нет, мгновенный сброс) → victim падает за ~3мс вместо полного
 * окна → ассерт {@code heldMs >= 2000} КРАСНЫЙ
 * ({@code Expecting actual: 3L to be greater than or equal to: 2000L}).
 * Мутация доказывает, что измеряется именно admission-ожидание прод-кода,
 * а не артефакт harness'а.
 */
class AdmissionWaitHoldsTransactionTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void admissionWait_holdsCallerForFullWindow_twoRealThreads() throws Exception {
        long queueWaitSeconds = 2;
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        ScriptServiceImpl svc = new ScriptServiceImpl(unary, expression,
            new tools.jackson.databind.ObjectMapper(),
            new BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            10, 1, 1, queueWaitSeconds);

        // Занимаем единственный воркер + очередь СТРОГО ПО ПОРЯДКУ (прецедент
        // sustained-теста ENG-24: filler крадёт слот иначе): сначала воркер на
        // латче, filler — только после (факт queue.size()==1, не сон).
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerBusy = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        java.util.concurrent.ThreadPoolExecutor exec = null;
        try {
            Future<Object> blocker1 = pool.submit(() -> svc.runWithBudget(() -> {
                workerBusy.countDown();
                release.await(30, TimeUnit.SECONDS);
                return null;
            }, "blocker-1"));
            assertThat(workerBusy.await(15, TimeUnit.SECONDS))
                .as("воркер занят дольше admission-окна")
                .isTrue();
            Future<Object> blocker2 = pool.submit(() -> svc.runWithBudget(() -> {
                release.await(30, TimeUnit.SECONDS);
                return null;
            }, "blocker-2"));
            // Filler гарантированно в ОЧЕРЕДИ, а не на воркере: факт —
            // queue.size()==1 (тело не выполнится, пока воркер на латче).
            java.lang.reflect.Field f = ScriptServiceImpl.class.getDeclaredField("executor");
            f.setAccessible(true);
            exec = (java.util.concurrent.ThreadPoolExecutor) f.get(svc);
            long deadline = System.currentTimeMillis() + 5000;
            while (exec.getQueue().size() != 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(exec.getQueue().size())
                .as("второй слот (очередь) занят дольше admission-окна")
                .isEqualTo(1);

            // «Транзакция»: наблюдатель стартует ДО вызова, счётчик идёт всё
            // время ожидания admission-слота (соединение было бы занято здесь).
            AtomicLong heldMs = new AtomicLong(-1);
            long startNs = System.nanoTime();
            Future<?> victim = pool.submit(() -> {
                try {
                    svc.runWithBudget(() -> null, "victim");
                } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException expected) {
                    heldMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
                }
            });
            victim.get(30, TimeUnit.SECONDS);

            assertThat(heldMs.get())
                .as("sustained-перегрузка: вызывающий (и его транзакция/соединение) "
                    + "ждёт ПОЛНОЕ admission-окно %sс до сброса", queueWaitSeconds)
                .isGreaterThanOrEqualTo(queueWaitSeconds * 1000L);
            assertThat(heldMs.get())
                .as("ожидание ограничено окном + запас, а не бесконечно")
                .isLessThan(queueWaitSeconds * 1000L + 5000L);

            release.countDown();
            blocker1.get(15, TimeUnit.SECONDS);
            blocker2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void admissionWait_windowIsConfigurable_notHardcoded() {
        // Окно — настройка (script-queue-wait-seconds), а не хардкод: конструктор
        // принимает значение, fail-fast на отрицательном — прецедент P-41.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            serviceWithWait(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("script-queue-wait-seconds");
    }

    private static ScriptServiceImpl serviceWithWait(long queueWaitSeconds) {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        return new ScriptServiceImpl(unary, expression,
            new tools.jackson.databind.ObjectMapper(),
            new BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            10, 1, 1, queueWaitSeconds);
    }
}
