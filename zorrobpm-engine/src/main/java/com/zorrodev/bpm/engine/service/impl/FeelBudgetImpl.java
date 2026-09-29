package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.api.FeelEngineApi;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * WO-ENG-20 (N09): {@link FeelBudget} поверх механизма WO-REL-46, без второго механизма.
 *
 * <p>Оба метода исполняют ТОТ ЖЕ вызов общего {@code FeelEngineApi}-синглтона с ТЕМИ ЖЕ
 * аргументами, что раньше исполнялись напрямую в {@code ElementSupport}/{@code DmnServiceImpl},
 * но внутри задачи общего пула {@code ScriptServiceImpl} (см.
 * {@link ScriptService#runWithBudget}): timeout, очередь, AbortPolicy, метрики и warn'ы —
 * побайтово те же, второй executor не создаётся.
 *
 * <p>Starvation-оговорка — та же, что у WO-REL-46 для script task'ов: с этого WO общий пул
 * делят script task'и И io-mapping/DMN-резолвы. Один зависший non-cooperative FEEL пинит
 * слот независимо от того, откуда пришёл; полный outage по-прежнему требует
 * {@code script-pool-size} одновременных зависших. Отдельный пул не заводился осознанно:
 * два bulkhead'а — это два механизма вместо одного, что запрещает сам WO-ENG-20 п.2.
 *
 * <p>Замена пулов/потоков НЕ восстанавливалась (WO-ENG-20 п.3, урок F22 из WO-REL-46):
 * этот класс не создаёт ни одного executor'а, только сабмитит задачи в существующий.
 */
@Slf4j
@Service
public class FeelBudgetImpl implements FeelBudget {

    private final ScriptService scriptService;
    private final FeelEngineApi feelEngineApi;

    public FeelBudgetImpl(ScriptService scriptService, FeelEngineApi feelEngineApi) {
        this.scriptService = scriptService;
        this.feelEngineApi = feelEngineApi;
    }

    @Override
    public EvaluationResult evaluateExpression(String expression, Map<String, Object> variables) {
        try {
            return (EvaluationResult) scriptService.runWithBudget(
                () -> feelEngineApi.evaluateExpression(expression, variables),
                "feel-expression(len=" + (expression == null ? "null" : expression.length()) + ")");
        } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException e) {
            // WO-ENG-24: временная перегрузка — не ошибка выражения. Проброс
            // без "(feel-budget: …)"-обёртки: 503-хендлер матчит по ТИПУ,
            // обёртка спрятала бы его обратно в 422.
            throw e;
        } catch (EngineException e) {
            throw new EngineException("(feel-budget: " + e.getMessage() + ")", e);
        }
    }

    @Override
    public EvaluationResult evaluateUnaryTests(String test, Object input, Map<String, Object> variables) {
        try {
            return (EvaluationResult) scriptService.runWithBudget(
                () -> feelEngineApi.evaluateUnaryTests(test, input, variables),
                "feel-unary-test(len=" + (test == null ? "null" : test.length()) + ")");
        } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException e) {
            // WO-ENG-24: см. выше — перегрузка идёт дальше без обёртки.
            throw e;
        } catch (EngineException e) {
            throw new EngineException("(feel-budget: " + e.getMessage() + ")", e);
        }
    }
}
