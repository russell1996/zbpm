package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.configuration.FeelBigDecimalNumberMapper;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.FeelEvaluationException;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.impl.FeelBudgetImpl;
import com.zorrodev.bpm.engine.service.impl.ScriptServiceImpl;
import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.FeelEngineBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * WO-ENG-29: unit-level contract of {@link ElementSupport#evaluateMapping} on the
 * REAL prod path — real {@code FeelEngineApi} (same builder shape as
 * {@code DmnEngineConfig}), real {@code FeelBudgetImpl} + {@code ScriptServiceImpl}
 * (pool/timeout/bulkhead), real {@code ElementSupport#evaluateMapping}.
 *
 * <p>Three cases, no Spring context:
 * <ol>
 *   <li>source references a variable absent from the context → FEEL failure →
 *       {@link FeelEvaluationException} (incident path at callers);</li>
 *   <li>source is an explicit {@code =null} literal → legitimate null → same
 *       {@code ""} STRING variable as before, no exception (regression guard);</li>
 *   <li>source references a variable explicitly holding a null value → FEEL
 *       resolves the present-but-null name to honest null (NOT «not found»),
 *       same {@code ""} STRING variable, no exception.</li>
 * </ol>
 *
 * <p>POF-мутации (каждая валится ровно своим тестом):
 * <ul>
 *   <li>вернуть JSR-223 {@code scriptService.evaluateExpression} в
 *       {@code evaluateMapping} — criterion1 КРАСНЫЙ (legacy wrapper глотает
 *       failure в null → тихая {@code ""} вместо исключения);</li>
 *   <li>убрать проверку {@code suppressedFailures} (только {@code isSuccess}) —
 *       criterion1 КРАСНЫЙ (нативный API отдаёт success=true с null при
 *       NO_VARIABLE_FOUND — проверено пробой feel-engine 1.19.3, см. отчёт);</li>
 *   <li>бросать исключение и на честном null — criterion2/criterion3 КРАСНЫЕ
 *       (смешение «ошибка»/«легитимный null» — тот класс бага, что был у Zeebe).</li>
 * </ul>
 */
class Eng29FeelNullIoMappingUnitTest {

    private ScriptService scriptService;
    private ElementSupport elementSupport;

    @BeforeEach
    void wiring() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        scriptService = new ScriptServiceImpl(
            new org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory().getScriptEngine(),
            new org.camunda.feel.impl.script.FeelScriptEngineFactory().getScriptEngine(),
            new tools.jackson.databind.ObjectMapper(),
            new BpmMetrics(registry), 10, 2, 10, 5);
        // Same builder shape as DmnEngineConfig#feelEngineApi (forJava + BigDecimal mapper).
        FeelEngineApi feelEngineApi =
            FeelEngineBuilder.forJava().withCustomValueMapper(new FeelBigDecimalNumberMapper()).build();
        FeelBudget feelBudget = new FeelBudgetImpl(scriptService, feelEngineApi);
        elementSupport = new ElementSupport(mock(DBService.class), scriptService, feelBudget,
            new tools.jackson.databind.ObjectMapper(), ZoneId.of("Asia/Almaty"), true);
    }

    private static IoMappingExtensionModel.Mapping mapping(String source, String target) {
        IoMappingExtensionModel.Mapping m = new IoMappingExtensionModel.Mapping();
        m.setSource(source);
        m.setTarget(target);
        return m;
    }

    private static ProcessVariable str(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    // ─── Критерий 1: отсутствующая переменная → FeelEvaluationException ───────

    @Test
    void criterion1_missingVariable_throwsFeelEvaluationException() {
        assertThatThrownBy(() -> elementSupport.evaluateMapping(
                mapping("=routeStageUuid", "routeStageUuid"), List.of()))
            .as("source references a variable absent from the job result — must be a "
                + "distinguishable evaluation failure, not a silent null")
            .isInstanceOf(FeelEvaluationException.class)
            .hasMessageContaining("routeStageUuid");
    }

    @Test
    void criterion1_missingVariable_withoutEqualsPrefix_alsoThrows() {
        assertThatThrownBy(() -> elementSupport.evaluateMapping(
                mapping("routeStageUuid", "routeStageUuid"),
                List.of(str("otherVar", "x"))))
            .isInstanceOf(FeelEvaluationException.class)
            .hasMessageContaining("routeStageUuid");
    }

    // ─── Предпосылка бага: legacy JSR-223 действительно глотает failure ──────

    @Test
    void premise_legacyJsr223Wrapper_returnsNullWithoutException() {
        // Документирует корневую причину из WO: тот же движок через JSR-223
        // отдаёт голый null без признака ошибки — поэтому evaluateMapping больше
        // не идёт этим путём (см. criterion1 выше на том же выражении).
        Object legacy = scriptService.evaluateExpression("routeStageUuid", List.of());
        assertThat(legacy)
            .as("legacy JSR-223 path swallows the 'no variable found' failure into null")
            .isNull();
    }

    // ─── Критерий 3: легитимный null — как раньше, без исключения ────────────

    @Test
    void criterion3_explicitNullLiteral_behavesAsBefore() {
        ProcessVariable variable = elementSupport.evaluateMapping(
            mapping("=null", "nullEcho"), List.of());

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue())
            .as("explicit =null keeps the pre-fix '' STRING behaviour (no incident)")
            .isEqualTo("");
    }

    @Test
    void criterion3_presentButNullVariable_isHonestNullNotFailure() {
        // Переменная ЕСТЬ в контексте, но её значение явно null (воркер вернул
        // имя с null-значением) — FEEL резолвит имя в null, это НЕ «not found».
        ProcessVariable nullValued = new ProcessVariable();
        nullValued.setName("routeStageUuid");
        nullValued.setType(ProcessVariableType.STRING);
        nullValued.setValue(null);

        ProcessVariable variable = elementSupport.evaluateMapping(
            mapping("=routeStageUuid", "routeStageUuid"), List.of(nullValued));

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue())
            .as("present-but-null variable is honest null, not an evaluation failure")
            .isEqualTo("");
    }

    @Test
    void criterion3_existingVariable_stillResolves() {
        ProcessVariable variable = elementSupport.evaluateMapping(
            mapping("=routeStageUuid", "routeStageUuid"),
            List.of(str("routeStageUuid", "9f6d1a2b")));

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue()).isEqualTo("9f6d1a2b");
    }

    @Test
    void suppressedFailureWithNonNullValue_proceedsWithValue() {
        // WO-DIFF-1 S-027 precedent: `=noSuchVar` USED TO yield "" via the legacy
        // path and existing fixtures (toProcessVariable on null) rely on it; more
        // importantly a suppressed failure alongside a REAL value (fallback/default
        // expressions that degrade gracefully) must keep flowing — only null-valued
        // failures are incidents. Direct unit proof on the same native result shape.
        ProcessVariable variable = elementSupport.evaluateMapping(
            mapping("=if routeStageUuid = null then \"fallback\" else routeStageUuid", "out"),
            List.of());

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue()).isEqualTo("fallback");
    }
}
