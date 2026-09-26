package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-ENG-25 (NEW-05+NEW-06): FEEL↔Java числовой контракт.
 *
 * <p>Два пути одного языка давали разные Java-типы: script-FEEL (JSR-223) —
 * {@code scala.math.BigDecimal}, DMN/io-mapping ({@code forJava}) — {@code Double}.
 * Оба тихо меняли бизнес-значение: script-путь ложно считал дробное «целым»
 * (double ≥2⁵³ теряет дробь → ложный "outside LONG range"), DMN-путь считал
 * агрегаты в double (0.1+0.2 = 0.30000000000000004).
 *
 * <p>Решение (см. agent-to-cto, ADR-9): ADR-принцип «decimal = BigDecimal везде
 * внутри рантайма» с точечной реализацией — оба пути сходятся в одном
 * {@code toProcessVariable}, формат хранения не меняется (LONG/DOUBLE-строки).
 * Честный residual: одиночные DMN-числа остаются Double-точности (ограничение
 * самого forJava-движка), агрегаты считаются точно.
 *
 * <p>POF-мутации: убрать {@code normalizeFeelNumber} из {@code toProcessVariable} —
 * criterion2 КРАСНЫЙ (ложный "outside LONG range"); вернуть {@code mapToDouble} в
 * {@code aggregate} — criterion1 КРАСНЫЙ (0.30000000000000004); вернуть
 * {@code orElse(0)} — criterion3 КРАСНЫЙ; вернуть silent-list на неизвестную
 * агрегацию — criterion4 КРАСНЫЙ.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class FeelNumberContractTest {

    @Autowired
    private DmnService dmnService;

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private ElementSupport elementSupport;

    @BeforeEach
    void deployAll() throws Exception {
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-collect-sum-decimal.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-collect-null.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-collect-unknown-agg.dmn")));
    }

    // ─── Критерий 1: DMN COLLECT SUM ['0.1','0.2'] → 0.3 ────────────────────

    @Transactional
    @Test
    void criterion1_collectSumDecimals_isExactlyPointThree() {
        // score=1 матчит оба правила (>= 0 → 0.1, >= 1 → 0.2).
        Object result = dmnService.evaluate("collectSumDecimalDecision", List.of(num("score", "1")));
        assertThat(result)
            .as("COLLECT SUM of 0.1 and 0.2 must be exactly 0.3, not 0.30000000000000004")
            .isEqualTo(new BigDecimal("0.3"));
    }

    // ─── Критерий 2: script-FEEL 12345678901234567.89 — точно, без ложного LONG ──

    @Test
    void criterion2_scriptFeelLargeDecimal_storedExactlyWithoutLongError() {
        // Реальный script-путь: JSR-223 FEEL возвращает scala.math.BigDecimal.
        Object raw = scriptService.evaluateExpression("12345678901234567.89", List.of());
        assertThat(raw)
            .as("script-FEEL must return a BigDecimal-shaped value (precondition of the bug)")
            .isInstanceOf(Number.class);

        ProcessVariable variable = elementSupport.toProcessVariable("amount", raw);
        assertThat(variable.getType())
            .as("fractional value must be DOUBLE, never LONG")
            .isEqualTo(ProcessVariableType.DOUBLE);
        assertThat(variable.getValue())
            .as("17-значная дробь сохраняется без потери точности")
            .isEqualTo("12345678901234567.89");
    }

    @Test
    void criterion2_scalaBigDecimalNormalized_beforeIntegralCheck() {
        // Тот же контракт напрямую: scala-значение с дробью ≥2⁵³ — не целое.
        // Строковый парс (как FEEL парсит числовой литерал), НЕ decimal(double):
        // decimal(double) округляет до binary-double до нашего кода и дробь
        // потеряна заранее — это не путь бага.
        scala.math.BigDecimal scalaValue =
            new scala.math.BigDecimal(new java.math.BigDecimal("12345678901234567.89"));
        assertThat(elementSupport.isIntegral(scalaValue))
            .as("fractional scala.BigDecimal must not be integral (double-детур терял дробь)")
            .isFalse();
        ProcessVariable variable = elementSupport.toProcessVariable("amount", scalaValue);
        assertThat(variable.getType()).isEqualTo(ProcessVariableType.DOUBLE);
    }

    // ─── Критерий 3: null-выход DMN — по DMN-семантике ───────────────────────

    @Transactional
    @Test
    void criterion3_nullSumAndMin_returnNullNotZero() {
        // Правило сматчилось, но output пуст → SUM/MIN агрегат пустого множества = null.
        Object sum = dmnService.evaluate("collectNullSumDecision", List.of(num("score", "5")));
        assertThat(sum)
            .as("SUM over all-null outputs must be null (DMN), not 0 and not NPE")
            .isNull();
        Object min = dmnService.evaluate("collectNullMinDecision", List.of(num("score", "5")));
        assertThat(min)
            .as("MIN over all-null outputs must be null (DMN), not 0 and not NPE")
            .isNull();
    }

    // ─── Критерий 4: неизвестная агрегация — явный EngineException ───────────

    @Transactional
    @Test
    void criterion4_unknownAggregation_rejectedExplicitly() {
        assertThatThrownBy(() -> dmnService.evaluate("collectUnknownAggDecision", List.of(num("score", "5"))))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("unknown aggregation")
            .hasMessageContaining("AVERAGE");
    }

    private static ProcessVariable num(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.DOUBLE);
        v.setValue(value);
        return v;
    }
}
