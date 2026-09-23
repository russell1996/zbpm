package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.event.model.VariableModel;

import java.util.List;

public interface ScriptService {

    Object evaluateScript(String script, List<ProcessVariable> variables);

    /** Evaluates a full FEEL expression (any return value), unlike {@link #evaluateScript} which runs a unary test. */
    Object evaluateExpression(String expression, List<ProcessVariable> variables);

    /**
     * WO-ENG-20: выполняет произвольный {@code task} в ОБЩЕМ script-пуле под тем же
     * timeout/bulkhead/метриками, что script task'и (механизм WO-A-02 + WO-REL-46).
     * Единственный способ дать лимиты FEEL-вызовам, которые нельзя выразить через
     * {@code evaluateScript}/{@code evaluateExpression} (DMN unary-тесты с явным input,
     * прямые {@code evaluateExpression} с Map-переменными): сам вызов движка остаётся
     * прежним, меняется только envelope исполнения (поток пула + timeout).
     * Второго пула нет — это и есть «тот же механизм», а не второй.
     *
     * @param task задача (обычно прямой вызов общего {@code FeelEngineApi}-синглтона)
     * @param codeRef безопасная ссылка для логов/ошибок (длина, не текст — A-09)
     * @throws com.zorrodev.bpm.contract.exception.EngineException при timeout,
     *         переполнении пула или прерывании; исключение самой задачи (включая
     *         {@code EngineException}) проходит наружу как есть
     */
    Object runWithBudget(java.util.concurrent.Callable<Object> task, String codeRef);
}
