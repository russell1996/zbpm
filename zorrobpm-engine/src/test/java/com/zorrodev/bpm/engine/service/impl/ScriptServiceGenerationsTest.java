package com.zorrodev.bpm.engine.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WO-REL-33 F22: многократный timeout non-cooperative выражения обязан держать
 * верхнюю границу суммарных потоков всех поколений worker'ов.
 *
 * <p>Мок-движок игнорирует interrupt (non-cooperative) и висит до релиза —
 * детерминировано, без FEEL-калибровок. Три последовательных timeout: на старом
 * коде каждое поколение выживает (+1 поток за timeout, итого 3 пула и +3 живых
 * потока); с фиксом — один пул на все времена, живые потоки ≤ maxPoolSize.
 *
 * <p>Использует только существующий API ({@code evaluateExpression},
 * приватное поле {@code executor}) — компилируется и на pre-fix базе (RED там).
 */
class ScriptServiceGenerationsTest {

    private static final AtomicInteger SCRIPT_THREADS = new AtomicInteger();

    /** Non-cooperative движок: висит на латче, прерывания игнорирует. */
    private static ScriptEngine stuckEngine(CountDownLatch release) throws Exception {
        ScriptEngine engine = mock(ScriptEngine.class);
        when(engine.eval(any(String.class), any(ScriptContext.class))).thenAnswer(inv -> {
            while (release.getCount() > 0) {
                try {
                    release.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // non-cooperative: прерывание игнорируется, продолжаем висеть
                }
            }
            return 42;
        });
        return engine;
    }

    private ScriptServiceImpl serviceWithStuckEngine(CountDownLatch release, long timeoutSeconds)
            throws Exception {
        return new ScriptServiceImpl(stuckEngine(release), stuckEngine(release),
            new tools.jackson.databind.ObjectMapper(),
            new com.zorrodev.bpm.engine.metrics.BpmMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            timeoutSeconds);
    }

    private static ThreadPoolExecutor readPool(ScriptServiceImpl service) throws Exception {
        java.lang.reflect.Field f = ScriptServiceImpl.class.getDeclaredField("executor");
        f.setAccessible(true);
        return (ThreadPoolExecutor) f.get(service);
    }

    private static int liveScriptThreads() {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("script-eval") && t.isAlive()) {
                count++;
            }
        }
        return count;
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void threeNonCooperativeTimeouts_boundedTotalThreads() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ScriptServiceImpl service = serviceWithStuckEngine(release, 1);
        try {
            int before = liveScriptThreads();

            // Три последовательных timeout non-cooperative выражения.
            for (int i = 0; i < 3; i++) {
                try {
                    service.evaluateExpression("stuck" + i, List.of());
                    throw new IllegalStateException("non-cooperative выражение обязано выйти в timeout");
                } catch (com.zorrodev.bpm.contract.exception.EngineException e) {
                    assertThat(e.getMessage()).contains("timed out");
                }
            }

            ThreadPoolExecutor pool = readPool(service);
            int maxSize = pool.getMaximumPoolSize();
            int liveNow = liveScriptThreads();

            // Верхняя граница: живые потоки всех поколений ≤ maxPoolSize текущего пула.
            // Старый код: +1 выжившее поколение (и +1 поток) за каждый timeout → 3 > 2.
            assertThat(liveNow - before)
                .as("суммарные живые потоки всех поколений (было %d, стало %d, maxPoolSize %d)",
                    before, liveNow, maxSize)
                .isLessThanOrEqualTo(maxSize);
        } finally {
            release.countDown();
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void replaceWorker_doesNotMultiplyPools() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ScriptServiceImpl service = serviceWithStuckEngine(release, 1);
        try {
            ThreadPoolExecutor first = readPool(service);

            AtomicBoolean timedOut = new AtomicBoolean(false);
            for (int i = 0; i < 2 && !timedOut.get(); i++) {
                try {
                    service.evaluateExpression("stuck" + i, List.of());
                } catch (com.zorrodev.bpm.contract.exception.EngineException e) {
                    if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                        timedOut.set(true);
                    }
                }
            }
            assertThat(timedOut.get()).as("timeout произошёл").isTrue();

            ThreadPoolExecutor after = readPool(service);

            // С фиксом пул один на все времена (переиспользуется), со старым кодом —
            // каждый timeout создаёт новый экземпляр.
            assertThat(after).as("пул не размножается между timeout'ами").isSameAs(first);
        } finally {
            release.countDown();
        }
    }
}
