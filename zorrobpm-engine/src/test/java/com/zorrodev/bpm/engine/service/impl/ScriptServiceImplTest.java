package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.camunda.feel.impl.script.FeelScriptEngineFactory;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.junit.jupiter.api.Test;

import javax.script.ScriptEngine;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ScriptServiceImplTest {

    private ScriptService service() {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        return new ScriptServiceImpl(unary, expression);
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
}
