package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.camunda.feel.impl.script.FeelScriptEngineFactory;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-5 item 4: two simultaneous FEEL timeouts must not lose the pool.
 * Both threads time out together (latch start, same slow expression) → both run
 * {@code replaceWorker} concurrently. Afterwards the pool must still serve a fast
 * evaluation (no lost pool, no {@code RejectedExecutionException}).
 *
 * Honestly: the collision itself is timing-dependent (regression shape — green is
 * deterministic, catching the old race is probabilistic); mutual exclusion is
 * proven by construction ({@code synchronized}), this test pins the behavior.
 */
class ScriptServiceTimeoutRaceTest {

    private ScriptService serviceWithTimeout(long seconds) {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        return new ScriptServiceImpl(unary, expression, new tools.jackson.databind.ObjectMapper(), new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), seconds);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentTimeouts_bothFailCleanlyAndPoolSurvives() throws Exception {
        ScriptService service = serviceWithTimeout(1);
        String slowExpr = "for i in 1..5000 return for j in 1..5000 return i * j";
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> timeouts = Collections.synchronizedList(new ArrayList<>());

        Runnable attempt = () -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                try {
                    service.evaluateExpression(slowExpr, List.of());
                    errors.add(new IllegalStateException("slow expression should have timed out"));
                } catch (EngineException e) {
                    if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                        timeouts.add(e);
                    } else {
                        errors.add(e);
                    }
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        };
        Thread first = new Thread(attempt);
        Thread second = new Thread(attempt);
        first.start();
        second.start();
        go.countDown();
        assertThat(done.await(45, TimeUnit.SECONDS)).as("both timeouts finished").isTrue();

        assertThat(errors).as("no unexpected failures (incl. RejectedExecution): %s", errors).isEmpty();
        assertThat(timeouts).as("both threads hit the timeout path").hasSize(2);

        // The pool survived the double replacement: fast evaluation still works.
        ProcessVariable v = new ProcessVariable();
        v.setName("x");
        v.setType(ProcessVariableType.LONG);
        v.setValue("41");
        Object result = service.evaluateExpression("x + 1", List.of(v));
        assertThat(((Number) result).longValue()).isEqualTo(42L);
    }

    /**
     * WO-REL-24: a timeout must not kill the NEIGHBOUR task.
     *
     * <p>Deterministic by construction (no wall-clock race): thread A hangs on a slow
     * expression and times out; meanwhile the main thread tiles the whole window with
     * back-to-back short evaluations. The iteration live at A's {@code replaceWorker()}
     * instant started before it and finishes after it — on the old code that iteration
     * eats the pool-wide {@code shutdownNow()} interrupt and fails with a confusing
     * {@code EngineException("...failed...: null")} instead of its result (RED); with
     * the fix the old pool drains gracefully and every iteration succeeds (GREEN).
     *
     * <p>A {@code RejectedExecution} ("pool full") mid-swap is retried — it is a
     * transient submit-vs-shutdown race, not a sibling kill, on either code version.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void timeoutDoesNotKillSiblingTask() throws Exception {
        ScriptService service = serviceWithTimeout(1);
        String slowExpr = "for i in 1..5000 return for j in 1..5000 return i * j";
        // Warm up the FEEL engine so iteration durations are stable, not class-loading noise.
        for (int i = 0; i < 3; i++) {
            service.evaluateExpression("for i in 1..3000 return i", List.of());
        }

        AtomicBoolean aTimedOut = new AtomicBoolean(false);
        List<Throwable> aErrors = Collections.synchronizedList(new ArrayList<>());
        Thread stuck = new Thread(() -> {
            try {
                service.evaluateExpression(slowExpr, List.of());
                aErrors.add(new IllegalStateException("slow expression should have timed out"));
            } catch (EngineException e) {
                if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                    aTimedOut.set(true);
                } else {
                    aErrors.add(e);
                }
            } catch (Throwable t) {
                aErrors.add(t);
            }
        });
        stuck.start();

        // Tile A's whole timeout window with short evaluations. The iteration live at
        // A's replaceWorker() straddles it: started before, finishes after.
        List<Throwable> siblingErrors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger siblingSuccesses = new AtomicInteger(0);
        int guard = 0;
        while (!aTimedOut.get() && guard++ < 100_000) {
            try {
                Object r = service.evaluateExpression("for i in 1..3000 return i", List.of());
                assertThat(r).isNotNull();
                siblingSuccesses.incrementAndGet();
            } catch (EngineException e) {
                if (e.getMessage() != null && e.getMessage().contains("pool full")) {
                    continue; // transient submit-vs-shutdown race — retry, not a sibling kill
                }
                siblingErrors.add(e);
            }
        }
        stuck.join(30_000);

        assertThat(aErrors).as("A timed out cleanly: %s", aErrors).isEmpty();
        assertThat(aTimedOut.get()).as("A hit the timeout path").isTrue();
        assertThat(siblingSuccesses.get()).as("sibling iterations covered the window").isPositive();
        assertThat(siblingErrors).as("no sibling task killed by A's timeout: %s", siblingErrors).isEmpty();

        // Pool still serves after everything.
        ProcessVariable v = new ProcessVariable();
        v.setName("x");
        v.setType(ProcessVariableType.LONG);
        v.setValue("41");
        Object result = service.evaluateExpression("x + 1", List.of(v));
        assertThat(((Number) result).longValue()).isEqualTo(42L);
    }
}
