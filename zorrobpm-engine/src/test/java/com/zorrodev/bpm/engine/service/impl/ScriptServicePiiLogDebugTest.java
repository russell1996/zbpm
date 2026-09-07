package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.camunda.feel.impl.script.FeelScriptEngineFactory;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import javax.script.ScriptEngine;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-16: PII values are NOT logged even at DEBUG level.
 * Variable values removed from log output to prevent secret leakage.
 * Logger set to DEBUG programmatically — still no values.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScriptServicePiiLogDebugTest {

    @BeforeAll
    static void enableDebug() {
        Logger logger = (Logger) LoggerFactory.getLogger(ScriptServiceImpl.class);
        logger.setLevel(Level.DEBUG);
    }

    private ScriptService service() {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        return new ScriptServiceImpl(unary, expression, new tools.jackson.databind.ObjectMapper(), new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), 10);
    }

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    // --- Criterion #2: value NOT visible even on DEBUG (WO-SEC-16) ---

    @Test
    void criterion2_secretValue_notVisibleOnDebug(CapturedOutput output) {
        String secretValue = "secret-salary-99999";
        ProcessVariable pii = var("salary", ProcessVariableType.STRING, secretValue);

        service().evaluateExpression("salary", List.of(pii));

        // WO-SEC-16: values must NOT appear in logs, even at DEBUG
        assertThat(output.getOut()).doesNotContain(secretValue);
    }

    // --- Criterion #3: FEEL calculations still work ---

    @Test
    void criterion3_feelCalculations_continueWorking() {
        ProcessVariable a = var("a", ProcessVariableType.LONG, "10");
        ProcessVariable b = var("b", ProcessVariableType.LONG, "5");

        Object result = service().evaluateExpression("a + b", List.of(a, b));
        assertThat(((Number) result).longValue()).isEqualTo(15L);
    }
}
