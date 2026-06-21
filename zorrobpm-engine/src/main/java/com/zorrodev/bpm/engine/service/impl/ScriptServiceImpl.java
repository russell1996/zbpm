package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.SimpleScriptContext;
import java.util.List;

@Slf4j
@Service
public class ScriptServiceImpl implements ScriptService {

    private final ScriptEngine scriptEngine;
    private final ScriptEngine feelExpressionScriptEngine;
    private final ObjectMapper objectMapper;

    public ScriptServiceImpl(@Qualifier("feelScriptEngine") ScriptEngine scriptEngine,
                             @Qualifier("feelExpressionScriptEngine") ScriptEngine feelExpressionScriptEngine,
                             ObjectMapper objectMapper) {
        this.scriptEngine = scriptEngine;
        this.feelExpressionScriptEngine = feelExpressionScriptEngine;
        this.objectMapper = objectMapper;
    }

    @SneakyThrows
    public Object evaluateScript(String script, List<ProcessVariable> variables) {
        Object result = scriptEngine.eval(script, buildContext(variables));
        log.info("Expression result {} = {}", script, result);
        return result;
    }

    @SneakyThrows
    public Object evaluateExpression(String expression, List<ProcessVariable> variables) {
        Object result = feelExpressionScriptEngine.eval(expression, buildContext(variables));
        log.info("Script result {} = {}", expression, result);
        return result;
    }

    private ScriptContext buildContext(List<ProcessVariable> variables) {
        ScriptContext ctx = new SimpleScriptContext();

        if (variables != null && !variables.isEmpty()) {
            for (ProcessVariable variable : variables) {
                ProcessVariableType type = variable.getType();
                if (type == ProcessVariableType.LONG) {
                    ctx.setAttribute(variable.getName(), Long.valueOf(variable.getValue()), ScriptContext.ENGINE_SCOPE);
                } else if (type == ProcessVariableType.BOOLEAN) {
                    ctx.setAttribute(variable.getName(), Boolean.valueOf(variable.getValue()), ScriptContext.ENGINE_SCOPE);
                } else if (type == ProcessVariableType.DOUBLE) {
                    // FEEL numbers are BigDecimal — pass decimals as such so arithmetic/comparison works
                    ctx.setAttribute(variable.getName(), new java.math.BigDecimal(variable.getValue()), ScriptContext.ENGINE_SCOPE);
                } else if (type == ProcessVariableType.JSON) {
                    // JSON object/list -> Java Map/List so FEEL can read nested properties (order.total) and iterate
                    ctx.setAttribute(variable.getName(), objectMapper.readValue(variable.getValue(), Object.class), ScriptContext.ENGINE_SCOPE);
                } else {
                    ctx.setAttribute(variable.getName(), variable.getValue(), ScriptContext.ENGINE_SCOPE);
                }
                log.info("Variable {} = {}", variable.getName(), variable.getValue());
            }
        }
        return ctx;
    }

}
