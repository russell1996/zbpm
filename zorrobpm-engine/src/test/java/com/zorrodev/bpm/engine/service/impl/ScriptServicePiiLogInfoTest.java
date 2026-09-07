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
 * WO-SEC-7 criterion #1: PII values are NOT leaked into INFO logs.
 * Logger at default level (INFO) — debug messages are suppressed.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScriptServicePiiLogInfoTest {

    @BeforeAll
    static void resetToInfo() {
        Logger logger = (Logger) LoggerFactory.getLogger(ScriptServiceImpl.class);
        logger.setLevel(Level.INFO);
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

    // --- Criterion #1: value NOT in INFO logs ---

    @Test
    void criterion1_secretValue_notInInfoLogs(CapturedOutput output) {
        String secretValue = "passport-12345678";
        ProcessVariable pii = var("document", ProcessVariableType.STRING, secretValue);

        service().evaluateExpression("document", List.of(pii));

        // The value must NOT appear in the log output (only DEBUG would show it)
        assertThat(output.getOut()).doesNotContain(secretValue);
    }
}
