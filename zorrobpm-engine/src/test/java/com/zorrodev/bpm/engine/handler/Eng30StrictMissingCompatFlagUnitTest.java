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
 * WO-ENG-30: compat flag {@code zorrobpm.engine.io-mapping.strict-missing} for the
 * WO-ENG-29 strict behaviour — both branches on the REAL prod path (real
 * {@code FeelEngineApi}, real {@code FeelBudgetImpl}, real
 * {@code ElementSupport#evaluateMapping}; reuses the Eng29 fixtures' shapes, no
 * new BPMN needed at this level — the IT below reuses the Eng29 BPMN files themselves).
 *
 * <ul>
 *   <li>{@code strict=false} (default): missing variable → legacy silent {@code ""}
 *       STRING, no exception, plus a WARN carrying source/target;</li>
 *   <li>{@code strict=true}: WO-ENG-29 behaviour byte-identical (incident);</li>
 *   <li>hard FEEL error (syntax): incident in BOTH modes — the flag never covers it.</li>
 * </ul>
 *
 * <p>POF-мутации (каждая валится ровно своим тестом):
 * <ul>
 *   <li>флаг игнорируется, всегда throw (как до WO-ENG-30) —
 *       {@code lenient_*} КРАСНЫЕ;</li>
 *   <li>флаг игнорируется, никогда не throw (полный откат WO-ENG-29) —
 *       {@code strict_*} КРАСНЫЙ;</li>
 *   <li>hard-failure ветка под флагом (единое {@code failed} без разделения) —
 *       {@code hardError_*} КРАСНЫЙ ровно в lenient-моде.</li>
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

    // ─── Критерий 1: lenient — тихий "" + WARN, без исключения ──────────────

    @Test
    void lenient_missingVariable_returnsLegacyEmptyStringWithoutThrowing() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ElementSupport.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProcessVariable variable = lenient.evaluateMapping(
                mapping("=routeStageUuid", "routeStageUuid"), List.of());

            assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
            assertThat(variable.getValue())
                .as("lenient mode keeps the pre-ENG-29 silent '' behaviour, no incident")
                .isEqualTo("");
            assertThat(appender.list)
                .as("lenient mode must log a WARN carrying source/target for log-based inventory")
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("routeStageUuid");
                });
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ─── Критерий 2: strict — поведение WO-ENG-29 как есть ──────────────────

    @Test
    void strict_missingVariable_throwsFeelEvaluationException() {
        assertThatThrownBy(() -> strict.evaluateMapping(
                mapping("=routeStageUuid", "routeStageUuid"), List.of()))
            .as("strict mode keeps the WO-ENG-29 incident behaviour byte-identical")
            .isInstanceOf(FeelEvaluationException.class)
            .hasMessageContaining("routeStageUuid");
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

    // ─── Регресс: честный null — тихо в ОБОИХ режимах ───────────────────────

    @Test
    void honestNull_explicitLiteral_staysSilentInBothModes() {
        for (ElementSupport support : List.of(lenient, strict)) {
            ProcessVariable variable = support.evaluateMapping(
                mapping("=null", "nullEcho"), List.of());
            assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
            assertThat(variable.getValue())
                .as("explicit =null is honest null, not a failure — silent in both modes")
                .isEqualTo("");
        }
    }
}
