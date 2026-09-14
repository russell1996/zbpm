package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the ThreadLocal guards used during process execution:
 * <ul>
 *   <li>{@code executionDepth} — recursion depth limiter (prevents StackOverflow)</li>
 *   <li>{@code callStack} — call-activity chain limiter (WO-REL-29, prevents StackOverflow via A→A/A→B→A)</li>
 *   <li>{@code evaluatingConditionals} — re-entrancy guard for conditional event evaluation</li>
 *   <li>{@code variableChanges} — variable writes since the last conditional trigger pass
 *   (WO-C8-29, consumed by {@code triggerConditionalEvents} for conditionalFilter matching)</li>
 * </ul>
 * Centralised here so that when handlers are extracted to separate beans, they all
 * share the same depth/conditional state instead of silently creating per-bean copies.
 */
@Component
public class ExecutionContext {

    @Value("${zorrobpm.engine.max-execution-depth:1000}")
    private int maxExecutionDepth = 1000;

    @Value("${zorrobpm.engine.max-call-depth:50}")
    private int maxCallDepth = 50;

    private final ThreadLocal<Integer> executionDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Deque<String>> callStack = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Boolean> evaluatingConditionals = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Map<String, String>> variableChanges =
        ThreadLocal.withInitial(LinkedHashMap::new);

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

    /**
     * WO-REL-29: enters a call-activity frame. Detects recursion (A→A or A→B→A) and
     * depth overflow before the child process is started. Throws a plain
     * {@code IllegalStateException} so {@code ActivityServiceImpl.execute()} parks
     * it as an incident (managed error) instead of propagating as engine abort
     * or StackOverflow.
     */
    public void enterCall(String processKey, String elementId, UUID processInstanceId) {
        Deque<String> stack = callStack.get();
        if (stack.contains(processKey)) {
            String chain = String.join(" -> ", stack) + " -> " + processKey;
            throw new IllegalStateException("Call activity recursion detected: chain [" + chain
                + "] at element '" + elementId + "' in process instance " + processInstanceId
                + " — process '" + processKey + "' is already in the call stack");
        }
        if (stack.size() >= maxCallDepth) {
            String chain = String.join(" -> ", stack) + " -> " + processKey;
            throw new IllegalStateException("Call activity max depth (" + maxCallDepth + ") exceeded: chain ["
                + chain + "] at element '" + elementId + "'");
        }
        stack.push(processKey);
    }

    public void exitCall() {
        Deque<String> stack = callStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            callStack.remove();
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
     * WO-C8-29: records one variable write (name to {@code "create"}/{@code "update"} —
     * the only kinds the store reports; deletes leave no trace) for
     * conditionalFilter matching. Overflow (over 1024 pending names) FORGETS everything
     * instead of growing unboundedly — an empty record means "no information", which
     * the matcher treats as unrestricted, so forgetting can only ever cause
     * over-evaluation (safe), never a missed event.
     */
    public void recordVariableChange(String name, String kind) {
        Map<String, String> pending = variableChanges.get();
        if (pending.size() >= 1024) {
            pending.clear();
        }
        pending.put(name, kind);
    }

    /**
     * WO-C8-29: returns and drops the recorded changes (consume-on-read). Every
     * consumed change is matched against filtered subscriptions in the same trigger
     * pass, so consuming cannot lose an evaluation — at worst a change lingers into
     * a later pass (no trigger ran in between) and causes one extra evaluation.
     */
    public Map<String, String> consumeVariableChanges() {
        Map<String, String> copy = new LinkedHashMap<>(variableChanges.get());
        variableChanges.get().clear();
        return copy;
    }
}
