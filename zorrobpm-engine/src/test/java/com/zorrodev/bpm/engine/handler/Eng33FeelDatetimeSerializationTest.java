package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.configuration.FeelBigDecimalNumberMapper;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.impl.FeelBudgetImpl;
import com.zorrodev.bpm.engine.service.impl.ScriptServiceImpl;
import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.FeelEngineBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * WO-ENG-33: объект даты FEEL, дошедший до {@code ElementSupport#toProcessVariable},
 * сериализуется в interoperable ISO-строку с офсетом — а не в Java
 * {@code toString()} с {@code [ZoneId]}, который не парсит никто кроме Java
 * (живой инцидент: Go-API получило {@code ...+05:00[Asia/Almaty]} и ответило
 * {@code extra text: "[Asia/Almaty]"}).
 *
 * <p>Тесты идут реальным прод-путём: настоящий {@code FeelEngineApi} (та же
 * сборка, что {@code DmnEngineConfig}), настоящий {@code FeelBudgetImpl} +
 * {@code ScriptServiceImpl}, настоящее {@code ElementSupport#evaluateMapping} —
 * тот же wiring, что у {@code Eng29FeelNullIoMappingUnitTest}, без Spring.
 *
 * <p>POF-мутации (каждая валится ровно своим тестом, P-67):
 * <ul>
 *   <li>убрать новую {@code TemporalAccessor}-ветку из {@code toProcessVariable} →
 *       criterion1/criterion2/criterion3 КРАСНЫЕ (actual содержит
 *       {@code [Asia/Almaty]});</li>
 *   <li>«починить» слой 1 (отрезать {@code @Zone} из {@code string(now())}) →
 *       criterion4 КРАСНЫЙ (этот тест фиксирует поведение как есть, запрет
 *       чинить апстрим-паритет руками);</li>
 *   <li>заменить {@code EngineException} на молчаливый {@code toString()} для
 *       неизвестного {@code TemporalAccessor} →
 *       {@code unknownTemporalAccessor_throwsEngineException} КРАСНЫЙ.</li>
 * </ul>
 */
class Eng33FeelDatetimeSerializationTest {

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    private ElementSupport elementSupport;

    @BeforeEach
    void wiring() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ScriptService scriptService = new ScriptServiceImpl(
            new org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory().getScriptEngine(),
            new org.camunda.feel.impl.script.FeelScriptEngineFactory().getScriptEngine(),
            new tools.jackson.databind.ObjectMapper(),
            new BpmMetrics(registry), 10, 2, 10, 5);
        // Same builder shape as DmnEngineConfig#feelEngineApi (forJava + BigDecimal mapper).
        FeelEngineApi feelEngineApi =
            FeelEngineBuilder.forJava().withCustomValueMapper(new FeelBigDecimalNumberMapper()).build();
        FeelBudget feelBudget = new FeelBudgetImpl(scriptService, feelEngineApi);
        elementSupport = new ElementSupport(mock(DBService.class), scriptService, feelBudget,
            new tools.jackson.databind.ObjectMapper(), ALMATY, true);
    }

    private static IoMappingExtensionModel.Mapping mapping(String source, String target) {
        IoMappingExtensionModel.Mapping m = new IoMappingExtensionModel.Mapping();
        m.setSource(source);
        m.setTarget(target);
        return m;
    }

    // ─── Критерий 1: ZonedDateTime → ISO-offset, без [ZoneId] ────────────────

    @Test
    void criterion1_zonedDateTime_formatsAsIsoOffsetWithoutZoneName() {
        ZonedDateTime zdt = ZonedDateTime.of(2026, 9, 30, 15, 49, 48, 966494894, ALMATY);

        ProcessVariable variable = elementSupport.toProcessVariable("started", zdt);

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue())
            .as("ZonedDateTime must serialize without the Java [ZoneId] suffix")
            .doesNotContain("[")
            .doesNotContain("]");
        assertThat(variable.getValue())
            .as("offset must survive in RFC 3339 form (Go layout 2006-01-02T15:04:05Z07:00 eats this)")
            .isEqualTo("2026-09-30T15:49:48.966494894+05:00");
    }

    // ─── Критерий 2: остальные temporal-типы — своим ISO-форматтером ─────────

    @Test
    void criterion2_offsetDateTime_formatsAsIsoOffset() {
        OffsetDateTime odt = OffsetDateTime.of(2026, 9, 30, 15, 49, 48, 0,
            java.time.ZoneOffset.ofHours(5));

        ProcessVariable variable = elementSupport.toProcessVariable("v", odt);

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue()).isEqualTo("2026-09-30T15:49:48+05:00");
    }

    @Test
    void criterion2_instant_formatsAsIsoInstant() {
        Instant instant = Instant.parse("2026-09-30T10:49:48.123Z");

        ProcessVariable variable = elementSupport.toProcessVariable("v", instant);

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue()).isEqualTo("2026-09-30T10:49:48.123Z");
    }

    @Test
    void criterion2_localDateTime_formatsAsIsoLocalWithoutOffset() {
        LocalDateTime ldt = LocalDateTime.of(2026, 9, 30, 15, 49, 48);

        ProcessVariable variable = elementSupport.toProcessVariable("v", ldt);

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue())
            .as("zone-naive value must NOT gain an invented offset — ISO_LOCAL, honestly offset-free")
            .isEqualTo("2026-09-30T15:49:48");
    }

    @Test
    void criterion2_localDate_formatsAsIsoLocalDate() {
        ProcessVariable variable = elementSupport.toProcessVariable("v", LocalDate.of(2026, 9, 30));

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue()).isEqualTo("2026-09-30");
    }

    @Test
    void criterion2_localTime_formatsAsIsoLocalTime() {
        ProcessVariable variable = elementSupport.toProcessVariable("v", LocalTime.of(15, 49, 48));

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue()).isEqualTo("15:49:48");
    }

    @Test
    void criterion2_offsetTime_formatsAsIsoOffsetTime() {
        ProcessVariable variable = elementSupport.toProcessVariable("v",
            java.time.OffsetTime.of(15, 49, 48, 0, java.time.ZoneOffset.ofHours(5)));

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue()).isEqualTo("15:49:48+05:00");
    }

    @Test
    void unknownTemporalAccessor_throwsEngineException() {
        // WO-ENG-33 п.1: неизвестный TemporalAccessor — fail-closed явным
        // EngineException, а не молчаливый toString() с квадратными скобками.
        // FEEL date/time-функции отдают только типы выше (ZonedDateTime /
        // LocalDate(Time) / Period-Duration, а Period/Duration — вообще не
        // TemporalAccessor), так что сюда живое значение попасть не может —
        // это охранник от будущих типов, не от текущего FEEL-набора.
        assertThatThrownBy(() -> elementSupport.toProcessVariable("v", java.time.Year.of(2026)))
            .as("unknown TemporalAccessor must fail closed, not leak Java toString()")
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("v");
    }

    // ─── Критерий 3: сквозной путь io-mapping =now() ────────────────────────

    @Test
    void criterion3_nowIoMapping_producesGoParsableOffsetString() {
        ProcessVariable variable = elementSupport.evaluateMapping(
            mapping("=now()", "started"), List.of());

        assertThat(variable.getType())
            .as("=now() through the real prod path must land as STRING (contract unchanged)")
            .isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue())
            .as("must not contain the Java [ZoneId] suffix that broke the Go consumer")
            .doesNotContain("[")
            .doesNotContain("]");
        OffsetDateTime parsed = OffsetDateTime.parse(variable.getValue(),
            DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        assertThat(java.time.Duration.between(parsed.toInstant(), Instant.now()).abs())
            .as("parsed instant must be ~now (proves the value is a real timestamp, not a placeholder)")
            .isLessThan(java.time.Duration.ofMinutes(1));
    }

    // ─── Критерий 4: string(now()) — слой feel-scala, НЕ чиним ───────────────

    @Test
    void criterion4_stringNow_keepsUpstreamAtZoneSuffixUnchanged() {
        // Слой 1 из WO: string() исполняется ВНУТРИ feel-scala 1.19.3 (та же
        // линия, что Camunda 8) — суффикс @ZoneId это апстрим-паритет, руками в
        // либу не лезем. Этот тест фиксирует факт как есть: строка проходит
        // через toProcessVariable нетронутой. Мутация «отрезать @Zone» обязана
        // ронять именно этот тест.
        ProcessVariable variable = elementSupport.evaluateMapping(
            mapping("=string(now())", "s"), List.of());

        assertThat(variable.getType()).isEqualTo(ProcessVariableType.STRING);
        assertThat(variable.getValue())
            .as("feel-scala string(now()) shape passes through unchanged (upstream parity, not fixed here)")
            .contains("@")
            .doesNotContain("[");
    }
}
