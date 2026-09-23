package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.DmnService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-ENG-21 (N10, повторный аудит 2026-09-23): целое FEEL-число за пределами
 * {@code Long} молча сохранялось как другое число — {@code toProcessVariable}/
 * {@code isIntegral} проверяли только {@code scale}, затем звали
 * {@code longValue()} без проверки диапазона.
 *
 * <p>Контракт фикса (п.2 WO — путь «явный incident», БЕЗ расширения
 * {@code ProcessVariableType}, поэтому G-C эскалация не требуется; откат
 * в {@code double} для денег запрещён тем же пунктом):
 * {@code integral && withinLongRange} → {@code LONG} точно,
 * целое вне диапазона → {@code EngineException}, не усечение битов.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class FeelNumericOverflowTest {

    @Autowired
    private DmnService dmnService;

    @Autowired
    private ElementSupport elementSupport;

    private static ProcessVariable str(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    // ─── Критерий 1: границы Long — точное сохранение ────────────────────

    @Test
    void criterion1_longBoundaries_storedExactly() {
        ProcessVariable min = elementSupport.toProcessVariable("v", Long.MIN_VALUE);
        assertThat(min.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(min.getValue()).isEqualTo("-9223372036854775808");

        ProcessVariable max = elementSupport.toProcessVariable("v", Long.MAX_VALUE);
        assertThat(max.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(max.getValue()).isEqualTo("9223372036854775807");

        // Та же граница, пришедшая как FEEL-число (BigDecimal), — тоже точно.
        ProcessVariable maxBd =
            elementSupport.toProcessVariable("v", new BigDecimal("9223372036854775807"));
        assertThat(maxBd.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(maxBd.getValue()).isEqualTo("9223372036854775807");

        ProcessVariable minBd =
            elementSupport.toProcessVariable("v", new BigDecimal("-9223372036854775808"));
        assertThat(minBd.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(minBd.getValue()).isEqualTo("-9223372036854775808");

        // Обычные узкие целые — без изменений.
        ProcessVariable small = elementSupport.toProcessVariable("v", 42);
        assertThat(small.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(small.getValue()).isEqualTo("42");
    }

    // ─── Критерий 2: ±1 за границей — явный отказ, не тихая порча ────────

    @Test
    void criterion2_justOutsideLongRange_refusedExplicitlyNotTruncated() {
        // Long.MAX_VALUE + 1: старый код классифицировал как "integral" и звал
        // longValue() — старшие биты терялись молча (получался Long.MIN_VALUE).
        assertThatThrownBy(() ->
            elementSupport.toProcessVariable("orderId", new BigDecimal("9223372036854775808")))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("orderId")
            .hasMessageContaining("LONG");

        // Long.MIN_VALUE - 1, отрицательная сторона.
        assertThatThrownBy(() ->
            elementSupport.toProcessVariable("orderId", new BigDecimal("-9223372036854775809")))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("orderId");

        // Тот же выход за диапазон как BigInteger (не BigDecimal).
        assertThatThrownBy(() ->
            elementSupport.toProcessVariable("orderId", new BigInteger("9223372036854775808")))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("orderId");

        assertThatThrownBy(() ->
            elementSupport.toProcessVariable("orderId", new BigInteger("-9223372036854775809")))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("orderId");
    }

    // ─── Критерий 3: большие/отрицательные/дробные — корректны ───────────

    @Test
    void criterion3_bigIntegersNegativesAndFractionals_correct() {
        // Большой BigInteger ВНУТРИ диапазона — точный LONG.
        ProcessVariable bigInRange =
            elementSupport.toProcessVariable("v", new BigInteger("1234567890123456789"));
        assertThat(bigInRange.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(bigInRange.getValue()).isEqualTo("1234567890123456789");

        // Отрицательные целые — точный LONG.
        ProcessVariable negative =
            elementSupport.toProcessVariable("v", new BigDecimal("-42"));
        assertThat(negative.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(negative.getValue()).isEqualTo("-42");

        // Целое с ненулевым scale (100.00) — всё ещё целое → LONG, не DOUBLE.
        ProcessVariable trailingZeros =
            elementSupport.toProcessVariable("v", new BigDecimal("100.00"));
        assertThat(trailingZeros.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(trailingZeros.getValue()).isEqualTo("100");

        // Дробные — DOUBLE с точной десятичной строкой (не через binary double).
        ProcessVariable fractional =
            elementSupport.toProcessVariable("v", new BigDecimal("42.5"));
        assertThat(fractional.getType()).isEqualTo(ProcessVariableType.DOUBLE);
        assertThat(fractional.getValue()).isEqualTo("42.5");

        ProcessVariable negativeFractional =
            elementSupport.toProcessVariable("v", new BigDecimal("-0.1"));
        assertThat(negativeFractional.getType()).isEqualTo(ProcessVariableType.DOUBLE);
        assertThat(negativeFractional.getValue()).isEqualTo("-0.1");
    }

    // ─── Критерий 4: путь DMN → process variable — тот же guard ──────────

    private static final String OVERFLOW_DMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="Definitions_overflowProbeN10" name="overflowProbeN10" namespace="http://camunda.org/schema/1.0/dmn">
          <decision id="overflowProbeN10" name="OverflowProbe">
            <decisionTable id="DecisionTable_1" hitPolicy="FIRST">
              <input id="Input_1" label="Category">
                <inputExpression id="InputExpression_1" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_1" label="Amount" name="amount" typeRef="number" />
              <rule id="Rule_gold">
                <inputEntry id="In_gold"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_gold"><text>9223372036854775808</text></outputEntry>
              </rule>
              <rule id="Rule_default">
                <inputEntry id="In_default"><text></text></inputEntry>
                <outputEntry id="Out_default"><text>0</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    @Transactional
    @Test
    void criterion4_dmnOverflowPath_refusedThroughSameGuard() {
        dmnService.deploy(OVERFLOW_DMN);
        Object result = dmnService.evaluate("overflowProbeN10", List.of(str("category", "gold")));

        // Путь упражнения доказан двумя независимыми фактами, а не словами:
        // результат — число (иначе проверялся бы не тот путь) И оно вне
        // диапазона LONG (иначе guard ниже проходил бы тривиально).
        // Замечание о слое: пробник FeelNumberProbeTest показал, что сам
        // FeelEngineApi возвращает целые в диапазоне Long как Long, а 2^63 —
        // как Double 9.223372036854776E18 (точность потеряна уже в FEEL-
        // библиотеке, вне scope N10 — N10 про усечение в toProcessVariable).
        // Этот setup-assert держится при любом из двух представлений.
        assertThat(result).isInstanceOf(Number.class);
        assertThat(new BigDecimal(result.toString()))
            .as("DMN-результат вне диапазона LONG — путь переполнения упражнён")
            .isGreaterThan(new BigDecimal(Long.MAX_VALUE));

        // Тот же общий toProcessVariable, что у script/io-mapping путей, —
        // явный отказ вместо тихого усечения DMN-результата.
        assertThatThrownBy(() -> elementSupport.toProcessVariable("amount", result))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("amount");
    }

    @Transactional
    @Test
    void criterion4_dmnNormalPath_stillStoresLong() throws Exception {
        // Контрольный: обычное DMN-число guard не ломает — LONG как раньше.
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-discount.dmn")));
        Object result = dmnService.evaluate("discount", List.of(str("category", "gold")));

        ProcessVariable v = elementSupport.toProcessVariable("discount", result);
        assertThat(v.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(v.getValue()).isEqualTo("20");
    }
}
