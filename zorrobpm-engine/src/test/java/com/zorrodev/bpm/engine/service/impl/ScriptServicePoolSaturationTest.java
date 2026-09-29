package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import org.camunda.feel.impl.script.FeelScriptEngineFactory;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import tools.jackson.databind.ObjectMapper;

import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WO-REL-46 (fallback — честно вместо точечной замены слота, см. отчёт почему):
 * poolSize конфигурируется через {@code zorrobpm.engine.script-pool-size}, а
 * насыщение пула видно заранее — троттлированный warn + свежие gauges
 * {@code zbpm.script.pool.active/size} (Grafana-правило {@code zbpm-feel-pool-saturated}).
 *
 * <p>Оба теста — реальные потоки против реального прод-пути
 * (V6, детерминированы латчами/фактами, не снами): занятость доказывается
 * фактом очереди ({@code getQueue().size() == 1}), а не фиксированной паузой.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScriptServicePoolSaturationTest {

    @BeforeAll
    static void resetToWarn() {
        Logger logger = (Logger) LoggerFactory.getLogger(ScriptServiceImpl.class);
        logger.setLevel(Level.WARN);
    }

    private static ScriptServiceImpl service(long timeoutSeconds, int poolSize,
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry) {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        return new ScriptServiceImpl(unary, expression, new ObjectMapper(),
            new BpmMetrics(registry), timeoutSeconds, poolSize, 10, 5);
    }

    private static ThreadPoolExecutor readPool(ScriptServiceImpl service) throws Exception {
        Field f = ScriptServiceImpl.class.getDeclaredField("executor");
        f.setAccessible(true);
        return (ThreadPoolExecutor) f.get(service);
    }

    private static BpmMetrics readMetrics(ScriptServiceImpl service) throws Exception {
        Field f = ScriptServiceImpl.class.getDeclaredField("bpmMetrics");
        f.setAccessible(true);
        return (BpmMetrics) f.get(service);
    }

    private static ProcessVariable longVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.LONG);
        v.setValue(value);
        return v;
    }

    @Test
    void poolSizeIsHonored_andSizeGaugeSet() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ScriptServiceImpl service = service(10, 5, registry);
        try {
            ThreadPoolExecutor pool = readPool(service);
            assertThat(pool.getCorePoolSize()).as("core pool size").isEqualTo(5);
            assertThat(pool.getMaximumPoolSize()).as("max pool size").isEqualTo(5);
            // Знаменатель для Grafana-правила насыщения выставляется конструктором.
            assertThat(registry.find("zbpm.script.pool.size").gauge().value())
                .as("zbpm.script.pool.size gauge").isEqualTo(5.0);
        } finally {
            service.shutdown();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void saturationWarnsOnceAndRecovers(CapturedOutput output) throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        // poolSize=1: один занятый слот = полное насыщение. timeout=1s, чтобы
        // вставшие в очередь запросы вышли чистым timeout, а не висели.
        ScriptServiceImpl service = service(1, 1, registry);
        ThreadPoolExecutor pool = readPool(service);

        // Единственный воркер занимаем напрямую — детерминировано, без FEEL-калибровок.
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        pool.submit(() -> {
            workerStarted.countDown();
            releaseWorker.await(30, TimeUnit.SECONDS);
            return null;
        });
        assertThat(workerStarted.await(10, TimeUnit.SECONDS)).as("worker occupied").isTrue();

        // Фоновый saturated-вызов: warn #1, встаёт в очередь, выходит в timeout.
        List<Throwable> bgErrors = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean bgTimedOut = new AtomicBoolean(false);
        Thread bg = new Thread(() -> {
            try {
                service.evaluateExpression("x + 1", List.of(longVar("x", "1")));
            } catch (EngineException e) {
                if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                    bgTimedOut.set(true);
                } else {
                    bgErrors.add(e);
                }
            }
        });
        bg.setDaemon(true);
        bg.start();

        // ФАКТ (не сон): фоновый запрос в очереди — значит его saturation-check
        // уже отработал (check идёт строго до submit в коде), warn #1 записан.
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.getQueue().size() == 1);

        // Второй saturated-вызов с этого потока: троттлинг душит повторный warn.
        try {
            service.evaluateExpression("x + 1", List.of(longVar("x", "2")));
            bgErrors.add(new IllegalStateException("saturated call must time out, pool worker is held"));
        } catch (EngineException e) {
            assertThat(e.getMessage()).as("main saturated call times out cleanly (queued, not rejected)")
                .contains("timed out");
        }

        bg.join(15_000);
        assertThat(bgErrors).as("background saturated call clean: %s", bgErrors).isEmpty();
        assertThat(bgTimedOut.get()).as("background saturated call timed out").isTrue();

        // Ровно ОДИН warn на два saturated-вызова — троттлинг 60с работает.
        // Без recordSaturation было бы 0 (POF-мутация), без троттлинга — 2.
        long warns = output.getOut().lines()
            .filter(line -> line.contains("Script pool saturated"))
            .count();
        assertThat(warns).as("exactly one saturation warn (second throttled)").isEqualTo(1L);

        // Gauge свежий ВО ВРЕМЯ насыщения (finally после каждого eval), а не
        // протухший со времён последнего timeout/reject.
        assertThat(registry.find("zbpm.script.pool.active").gauge().value())
            .as("zbpm.script.pool.active during saturation").isEqualTo(1.0);

        // Освобождение: пул снова обслуживает.
        releaseWorker.countDown();
        try {
            Object result = service.evaluateExpression("x + 1", List.of(longVar("x", "41")));
            assertThat(((Number) result).longValue()).as("pool serves after release").isEqualTo(42L);
            // Gauge читает getActiveCount() (документированно approximate):
            // внутренний decrement счётчика происходит чуть позже, чем
            // FutureTask разблокирует Future.get(), поэтому прямой assert
            // сразу после evaluate — гонка (реальный CI pipeline 171434
            // поймал 1.0 вместо 0.0). Ждём фактического освобождения пула
            // тем же Awaitility-паттерном, что выше для очереди. Refresh
            // внутри поллинга обязателен: gauge обновляется только из
            // finally eval'а, после shutdown его уже никто не перепишет —
            // ждать замороженное значение без refresh было бы hang-until-timeout.
            BpmMetrics metrics = readMetrics(service);
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                metrics.updateScriptPoolMetrics(pool);
                return registry.find("zbpm.script.pool.active").gauge().value() == 0.0;
            });
        } finally {
            service.shutdown();
        }
    }
}
