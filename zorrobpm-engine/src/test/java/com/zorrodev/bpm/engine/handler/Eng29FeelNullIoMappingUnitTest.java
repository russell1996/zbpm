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
 * WO-ENG-29 (superseded by WO-C8-54) — unit-level contract of
 * {@link ElementSupport#evaluateMapping} on the REAL prod path — real
 * {@code FeelEngineApi} (same builder shape as {@code DmnEngineConfig}), real
 * {@code FeelBudgetImpl} + {@code ScriptServiceImpl} (pool/timeout/bulkhead), real
 * {@code ElementSupport#evaluateMapping}.
 *
 * <p><b>WO-C8-54 (2026-10-19) supersedes the incident part of this contract.</b>
 * WO-ENG-29 assumed that Zeebe raises an incident when an io-mapping source
 * references an absent variable. The live Zeebe 8.5.0 measurement of the F1
 * campaign disproves that assumption: Zeebe evaluates such a source to a NULL
 * VALUE — the job is created, the mapped variable is a JSON null, the process
 * continues, NO incident ({@code docs/analysis/c8-oracle/feel-iomapping.js},
 * case {@code missing-bare}: {@code completed  work={"r":null}}). The strict /
 * lenient duality of WO-ENG-30 existed only to roll that assumption out safely;
 * with the assumption gone, the duality is gone too (both profiles follow live
 * 8.5) and the flag is obsolete.</p>
 *
 * <p>What this class pins NOW (WO-C8-54):</p>
 * <ol>
 * <li>a source referencing a variable absent from the context → a null VALUE
 *       (JSON null), no exception, no incident (live 8.5);</li>
 *   <li>a source without the {@code =} prefix → a STATIC VALUE (the source text
 *       verbatim, live 8.5) — WO-C8-55 answered the question this class left open
 *       («the static-source question itself is WO-C8-55»);</li>
 *   <li>an explicit {@code =null} literal → a null VALUE (JSON null);</li>
 *   <li>a variable that EXISTS with a null value → an honest null (JSON null),
 *       not an evaluation failure;</li>
 *   <li>a HARD failure (syntax error) → still an exception (the incident path) —
 *       the Zeebe fix class, rejected at deploy by WO-C8-56.</li>
 * </ol>
 *
 * <p>POF-мутации (каждая валится ровно своим тестом):</p>
 * <ul>
 *   <li>вернуть JSR-223 {@code scriptService.evaluateExpression} в
 *       {@code evaluateMapping} — criterion1 КРАСНЫЙ (legacy wrapper глотает
 *       failure в null → тихая {@code ""} вместо JSON null);</li>
 *   <li>убрать проверку {@code suppressedFailures} (только {@code isSuccess}) —
 *       criterion1 КРАСНЫЙ (нативный API отдаёт success=true с null при
 *       NO_VARIABLE_FOUND — проверено пробой feel-engine 1.19.3, см. отчёт);</li>
 *   <li>бросать исключение и на честном null — criterion1/criterion3 КРАСНЫЕ
 *       (смешение «ошибка»/«легитимный null» — тот же класс, что был у Zeebe,
 *       только наоборот: Zeebe не инцидентит, а мы инцидентили).</li>
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

    /** WO-C8-54: a null VALUE is a JSON null — the representation live 8.5 delivers. */
    private static void assertJsonNull(ProcessVariable variable, String what) {
        assertThat(variable.getType()).as("type of %s", what).isEqualTo(ProcessVariableType.JSON);
        assertThat(variable.getValue()).as("value of %s", what).isEqualTo("null");
    }

    // ─── Критерий 1: отсутствующая переменная → null VALUE (live 8.5) ─────────

    @Test
    void criterion1_missingVariable_isANullValueNotAFailure() {
        ProcessVariable variable = elementSupport.evaluateMapping(
            mapping("=routeStageUuid", "routeStageUuid"), List.of());

        assertJsonNull(variable, "a missing variable");
    }

    /**
     * WO-C8-55: the question this class left open in WO-C8-54 — answered by the live
     * Zeebe 8.5.0 measurement ({@code docs/analysis/c8-oracle/feel-iomapping-static-nested.js},
     * case {@code src-noeq-var}: a source without {@code =} is a STATIC VALUE, the source text
     * delivered verbatim, even when a variable of that name exists). Before this WO the engine
     * evaluated such a source as FEEL, so a missing variable of that spelling produced a null
     * VALUE; the live-8.5 behaviour is the literal text.
     */
    @Test
    void criterion1_missingVariable_withoutEqualsPrefix_isAStaticValueNotANull() {
        ProcessVariable variable = elementSupport.evaluateMapping(
            mapping("routeStageUuid", "routeStageUuid"),
            List.of(str("otherVar", "x")));

        assertThat(variable.getType()).as("type of a static value (no '=' prefix)")
            .isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue()).as("value of a static value (no '=' prefix)")
            .isEqualTo("routeStageUuid");
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

    // ─── Критерий 3: легитимный null — значение, а не ошибка ──────────────────

    @Test
    void criterion3_explicitNullLiteral_isANullValue() {
        ProcessVariable variable = elementSupport.evaluateMapping(
            mapping("=null", "nullEcho"), List.of());

        assertJsonNull(variable, "an explicit =null literal");
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

        assertJsonNull(variable, "a present-but-null variable");
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
        // WO-DIFF-1 S-027 precedent: a suppressed failure alongside a REAL value
        // (fallback expressions that degrade gracefully) must keep flowing — only
        // null-valued failures become a null VALUE. Direct unit proof on the same
        // native result shape.
        ProcessVariable variable = elementSupport.evaluateMapping(
            mapping("=if routeStageUuid = null then \"fallback\" else routeStageUuid", "out"),
            List.of());

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue()).isEqualTo("fallback");
    }

    // ─── Жёсткая ошибка — по-прежнему исключение (инцидент) ──────────────────

    @Test
    void hardFailure_syntaxError_stillThrows() {
        assertThatThrownBy(() -> elementSupport.evaluateMapping(
                mapping("=1 +", "broken"), List.of()))
            .as("a syntax error is a genuine computation error (the Zeebe fix class) — "
                + "never an absent variable; live 8.5 rejects it at deploy (WO-C8-56)")
            .isInstanceOf(FeelEvaluationException.class);
    }
}
