package com.zorrodev.bpm.engine.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.camunda.feel.impl.script.FeelScriptEngineFactory;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.script.ScriptEngine;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ScriptServiceImplTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        logAppender.start();
        logger = (Logger) LoggerFactory.getLogger(ScriptServiceImpl.class);
        logger.addAppender(logAppender);
        logger.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    private ScriptService service() {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        return new ScriptServiceImpl(unary, expression, new tools.jackson.databind.ObjectMapper(), new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), 10, 2, 10, 5);
    }

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    @Test
    void testUnaryTestEvaluation() {
        ScriptService service = service();
        ProcessVariable var = var("x", ProcessVariableType.LONG, "1");

        assertThat((Boolean) service.evaluateScript("x = 1", List.of(var))).isTrue();
        assertThat((Boolean) service.evaluateScript("x != 1", List.of(var))).isFalse();
    }

    @Test
    void testExpressionEvaluationReturnsValue() {
        ScriptService service = service();
        List<ProcessVariable> vars = List.of(
            var("a", ProcessVariableType.LONG, "5"),
            var("b", ProcessVariableType.LONG, "3"));

        // a full FEEL expression returns an arbitrary value, not just a boolean unary test
        Object sum = service.evaluateExpression("a + b", vars);
        assertThat(((Number) sum).longValue()).isEqualTo(8L);

        Object greater = service.evaluateExpression("a > b", vars);
        assertThat(greater).isEqualTo(Boolean.TRUE);
    }

    // --- WO-SEC-16: criterion #2 — DEBUG log must not contain variable values ---

    @Test
    void criterion2_debugLogDoesNotContainVariableValues() {
        ScriptService service = service();
        ProcessVariable secret = var("apiKey", ProcessVariableType.STRING, "sk-live-supersecret123");
        ProcessVariable token = var("bearer", ProcessVariableType.STRING, "Bearer abcxyz");

        service.evaluateExpression("apiKey", List.of(secret, token));

        List<ILoggingEvent> events = logAppender.list;
        assertThat(events).isNotEmpty();

        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage())
                .as("Log must not contain secret value 'sk-live-supersecret123'")
                .doesNotContain("sk-live-supersecret123");
            assertThat(event.getFormattedMessage())
                .as("Log must not contain token value 'Bearer abcxyz'")
                .doesNotContain("Bearer abcxyz");
        }
    }
}
