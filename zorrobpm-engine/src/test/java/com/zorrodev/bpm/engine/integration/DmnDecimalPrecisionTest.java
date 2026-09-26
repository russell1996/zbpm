package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.FeelBudget;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-27 (NEW2-05): DMN/io-mapping FEEL тихо терял точность и менял тип числа.
 *
 * <p>{@code FeelEngineBuilder.forJava()} распаковывал числа в {@code Double}:
 * {@code 12345678901234567.89} превращалось в {@code 1.2345678901234568E16},
 * и {@code toProcessVariable} молча хранил его как LONG '12345678901234568'
 * (Double ≥ 2⁵³ теряет дробную часть → ложно считается «целым»).
 *
 * <p>Фикс — {@code FeelBigDecimalNumberMapper} в {@code DmnEngineConfig}:
 * {@code ValNumber} распаковывается в {@code java.math.BigDecimal} точно.
 * Приёмник уже держал BigDecimal (WO-ENG-25), script/JSR-223-путь не тронут.
 *
 * <p>POF-мутация: убрать {@code withCustomValueMapper} из {@code DmnEngineConfig} —
 * criterion1 КРАСНЫЙ (LONG '12345678901234568' вместо DOUBLE), criterion2 КРАСНЫЙ
 * (Double-произведение вместо точного '3000000000.21').
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class DmnDecimalPrecisionTest {

    @Autowired
    private DmnService dmnService;

    @Autowired
    private FeelBudget feelBudget;

    @Autowired
    private ElementSupport elementSupport;

    @BeforeEach
    void deployAll() throws Exception {
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-decimal-precision.dmn")));
    }

    // ─── Критерий 1: 17 значащих цифр — точно, не LONG ─────────────────────

    @Transactional
    @Test
    void criterion1_largeDecimalDecision_storedExactlyNotLong() {
        Object result = dmnService.evaluate("largeDecimalDecision", List.of(flag("go")));
        assertThat(result)
            .as("DMN-выход обязан быть точным BigDecimal, а не Double 1.2345678901234568E16")
            .isInstanceOf(BigDecimal.class)
            .isEqualTo(new BigDecimal("12345678901234567.89"));

        ProcessVariable variable = elementSupport.toProcessVariable("amount", result);
        assertThat(variable.getType())
            .as("дробное значение обязано быть DOUBLE, никогда LONG")
            .isEqualTo(ProcessVariableType.DOUBLE);
        assertThat(variable.getValue())
            .as("17-значная дробь сохраняется без потери точности")
            .isEqualTo("12345678901234567.89");
    }

    @Test
    void criterion1_feelExpressionLargeDecimal_unpacksToExactBigDecimal() {
        // Тот же бин-путь, что DMN и io-mapping-резолв (=expr): сырой unpack.
        Object raw = feelBudget.evaluateExpression("12345678901234567.89", Map.of()).result();
        assertThat(raw)
            .as("forJava+ValueMapper обязан отдать точный BigDecimal")
            .isInstanceOf(BigDecimal.class);
        assertThat(raw.toString()).isEqualTo("12345678901234567.89");
    }

    // ─── Критерий 2: дробное произведение — точное ─────────────────────────

    @Transactional
    @Test
    void criterion2_fractionalProductDecision_keepsFractionExactly() {
        Object result = dmnService.evaluate("fractionalProductDecision", List.of(flag("go")));
        assertThat(result)
            .as("DMN-выход обязан быть точным BigDecimal, а не Double 3.00000000021E9")
            .isInstanceOf(BigDecimal.class)
            .isEqualTo(new BigDecimal("3000000000.21"));

        ProcessVariable variable = elementSupport.toProcessVariable("total", result);
        assertThat(variable.getType()).isEqualTo(ProcessVariableType.DOUBLE);
        assertThat(variable.getValue()).isEqualTo("3000000000.21");
    }

    // ─── ElementSupport-контракт (WO п.2): согласованность с BigDecimal ────

    @Test
    void elementSupport_bigDecimalFraction_neverIntegral() {
        assertThat(elementSupport.isIntegral(new BigDecimal("12345678901234567.89")))
            .as("дробный BigDecimal ≥2⁵³ обязан быть нецелым (иначе — молчаливый LONG)")
            .isFalse();
    }

    @Test
    void elementSupport_bigDecimalWhole_stillLong() {
        // Целые не меняют представление: 42 остаётся LONG '42', как при Double/Long.
        ProcessVariable variable = elementSupport.toProcessVariable("n", new BigDecimal("42"));
        assertThat(variable.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(variable.getValue()).isEqualTo("42");
    }

    private static ProcessVariable flag(String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName("flag");
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }
}
