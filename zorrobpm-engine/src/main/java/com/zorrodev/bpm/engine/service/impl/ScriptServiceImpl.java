package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.SimpleScriptContext;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ScriptServiceImpl implements ScriptService {

    private final ScriptEngine scriptEngine;
    private final ScriptEngine feelExpressionScriptEngine;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;
    private final ExecutorService executor;

    public ScriptServiceImpl(@Qualifier("feelScriptEngine") ScriptEngine scriptEngine,
                             @Qualifier("feelExpressionScriptEngine") ScriptEngine feelExpressionScriptEngine,
                             ObjectMapper objectMapper,
                             @Value("${zorrobpm.engine.script-timeout-seconds:10}") long timeoutSeconds) {
        this.scriptEngine = scriptEngine;
        this.feelExpressionScriptEngine = feelExpressionScriptEngine;
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutSeconds * 1000;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "script-eval");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Object evaluateScript(String script, List<ProcessVariable> variables) {
        return evalWithTimeout(scriptEngine, script, variables);
    }

    @Override
    public Object evaluateExpression(String expression, List<ProcessVariable> variables) {
        return evalWithTimeout(feelExpressionScriptEngine, expression, variables);
    }

    private Object evalWithTimeout(ScriptEngine engine, String code, List<ProcessVariable> variables) {
        ScriptContext ctx = buildContext(variables);
        Future<Object> future = executor.submit(() -> engine.eval(code, ctx));
        try {
            Object result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            log.debug("Eval result {}", code);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new EngineException("Script execution timed out after " + (timeoutMs / 1000) + "s: " + code);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof javax.script.ScriptException se) throw new RuntimeException(se);
            throw new EngineException("Script execution failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("Script execution interrupted", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
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
                log.debug("Variable {}", variable.getName());
            }
        }
        return ctx;
    }

}
