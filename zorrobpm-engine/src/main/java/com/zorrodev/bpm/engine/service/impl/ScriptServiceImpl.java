package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ScriptServiceImpl implements ScriptService {

    private final ScriptEngine scriptEngine;
    private final ScriptEngine feelExpressionScriptEngine;
    private final ObjectMapper objectMapper;
    private final BpmMetrics bpmMetrics;
    private final long timeoutMs;
    /**
     * WO-REL-33 F22: пул ОДИН на все времена (final — никогда не заменяется).
     * Старой болезни «каждый timeout = новый executor + выжившие поколения»
     * больше нет: поколений нет вообще, суммарные потоки ≤ poolSize константно.
     * Non-cooperative задача пинит свой слот (неизбежно при любом bounded-дизайне),
     * дальше работает штатный bulkhead WO-A-02: очередь → AbortPolicy → быстрый
     * EngineException вместо утечки. Сосед не прерывается ничем (заменять нечего) —
     * инвариант WO-REL-24 сохранён доказанно его же тестом.
     */
    private final ThreadPoolExecutor executor;

    public ScriptServiceImpl(@Qualifier("feelScriptEngine") ScriptEngine scriptEngine,
                             @Qualifier("feelExpressionScriptEngine") ScriptEngine feelExpressionScriptEngine,
                             ObjectMapper objectMapper,
                             BpmMetrics bpmMetrics,
                             @Value("${zorrobpm.engine.script-timeout-seconds:10}") long timeoutSeconds) {
        this.scriptEngine = scriptEngine;
        this.feelExpressionScriptEngine = feelExpressionScriptEngine;
        this.objectMapper = objectMapper;
        this.bpmMetrics = bpmMetrics;
        this.timeoutMs = timeoutSeconds * 1000;
        // WO-A-02: bounded bulkhead — bounded pool + bounded queue + abort policy
        int poolSize = 2; // default: 2 concurrent script evaluations
        int queueCapacity = 10; // small buffer for queued expressions
        this.executor = new ThreadPoolExecutor(
            poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            r -> {
                Thread t = new Thread(r, "script-eval");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
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

        // WO-A-02: bulkhead — bounded queue rejects if pool is full (AbortPolicy)
        java.util.concurrent.Future<Object> future;
        try {
            future = executor.submit(() -> engine.eval(code, ctx));
        } catch (RejectedExecutionException e) {
            // WO-A-02: pool full — fast rejection instead of infinite queuing
            String codeRef = codeRef(code);
            bpmMetrics.scriptRejected();
            bpmMetrics.updateScriptPoolMetrics(executor);
            log.warn("Script rejected (bulkhead full): {} workers active, queue full", executor.getActiveCount());
            throw new EngineException("Script execution rejected: pool full (" + codeRef + ")");
        }

        try {
            Object result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            // A-09: log length/hash, not full code
            log.debug("Eval result (len={}, hash={})", code.length(), code.hashCode());
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            // WO-A-02: stuck-worker — точечный cancel; пул НЕ заменяется (F22:
            // single-pool — замена и была утечкой поколений). Non-cooperative
            // задача пиннит слот, остальное — штатный bulkhead.
            future.cancel(true);
            bpmMetrics.scriptTimeout();
            bpmMetrics.updateScriptPoolMetrics(executor);
            // A-09: no code in exception, correlation via length/hash
            throw new EngineException("Script execution timed out after " + (timeoutMs / 1000) + "s (" + codeRef(code) + ")");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            String codeRef = codeRef(code);
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof javax.script.ScriptException se) throw new RuntimeException(se);
            throw new EngineException("Script execution failed (" + codeRef + "): " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("Script execution interrupted");
        }
    }

    /**
     * WO-A-09: code reference for logging — length + hash, NOT full code.
     */
    private static String codeRef(String code) {
        return "len=" + code.length() + ",hash=" + code.hashCode();
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
