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
import java.util.concurrent.atomic.AtomicLong;

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
    /**
     * WO-REL-46: троттлинг warn'а о насыщении — при полном простое каждый запрос
     * иначе писал бы warn и топил лог ровно в момент инцидента. 60с: насыщение
     * держится минутами (non-cooperative слот не освобождается сам), повтор
     * раньше — шум, не информация.
     */
    private static final long SATURATION_WARN_INTERVAL_MS = 60_000;
    private final AtomicLong lastSaturationWarnMs = new AtomicLong(0);

    public ScriptServiceImpl(@Qualifier("feelScriptEngine") ScriptEngine scriptEngine,
                             @Qualifier("feelExpressionScriptEngine") ScriptEngine feelExpressionScriptEngine,
                             ObjectMapper objectMapper,
                             BpmMetrics bpmMetrics,
                             @Value("${zorrobpm.engine.script-timeout-seconds:10}") long timeoutSeconds,
                             @Value("${zorrobpm.engine.script-pool-size:8}") int poolSize) {
        this.scriptEngine = scriptEngine;
        this.feelExpressionScriptEngine = feelExpressionScriptEngine;
        this.objectMapper = objectMapper;
        this.bpmMetrics = bpmMetrics;
        this.timeoutMs = timeoutSeconds * 1000;
        if (poolSize < 1) {
            throw new IllegalArgumentException(
                "zorrobpm.engine.script-pool-size must be >= 1, got " + poolSize);
        }
        // WO-A-02: bounded bulkhead — bounded pool + bounded queue + abort policy.
        // WO-REL-46: размер конфигурируется (дефолт 8 — порог одновременных
        // зависших non-cooperative скриптов, нужный для полного outage, выше,
        // чем был при хардкоде 2).
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
        bpmMetrics.setScriptPoolSize(poolSize);
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
        String ref = codeRef(code);
        return awaitWithTimeout(submitToPool(() -> engine.eval(code, ctx), ref), ref);
    }

    /**
     * WO-ENG-20: {@link com.zorrodev.bpm.engine.service.ScriptService#runWithBudget} —
     * тот же пул/timeout/bulkhead/метрики, что у script task'ов, для задач, которые
     * нельзя выразить через {@code evaluateScript}/{@code evaluateExpression}.
     */
    public Object runWithBudget(java.util.concurrent.Callable<Object> task, String codeRef) {
        return awaitWithTimeout(submitToPool(task, codeRef), codeRef);
    }

    /**
     * WO-ENG-20: сабмит в общий пул (выделено из {@code evalWithTimeout} без смены
     * семантики — saturation-warn, AbortPolicy-маппинг и сообщения те же).
     */
    private java.util.concurrent.Future<Object> submitToPool(
        java.util.concurrent.Callable<Object> task, String ref) {
        // WO-REL-46: ранний сигнал насыщения — все воркеры заняты, запрос сейчас
        // встанет в очередь. getActiveCount() приблизителен, для warn-уровня
        // достаточно; точная картина — в zbpm.script.pool.active/size.
        if (executor.getActiveCount() >= executor.getMaximumPoolSize()) {
            recordSaturation();
        }

        // WO-A-02: bulkhead — bounded queue rejects if pool is full (AbortPolicy)
        java.util.concurrent.Future<Object> future;
        try {
            future = executor.submit(task);
        } catch (RejectedExecutionException e) {
            // WO-A-02: pool full — fast rejection instead of infinite queuing
            bpmMetrics.scriptRejected();
            bpmMetrics.updateScriptPoolMetrics(executor);
            log.warn("Script rejected (bulkhead full): {} workers active, queue full", executor.getActiveCount());
            throw new EngineException("Script execution rejected: pool full (" + ref + ")");
        }
        return future;
    }

    /**
     * WO-ENG-20: ожидание с timeout (выделено из {@code evalWithTimeout} без смены
     * семантики — сообщения, cancel, метрики те же).
     */
    private Object awaitWithTimeout(java.util.concurrent.Future<Object> future, String ref) {
        try {
            Object result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            // A-09: log length/hash, not full code
            log.debug("Eval result ({})", ref);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            // WO-A-02: stuck-worker — точечный cancel; пул НЕ заменяется (F22:
            // single-pool — замена и была утечкой поколений). Non-cooperative
            // задача пиннит слот, остальное — штатный bulkhead.
            future.cancel(true);
            bpmMetrics.scriptTimeout();
            // A-09: no code in exception, correlation via length/hash
            throw new EngineException("Script execution timed out after " + (timeoutMs / 1000) + "s (" + ref + ")");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof javax.script.ScriptException se) throw new RuntimeException(se);
            throw new EngineException("Script execution failed (" + ref + "): " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("Script execution interrupted");
        } finally {
            // WO-REL-46: gauges свежие после КАЖДОГО eval, а не только после
            // timeout/reject — иначе Grafana-правило насыщения смотрит на
            // протухшие значения ровно тогда, когда они нужны.
            bpmMetrics.updateScriptPoolMetrics(executor);
        }
    }

    /**
     * WO-REL-46: троттлированный warn о насыщении пула (см. поле
     * {@code lastSaturationWarnMs}).
     */
    private void recordSaturation() {
        long now = System.currentTimeMillis();
        long last = lastSaturationWarnMs.get();
        if (now - last >= SATURATION_WARN_INTERVAL_MS && lastSaturationWarnMs.compareAndSet(last, now)) {
            log.warn("Script pool saturated: {}/{} workers active, new evaluations queue "
                    + "(pool size via zorrobpm.engine.script-pool-size)",
                executor.getActiveCount(), executor.getMaximumPoolSize());
            bpmMetrics.updateScriptPoolMetrics(executor);
        }
    }

    /**
     * WO-A-09: code reference for logging — length + hash, NOT full code.
     * WO-DIFF-10: null-safe — evaluateScript(null) падал ВТОРОЙ маскирующей NPE
     * прямо здесь, пряча реальную причину сбоя FEEL-движка.
     */
    private static String codeRef(String code) {
        if (code == null) {
            return "null";
        }
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
