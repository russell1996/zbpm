package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.service.ScriptOverloadException;
import org.camunda.feel.impl.script.FeelScriptEngineFactory;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

import javax.script.ScriptEngine;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * WO-ENG-24: admission control вместо мгновенного AbortPolicy-отказа.
 *
 * <p>Аудит замерил 67% отказов на 32 одновременных вызывающих тривиального
 * {@code x + 1} (пул 8 + очередь 10 = 18 мест): переходный всплеск сверх
 * pool+queue откатывал целые операции. Новый сабмит ждёт освобождения
 * ограниченное время ({@code script-queue-wait-seconds}) и сбрасывает только
 * sustained-перегрузку — как временную ({@link ScriptOverloadException} →
 * HTTP 503 + Retry-After), а не как неверный запрос (422).
 *
 * <p>Оба теста — реальные потоки против реального прод-пути
 * (V6, детерминированы латчами/фактами, не снами).
 */
class ScriptBulkheadAdmissionTest {

    private static ScriptServiceImpl service(int poolSize, int queueCapacity, long queueWaitSeconds,
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry) {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        return new ScriptServiceImpl(unary, expression, new ObjectMapper(),
            new BpmMetrics(registry), 10, poolSize, queueCapacity, queueWaitSeconds);
    }

    /**
     * Критерий 1 (единица admission-механики): короткий всплеск сверх
     * pool+queue впитывается ожиданием — 0 отказов, все результаты верны.
     *
     * <p>POF-мутация: {@code queueWaitSeconds=0} (мгновенный сброс как раньше) —
     * этот же тест КРАСНЫЙ: overflow-вызовы получают ScriptOverloadException.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void burstBeyondPoolAndQueue_isAbsorbed_noRejections() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        // pool=4 + queue=4 = 8 мест, wait=30с: всплеск 16 дешёвых eval'ов —
        // вдвое сверх вместимости — обязан впитаться весь.
        ScriptServiceImpl svc = new ScriptServiceImpl(
            new FeelUnaryTestsScriptEngineFactory().getScriptEngine(),
            new FeelScriptEngineFactory().getScriptEngine(),
            new ObjectMapper(), new BpmMetrics(registry), 10, 4, 4, 30);
        try {
            int callers = 16;
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger ok = new AtomicInteger(0);
            AtomicInteger rejected = new AtomicInteger(0);
            List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            Thread[] threads = new Thread[callers];
            for (int i = 0; i < callers; i++) {
                final int n = i;
                threads[i] = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await(30, TimeUnit.SECONDS);
                        Object result = svc.evaluateExpression("x + 1", List.of(
                            longVar("x", String.valueOf(n))));
                        if (((Number) result).longValue() == n + 1) {
                            ok.incrementAndGet();
                        } else {
                            errors.add(new IllegalStateException("wrong result for n=" + n + ": " + result));
                        }
                    } catch (ScriptOverloadException e) {
                        rejected.incrementAndGet();
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
                threads[i].setDaemon(true);
                threads[i].start();
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all callers ready").isTrue();
            go.countDown();
            for (Thread t : threads) {
                t.join(90_000);
            }
            assertThat(errors).as("no unexpected errors: %s", errors).isEmpty();
            assertThat(rejected.get()).as("burst absorbed, 0 rejections").isEqualTo(0);
            assertThat(ok.get()).as("all burst evaluations correct").isEqualTo(callers);
        } finally {
            svc.shutdown();
        }
    }

    /**
     * Критерии 2+3 (единица сброса): sustained-перегрузка (пул+очередь заняты
     * дольше admission-окна) сбрасывается как {@link ScriptOverloadException}
     * — тип, который 503-хендлер маппит в 503 + Retry-After, — и инкрементит
     * {@code zbpm.script.rejected} через реальный прод-путь.
     *
     * <p>POF-мутация: вернуть старый {@code EngineException("...pool full")}
     * вместо {@code ScriptOverloadException} — оба ассерта КРАСНЫЕ (тип не
     * тот, 503-хендлер его не поймает). P-67: ассерт на КОНКРЕТНЫЙ тип +
     * КОНКРЕТНОЕ значение счётчика и Retry-After, не «что-то вызвалось».
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void sustainedOverload_shedsAsOverloadType_andCountsMetric() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        // pool=2 + queue=1, wait=1с: насыщение детерминировано латчами.
        ScriptServiceImpl svc = service(2, 1, 1, registry);
        java.util.concurrent.ThreadPoolExecutor pool = readPool(svc);
        try {
            // Строгий порядок (иначе filler крадёт слот воркера и barrier
            // виснет): сначала оба воркера на латче, filler — только после.
            CountDownLatch workersBusy = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Thread[] workers = new Thread[2];
            for (int i = 0; i < workers.length; i++) {
                workers[i] = new Thread(() -> {
                    try {
                        svc.runWithBudget(() -> {
                            workersBusy.countDown();
                            release.await(30, TimeUnit.SECONDS);
                            return null;
                        }, "eng24-holder");
                    } catch (RuntimeException e) {
                        // teardown — не asserted-путь
                    }
                });
                workers[i].setDaemon(true);
                workers[i].start();
            }
            Thread filler = null;
            try {
                assertThat(workersBusy.await(10, TimeUnit.SECONDS))
                    .as("pool (2) occupied").isTrue();
                // Оба воркера заняты — filler гарантированно встанет в
                // очередь, а не на воркер (факт — queue.size()==1, не сон:
                // его тело не выполнится, пока воркеры на латче).
                filler = new Thread(() -> {
                    try {
                        svc.runWithBudget(() -> {
                            release.await(30, TimeUnit.SECONDS);
                            return null;
                        }, "eng24-filler");
                    } catch (RuntimeException e) {
                        // teardown — не asserted-путь
                    }
                });
                filler.setDaemon(true);
                filler.start();
                await().atMost(Duration.ofSeconds(5)).until(() -> pool.getQueue().size() == 1);

                double rejectedBefore =
                    registry.find("zbpm.script.rejected").counter().count();

                // Перегрузка, занятая дольше admission-окна (1с), — сброс.
                // ФАКТ переполнения: очередь реально полна (размер 1/1), слоты
                // не освободятся (holders на латче) — отказ идёт из ветки
                // сброса, а не из гонки старта.
                assertThatThrownBy(() -> svc.evaluateExpression("x + 1", List.of(longVar("x", "1"))))
                    .as("sustained overload sheds load")
                    .isInstanceOf(ScriptOverloadException.class)
                    .hasMessageContaining("retry later");

                // Retry-After — конкретное значение прод-кода (max(1, wait)=1),
                // не «заголовок есть».
                try {
                    svc.evaluateExpression("x + 1", List.of(longVar("x", "2")));
                    assertThat(false).as("second overload must also shed").isTrue();
                } catch (ScriptOverloadException e) {
                    assertThat(e.getRetryAfterSeconds())
                        .as("Retry-After seconds")
                        .isEqualTo(1);
                }

                // Метрика критерия 3 — дельта через реальный прод-путь.
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(registry.find("zbpm.script.rejected").counter().count())
                        .as("zbpm.script.rejected incremented by shed load")
                        .isGreaterThanOrEqualTo(rejectedBefore + 2.0));
            } finally {
                release.countDown();
                for (Thread t : workers) {
                    t.join(15_000);
                }
                if (filler != null) {
                    filler.join(15_000);
                }
            }
        } finally {
            svc.shutdown();
        }
    }

    /**
     * Конфиг-валидация (P-41): новые ручки проходят ту же fail-fast проверку,
     * что соседняя {@code script-pool-size} — нулевая/отрицательная очередь и
     * отрицательное ожидание отвергаются в конструкторе.
     */
    @Test
    void constructor_rejectsInvalidQueueConfig() {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        ObjectMapper mapper = new ObjectMapper();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        BpmMetrics metrics = new BpmMetrics(registry);
        assertThatThrownBy(() -> new ScriptServiceImpl(unary, expression, mapper, metrics, 10, 2, 0, 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("script-queue-capacity");
        assertThatThrownBy(() -> new ScriptServiceImpl(unary, expression, mapper, metrics, 10, 2, 10, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("script-queue-wait-seconds");
    }

    private static java.util.concurrent.ThreadPoolExecutor readPool(ScriptServiceImpl svc) throws Exception {
        java.lang.reflect.Field f = ScriptServiceImpl.class.getDeclaredField("executor");
        f.setAccessible(true);
        return (java.util.concurrent.ThreadPoolExecutor) f.get(svc);
    }

    private static com.zorrodev.bpm.contract.model.ProcessVariable longVar(String name, String value) {
        com.zorrodev.bpm.contract.model.ProcessVariable v =
            new com.zorrodev.bpm.contract.model.ProcessVariable();
        v.setName(name);
        v.setType(com.zorrodev.bpm.contract.model.ProcessVariableType.LONG);
        v.setValue(value);
        return v;
    }
}
