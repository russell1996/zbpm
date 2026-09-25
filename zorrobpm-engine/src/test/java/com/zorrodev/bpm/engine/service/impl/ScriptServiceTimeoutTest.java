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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-SEC-21: Script execution timeout POF.
 * Verifies that long-running scripts are killed by timeout, not hanging forever.
 */
class ScriptServiceTimeoutTest {

    private ScriptService serviceWithTimeout(long seconds) {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        return new ScriptServiceImpl(unary, expression, new tools.jackson.databind.ObjectMapper(), new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), seconds, 2, 10, 5);
    }

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    // --- POF: slow script times out (RED without fix = test hangs, GREEN with fix = EngineException) ---

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void slowExpression_timesOut_throwsEngineException() {
        ScriptService service = serviceWithTimeout(1); // 1 second timeout
        // FEEL: nested nested repeat to create a long-running evaluation
        // 'for i in 1..10000 return for j in 1..10000 return i * j' — quadratic blowup
        String slowExpr = "for i in 1..5000 return for j in 1..5000 return i * j";

        assertThatThrownBy(() -> service.evaluateExpression(slowExpr, List.of()))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("timed out");
    }

    // --- Regression: fast scripts still work ---

    @Test
    void fastExpression_worksWithoutDegradation() {
        ScriptService service = serviceWithTimeout(10);
        List<ProcessVariable> vars = List.of(
            var("x", ProcessVariableType.LONG, "42"),
            var("y", ProcessVariableType.LONG, "8"));

        Object result = service.evaluateExpression("x + y", vars);
        assertThat(((Number) result).longValue()).isEqualTo(50L);
    }

    @Test
    void fastUnaryTest_worksWithoutDegradation() {
        ScriptService service = serviceWithTimeout(10);
        ProcessVariable var = var("status", ProcessVariableType.STRING, "active");

        assertThat((Boolean) service.evaluateScript("status = \"active\"", List.of(var))).isTrue();
    }
}
