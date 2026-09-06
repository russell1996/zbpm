package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Owns the three ThreadLocal guards used during process execution:
 * <ul>
 *   <li>{@code executionDepth} — recursion depth limiter (prevents StackOverflow)</li>
 *   <li>{@code evaluatingConditionals} — re-entrancy guard for conditional event evaluation</li>
 *   <li>{@code resumingListenerPhase} — resume-after-listeners re-entry marker (WO-C8-25)</li>
 * </ul>
 * Centralised here so that when handlers are extracted to separate beans, they all
 * share the same depth/conditional state instead of silently creating per-bean copies.
 */
@Component
public class ExecutionContext {

    @Value("${zorrobpm.engine.max-execution-depth:1000}")
    private int maxExecutionDepth = 1000;

    private final ThreadLocal<Integer> executionDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Boolean> evaluatingConditionals = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> resumingListenerPhase = ThreadLocal.withInitial(() -> false);

    /**
     * Enters a nested execution frame. Returns the new depth.
     *
     * @throws EngineException if the depth limit is exceeded
     */
    public int enterDepth(String elementId, UUID processInstanceId) {
        int depth = executionDepth.get() + 1;
        if (depth > maxExecutionDepth) {
            throw new EngineException("Execution depth limit (" + maxExecutionDepth + ") exceeded at element '"
                + elementId + "' in process instance " + processInstanceId
                + " — likely an unbounded loop or recursive call activity");
        }
        executionDepth.set(depth);
        return depth;
    }

    /**
     * Exits a nested execution frame. Removes the ThreadLocal at depth 1 (outermost).
     */
    public void exitDepth(int depth) {
        if (depth <= 1) {
            executionDepth.remove();
        } else {
            executionDepth.set(depth - 1);
        }
    }

    /** Re-entrancy guard for conditional event evaluation. */
    public boolean isEvaluatingConditionals() {
        return Boolean.TRUE.equals(evaluatingConditionals.get());
    }

    public void setEvaluatingConditionals(boolean value) {
        evaluatingConditionals.set(value);
    }

    /**
     * WO-C8-25: marks resume-after-listeners re-entry. When the last element listener
     * finishes, the dispatcher re-enters {@code execute()} for the same token+element —
     * without this marker the park-check would re-open a fresh phase (the phase row was
     * just deleted) and loop forever. Set immediately around the resume call only.
     */
    public boolean isResumingListenerPhase() {
        return Boolean.TRUE.equals(resumingListenerPhase.get());
    }

    public void setResumingListenerPhase(boolean value) {
        resumingListenerPhase.set(value);
    }
}
