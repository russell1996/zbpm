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
 * WO-ENG-30 (flag OBSOLETE since WO-C8-54) — the compat flag
 * {@code zorrobpm.engine.io-mapping.strict-missing} and the two null profiles of
 * the io-mapping evaluation, on the REAL prod path (real {@code FeelEngineApi},
 * real {@code FeelBudgetImpl}, real {@code ElementSupport#evaluateMapping}).
 *
 * <p><b>WO-C8-54 (2026-10-19): both profiles now behave IDENTICALLY and follow
 * live Zeebe 8.5.</b> The flag existed to roll out WO-ENG-29's incident behaviour
 * safely — and that behaviour rested on an UNMEASURED assumption about Zeebe
 * ("a missing variable in an io-mapping raises an incident"). The live 8.5
 * measurement of the F1 campaign disproves it: Zeebe evaluates such a source to
 * a NULL VALUE, creates the job with a JSON null and continues — no incident
 * ({@code docs/analysis/c8-oracle/feel-iomapping.js}, case {@code missing-bare}:
 * {@code completed  work={"r":null}}).</p>
 *
 * <p>So the flag no longer changes anything. This class pins THAT:</p>
 * <ul>
 *   <li>the missing variable → a null VALUE (JSON null) in BOTH profiles, with a
 *       WARN carrying source/target (the log-based inventory the lenient mode
 *       always had);</li>
 *   <li>the hard FEEL error (syntax) → an exception in BOTH profiles — the flag
 *       never covered it and never will.</li>
 * </ul>
 *
 * <p>POF-мутации (каждая валится ровно своим тестом):</p>
 * <ul>
 *   <li>вернуть инцидент на отсутствующую переменную (прежнее поведение
 *       strict) — {@code strict_missingVariable_isANullValueToo} КРАСНЫЙ;</li>
 *   <li>вернуть тихую пустую строку (прежнее поведение lenient) —
 *       {@code lenient_missingVariable_isANullValueToo} КРАСНЫЙ;</li>
 *   <li>снять разделение hard-failure (единое {@code failed} без разделения) —
 *       {@code hardError_syntaxError_throwsInBothModes} КРАСНЫЙ ровно в
 *       lenient-моде.</li>
 * </ul>
 */
class Eng30StrictMissingCompatFlagUnitTest {

    private ScriptService scriptService;
    private FeelBudget feelBudget;
    private ElementSupport lenient;
    private ElementSupport strict;

    @BeforeEach
    void wiring() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        scriptService = new ScriptServiceImpl(
            new org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory().getScriptEngine(),
            new org.camunda.feel.impl.script.FeelScriptEngineFactory().getScriptEngine(),
            new tools.jackson.databind.ObjectMapper(),
            new BpmMetrics(registry), 10, 2, 10, 5);
        FeelEngineApi feelEngineApi =
            FeelEngineBuilder.forJava().withCustomValueMapper(new FeelBigDecimalNumberMapper()).build();
        feelBudget = new FeelBudgetImpl(scriptService, feelEngineApi);
        lenient = new ElementSupport(mock(DBService.class), scriptService, feelBudget,
            new tools.jackson.databind.ObjectMapper(), ZoneId.of("Asia/Almaty"), false);
        strict = new ElementSupport(mock(DBService.class), scriptService, feelBudget,
            new tools.jackson.databind.ObjectMapper(), ZoneId.of("Asia/Almaty"), true);
    }

    private static IoMappingExtensionModel.Mapping mapping(String source, String target) {
        IoMappingExtensionModel.Mapping m = new IoMappingExtensionModel.Mapping();
        m.setSource(source);
        m.setTarget(target);
        return m;
    }

    /** WO-C8-54: a null VALUE is a JSON null (the representation live 8.5 delivers). */
    private static void assertJsonNull(ProcessVariable variable, String what) {
        assertThat(variable.getType()).as("type of %s", what).isEqualTo(ProcessVariableType.JSON);
        assertThat(variable.getValue()).as("value of %s", what).isEqualTo("null");
    }

    // ─── Критерий 1: lenient — null VALUE + WARN, без исключения ──────────────

    @Test
    void lenient_missingVariable_isANullValueToo() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ElementSupport.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProcessVariable variable = lenient.evaluateMapping(
                mapping("=routeStageUuid", "routeStageUuid"), List.of());

            assertJsonNull(variable, "a missing variable in the lenient profile");
            assertThat(appender.list)
                .as("the null path must log a WARN carrying source/target for log-based inventory")
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("routeStageUuid");
                });
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ─── Критерий 2: strict — то же самое (флагobsolete) ─────────────────────

    @Test
    void strict_missingVariable_isANullValueToo() {
        ProcessVariable variable = strict.evaluateMapping(
            mapping("=routeStageUuid", "routeStageUuid"), List.of());

        assertJsonNull(variable, "a missing variable in the strict profile");
    }

    // ─── Критерий 3: жёсткая ошибка — инцидент ВСЕГДА, независимо от флага ──

    @Test
    void hardError_syntaxError_throwsInBothModes() {
        assertThatThrownBy(() -> lenient.evaluateMapping(
                mapping("=1 +", "broken"), List.of()))
            .as("syntax error is a genuine computation error, never an absent variable — incident even in lenient mode")
            .isInstanceOf(FeelEvaluationException.class);
        assertThatThrownBy(() -> strict.evaluateMapping(
                mapping("=1 +", "broken"), List.of()))
            .as("syntax error throws in strict mode too")
            .isInstanceOf(FeelEvaluationException.class);
    }

    // ─── Регресс: честный null — значение в ОБОИХ режимах ────────────────────

    @Test
    void honestNull_explicitLiteral_isANullValueInBothModes() {
        for (ElementSupport support : List.of(lenient, strict)) {
            ProcessVariable variable = support.evaluateMapping(
                mapping("=null", "nullEcho"), List.of());
            assertJsonNull(variable, "an explicit =null literal");
        }
    }
}
