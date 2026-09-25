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
import java.util.concurrent.atomic.AtomicLong;

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
        return new ScriptServiceImpl(unary, expression, new tools.jackson.databind.ObjectMapper(), new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), seconds, 2, 10, 5);
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
     * WO-REL-24: a timeout must not kill the NEIGHBOUR task. WO-REL-25: the overlap is
     * guaranteed by CONSTRUCTION — v1 (mosaic of short evals) assumed millisecond
     * iterations and died with zero of them on a slow CI runner; v2/v3 calibrated one
     * long sibling, which is fragile to post-calibration speed drift, and v3's post-hoc
     * margin check turned out to be a coin flip (a live iteration's age at the kill is
     * uniform in [0, duration] — asserting age &gt; MARGIN passes only sometimes).
     *
     * <p>This version removes wall-clock duration from the proof entirely. The sibling
     * is a task that blocks on a latch (arbitrary lifetime — no calibration, no margins,
     * no thresholds):
     * <ol>
     *   <li>Thread A loops the proven slow expression until one eval outlasts its own
     *   1s deadline (service timeout stays T=1s, exactly as in WO-REL-24).</li>
     *   <li>Sibling B is submitted to the same pool FIRST and blocks on a latch; A
     *   submits only after B's start latch fired — so B-started → A-submit → kill is
     *   ordered by the latch itself, with no wall-clock pickup assumption at all
     *   (verifier HOLD #1: without this await, a pathological B-pickup lag past the
     *   kill would pass vacuously GREEN on fixed code).</li>
     *   <li>Main observes A's timeout return (which strictly follows
     *   {@code replaceWorker()} in code) and asserts B is STILL blocked — so B was
     *   running throughout, including at the kill instant.</li>
     *   <li>Main releases the latch; B returns 42.</li>
     * </ol>
     * On the old code the kill is a pool-wide {@code shutdownNow()} that interrupts the
     * latch-blocked sibling — B dies with the confusing {@code EngineException}
     * (RED). With the fix the old pool drains gracefully, B is never interrupted and
     * returns 42 (GREEN). Deviation from the WO letter, documented: B is a
     * latch-task, not a FEEL expression (FEEL context vars cannot block — only
     * Long/Boolean/String/Map pass through — so no FEEL expression can wait for an
     * event). What the test proves is the production mechanism under test
     * (worker survival across {@code replaceWorker()}, driven by a REAL timeout
     * through the REAL {@code evalWithTimeout} path); FEEL end-to-end is covered by
     * the sibling existing test and the final fast-eval below.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void timeoutDoesNotKillSiblingTask() throws Exception {
        ScriptService service = serviceWithTimeout(1);
        String slowExpr = "for i in 1..5000 return for j in 1..5000 return i * j";

        AtomicBoolean aTimedOut = new AtomicBoolean(false);
        List<Throwable> aErrors = Collections.synchronizedList(new ArrayList<>());
        AtomicLong aDoneNanos = new AtomicLong(-1);
        // Sibling B FIRST: occupies the second pool slot and blocks until released.
        // Submitted via the pool directly (test-only reflection into the private field;
        // production submits go through the same ExecutorService.submit path).
        java.util.concurrent.ExecutorService pool = readPool(service);
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch bRelease = new CountDownLatch(1);
        java.util.concurrent.Future<Integer> bFuture = pool.submit(() -> {
            bStarted.countDown();
            bRelease.await();
            return 42;
        });
        Thread stuck = new Thread(() -> {
            try {
                // Latch ordering (verifier HOLD #1 fix): A submits ONLY after B proved
                // it occupies a worker — so B-started → A-submit → kill is ordered by
                // construction, not by pickup-timing luck. Without this, a pathological
                // B-pickup lag past the kill would pass vacuously GREEN on fixed code.
                if (!bStarted.await(30, TimeUnit.SECONDS)) {
                    aErrors.add(new IllegalStateException("sibling never occupied a worker"));
                    return;
                }
                // Loop until an eval outlasts its own 1s deadline (each eval is
                // deadline-capped, so this always terminates; slowExpr outlasts 1s
                // on every observed box — dev, CI-slow, docker).
                for (int k = 0; k < 6 && !aTimedOut.get(); k++) {
                    try {
                        service.evaluateExpression(slowExpr, List.of());
                    } catch (EngineException e) {
                        if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                            aTimedOut.set(true);
                        } else {
                            aErrors.add(e);
                        }
                    }
                }
                if (!aTimedOut.get()) {
                    aErrors.add(new IllegalStateException("A never timed out"));
                }
            } catch (Throwable t) {
                aErrors.add(t);
            } finally {
                aDoneNanos.set(System.nanoTime());
            }
        });

        stuck.start();

        stuck.join(60_000);
        try {
            assertThat(aErrors).as("A timed out cleanly: %s", aErrors).isEmpty();
            assertThat(aTimedOut.get()).as("A hit the timeout path").isTrue();
            assertThat(aDoneNanos.get()).as("A finished").isPositive();
            // B was running before the kill (start latch) and is STILL running after
            // A's timeout returned (which strictly follows replaceWorker in code) —
            // so B straddled the kill. On the old code B is already dead here (RED).
            assertThat(bFuture.isDone()).as("sibling still blocked across A's timeout").isFalse();
        } finally {
            bRelease.countDown();
        }

        assertThat(bFuture.get(30, TimeUnit.SECONDS)).as("sibling returns its result").isEqualTo(42);

        // Pool still serves after everything.
        ProcessVariable v = new ProcessVariable();
        v.setName("x");
        v.setType(ProcessVariableType.LONG);
        v.setValue("41");
        Object result = service.evaluateExpression("x + 1", List.of(v));
        assertThat(((Number) result).longValue()).isEqualTo(42L);
    }

    /** Test-only access to the pool workers under test (same package, private field). */
    private static java.util.concurrent.ExecutorService readPool(ScriptService service) throws Exception {
        java.lang.reflect.Field f = ScriptServiceImpl.class.getDeclaredField("executor");
        f.setAccessible(true);
        return (java.util.concurrent.ExecutorService) f.get(service);
    }
}
