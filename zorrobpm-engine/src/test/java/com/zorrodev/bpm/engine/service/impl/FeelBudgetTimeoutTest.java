package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.DmnDefinitionRepository;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.impl.script.FeelScriptEngineFactory;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.script.ScriptEngine;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WO-ENG-20 (N09, критерии 2+3): io-mapping ({@code ElementSupport}) и DMN
 * ({@code DmnServiceImpl}) идут через общий бюджет WO-REL-46, а не напрямую
 * в caller-thread без ограничения.
 *
 * <p>Стенд: настоящий {@code ScriptServiceImpl} (pool timeout 1с — тот же механизм,
 * что защищает script task'и) + настоящий {@code FeelBudgetImpl} + настоящие
 * {@code ElementSupport}/{@code DmnServiceImpl}. Висящий FEEL симулируется
 * моком {@code FeelEngineApi}, блокирующимся 3с (детерминировано — настоящий
 * движок по требованию не виснет).
 *
 * <p>POF-мутация: вернуть прямой вызов {@code feelEngineApi} в прод-классе
 * (минуя {@code feelBudget}) — оба timeout-теста идут RED: выражение возвращается
 * нормально через ~3с БЕЗ исключения, т.е. лимит не применён.
 */
@ExtendWith(MockitoExtension.class)
class FeelBudgetTimeoutTest {

    @Mock
    private FeelEngineApi hangingApi;

    @Mock
    private com.zorrodev.bpm.engine.service.DBService dbService;

    @Mock
    private DmnDefinitionRepository dmnRepository;

    @Mock
    private com.zorrodev.bpm.engine.service.AdvisoryDeployLock advisoryDeployLock;

    private ScriptService poolService() {
        ScriptEngine unary = new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
        ScriptEngine expression = new FeelScriptEngineFactory().getScriptEngine();
        return new ScriptServiceImpl(unary, expression,
            new tools.jackson.databind.ObjectMapper(),
            new BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            1, 2, 10, 5);
    }

    private FeelBudget budget() {
        return new FeelBudgetImpl(poolService(), hangingApi);
    }

    private static EvaluationResult success(Object value) {
        EvaluationResult r = org.mockito.Mockito.mock(EvaluationResult.class,
            org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(r.isSuccess()).thenReturn(true);
        when(r.result()).thenReturn(value);
        return r;
    }

    // ─── Критерий 2: io-mapping через ElementSupport ────────────────────

    @Test
    void criterion2_hangingFeelInResolveExpression_isBoundedBySharedPool() {
        // Висящий FEEL: 3с блока, потом успех — без бюджета resolveExpression
        // вернулся бы нормально (лимит не применён); с бюджетом — timeout за ~1с.
        // Мок результата создан ЗАРАНЕЕ: создание мока внутри thenAnswer ломает
        // Mockito (UnfinishedStubbing — вложенный стаббинг).
        EvaluationResult slowOk = success("x");
        when(hangingApi.evaluateExpression(anyString(), anyMap())).thenAnswer(inv -> {
            Thread.sleep(3000);
            return slowOk;
        });
        ElementSupport support = new ElementSupport(
            dbService, poolService(), budget(),
            new tools.jackson.databind.ObjectMapper(), java.time.ZoneId.of("Asia/Almaty"));
        when(dbService.getVariables(any(UUID.class))).thenReturn(List.of());

        assertThatThrownBy(() -> support.resolveExpression("=expensive", UUID.randomUUID()))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("feel-budget")
            .hasMessageContaining("timed out");
    }

    @Test
    void criterion2_fastFeelInResolveExpression_stillResolves() {
        // Контрольный: быстрое выражение резолвится как раньше (бюджет не ломает путь).
        EvaluationResult fastOk = success("hello");
        when(hangingApi.evaluateExpression(anyString(), anyMap())).thenReturn(fastOk);
        ElementSupport support = new ElementSupport(
            dbService, poolService(), budget(),
            new tools.jackson.databind.ObjectMapper(), java.time.ZoneId.of("Asia/Almaty"));
        when(dbService.getVariables(any(UUID.class))).thenReturn(List.of());

        assertThat(support.resolveExpression("=greeting", UUID.randomUUID())).isEqualTo("hello");
    }

    // ─── Критерий 3: DMN через DmnServiceImpl ───────────────────────────

    @Test
    void criterion3_hangingFeelInDmn_isBoundedBySharedPool() throws Exception {
        // Input-выражение висит 3с; unary-стаб — страховка RED-пути (без фикса дело
        // доходит до unary), на GREEN-пути не вызывается → lenient, иначе
        // strict-stubs валит тест за ненужный стаб.
        EvaluationResult slowOk = success(new java.math.BigDecimal(20));
        when(hangingApi.evaluateExpression(anyString(), anyMap())).thenAnswer(inv -> {
            Thread.sleep(3000);
            return slowOk;
        });
        EvaluationResult unaryOk = success(Boolean.TRUE);
        lenient().when(hangingApi.evaluateUnaryTests(anyString(), any(), anyMap())).thenReturn(unaryOk);

        DmnService dmn = dmnServiceWithDiscountTable();

        assertThatThrownBy(() -> dmn.evaluate("discount", List.of()))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("feel-budget")
            .hasMessageContaining("timed out");
    }

    @Test
    void criterion3_fastFeelInDmn_stillEvaluates() throws Exception {
        // Контрольный: быстрая DMN-оценка работает как раньше (gold -> 20).
        EvaluationResult goldOk = success("gold");
        EvaluationResult twentyOk = success(new java.math.BigDecimal(20));
        when(hangingApi.evaluateExpression(anyString(), anyMap()))
            .thenAnswer(inv -> inv.getArgument(0).toString().contains("category") ? goldOk : twentyOk);
        EvaluationResult unaryOk = success(Boolean.TRUE);
        when(hangingApi.evaluateUnaryTests(anyString(), any(), anyMap())).thenReturn(unaryOk);

        DmnService dmn = dmnServiceWithDiscountTable();

        // unary-стаб отвечает true первому правилу — золотая ветка даёт 20.
        Object result = dmn.evaluate("discount", List.of());
        assertThat(((Number) result).intValue()).isEqualTo(20);
    }

    private DmnService dmnServiceWithDiscountTable() throws Exception {
        String xml = Files.readString(Paths.get("src/test/files/test-discount.dmn"));
        DmnDefinitionEntity entity = new DmnDefinitionEntity();
        entity.setId(UUID.randomUUID());
        entity.setDecisionId("discount");
        entity.setDmn(xml);
        when(dmnRepository.findFirstByDecisionIdOrderByVersionDesc("discount"))
            .thenReturn(Optional.of(entity));
        return new DmnServiceImpl(budget(), dmnRepository,
            new tools.jackson.databind.ObjectMapper(), advisoryDeployLock, 500, 60);
    }
}
