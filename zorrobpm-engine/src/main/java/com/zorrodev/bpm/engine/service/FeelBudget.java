package com.zorrodev.bpm.engine.service;

import java.util.Map;

import org.camunda.feel.api.EvaluationResult;

/**
 * WO-ENG-20 (N09): единая точка входа для вычисления FEEL-выражений с лимитами.
 *
 * <p>До этого WO защита timeout/bulkhead жила только в {@code ScriptServiceImpl}
 * (пул WO-A-02 + насыщение WO-REL-46), а {@code ElementSupport} и {@code DmnServiceImpl}
 * вызывали {@code FeelEngineApi} НАПРЯМУЮ — дорогое выражение в разрешённой модели или DMN
 * занимало caller/transaction thread вообще без timeout. Этот интерфейс закрывает
 * обход: все прямые вызовы идут через него, а реализация исполняет ТОТ ЖЕ вызов движка
 * внутри ОБЩЕГО пула {@code ScriptServiceImpl} (см. {@link ScriptService#runWithBudget}) —
 * второй bulkhead не заводится.
 *
 * <p>Контракт: неуспех САМОГО выражения возвращается как данные
 * ({@code FailedEvaluationResult}, {@code isSuccess() == false}) — вызывающие сохраняют
 * свою обработку (warn+null в {@code ElementSupport}, throw в {@code DmnServiceImpl}).
 * {@code EngineException} — ТОЛЬКО события бюджета (timeout/переполнение пула),
 * с префиксом {@code "(feel-budget: ...)"}, чтобы отличать от неуспеха выражения.
 */
public interface FeelBudget {

    /**
     * Вычисляет FEEL-выражение под общим бюджетом (timeout + bulkhead WO-REL-46).
     */
    EvaluationResult evaluateExpression(String expression, Map<String, Object> variables);

    /**
     * Вычисляет FEEL unary-test против явного input под общим бюджетом (DMN input entries).
     */
    EvaluationResult evaluateUnaryTests(String test, Object input, Map<String, Object> variables);
}
