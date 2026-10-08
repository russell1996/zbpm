package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-VT-1 п.4/4-бис: серверная валидация содержимого шаблона — табличный тест
 * на КЛАСС входов (урок красных команд: не штучные кейсы).
 *
 * <p>Правила типов — проверка парсинга (движок на входе не валидирует вовсе,
 * см. javadoc валидатора). Пустое значение ({@code ""}) допустимо для ЛЮБОГО
 * типа («спросить при запуске», решение владельца); {@code allowEmptyString} —
 * только boolean и только для STRING.
 */
class VariablePresetValidatorTest {

    /** Тип × валидное значение × невалидное значение. */
    static Stream<Arguments> typedValues() {
        return Stream.of(
            Arguments.of(ProcessVariableType.LONG, "42", "42.5"),
            Arguments.of(ProcessVariableType.LONG, "-7", "abc"),
            Arguments.of(ProcessVariableType.DOUBLE, "3.14", "NaN"),
            Arguments.of(ProcessVariableType.DOUBLE, "-0.5", "abc"),
            Arguments.of(ProcessVariableType.BOOLEAN, "true", "yes"),
            Arguments.of(ProcessVariableType.BOOLEAN, "FALSE", "1"),
            Arguments.of(ProcessVariableType.UUID, "123e4567-e89b-12d3-a456-426614174000", "not-a-uuid"),
            Arguments.of(ProcessVariableType.JSON, "{\"a\":1}", "{broken"),
            Arguments.of(ProcessVariableType.JSON, "[1,2]", "plain"),
            Arguments.of(ProcessVariableType.STRING, "anything at all", null),
            Arguments.of(ProcessVariableType.STRING, "", null));
    }

    @ParameterizedTest(name = "{0} valid=''{1}'' invalid=''{2}''")
    @MethodSource("typedValues")
    void typedValue_validPasses_invalidFails(ProcessVariableType type, String valid, String invalid) {
        assertThat(VariablePresetValidator.parseAndValidate(jsonVar("v", type, valid)).errors())
            .as("%s '%s' обязан проходить", type, valid)
            .isEmpty();
        if (invalid != null) {
            assertThat(VariablePresetValidator.parseAndValidate(jsonVar("v", type, invalid)).errors())
                .as("%s '%s' обязан отклоняться", type, invalid)
                .isNotEmpty();
        }
    }

    /** WO-VT-1 п.4: пустое значение — для КАЖДОГО типа, формат не проверяется. */
    @ParameterizedTest(name = "{0} empty means ask-at-run")
    @MethodSource("allTypes")
    void emptyValue_allowedForEveryType(ProcessVariableType type) {
        VariablePresetValidator.VariablesResult result =
            VariablePresetValidator.parseAndValidate(jsonVar("v", type, ""));
        assertThat(result.errors()).as("пусто у %s — «спросить», не ошибка", type).isEmpty();
        assertThat(result.variables()).hasSize(1);
        assertThat(result.variables().get(0).getValue()).isEmpty();
    }

    static Stream<Arguments> allTypes() {
        return Stream.of(ProcessVariableType.values()).map(Arguments::of);
    }

    /** allowEmptyString: true/false/absent у STRING — ок; true у не-STRING — ошибка. */
    @Test
    void allowEmptyString_true_onlyForString() {
        assertThat(VariablePresetValidator
            .parseAndValidate("[{\"name\":\"s\",\"type\":\"STRING\",\"value\":\"\",\"allowEmptyString\":true}]")
            .errors()).isEmpty();
        assertThat(VariablePresetValidator
            .parseAndValidate("[{\"name\":\"s\",\"type\":\"STRING\",\"value\":\"\"}]")
            .errors()).isEmpty();
        assertThat(VariablePresetValidator
            .parseAndValidate("[{\"name\":\"n\",\"type\":\"LONG\",\"value\":\"\",\"allowEmptyString\":true}]")
            .errors())
            .as("allowEmptyString у LONG — ошибка поля")
            .anyMatch(e -> e.field().contains("allowEmptyString"));
        assertThat(VariablePresetValidator
            .parseAndValidate("[{\"name\":\"s\",\"type\":\"STRING\",\"value\":\"x\",\"allowEmptyString\":\"yes\"}]")
            .errors())
            .as("allowEmptyString не-boolean — ошибка поля")
            .anyMatch(e -> e.field().contains("allowEmptyString"));
    }

    /** Флаг переживает сериализацию (иначе «пустая строка как значение»
     * неотличима от «спросить» после round-trip). */
    @Test
    void allowEmptyString_survivesRoundTrip() {
        String json =
            "[{\"name\":\"s\",\"type\":\"STRING\",\"value\":\"\",\"allowEmptyString\":true}]";
        VariablePresetValidator.VariablesResult parsed =
            VariablePresetValidator.parseAndValidate(json);
        assertThat(parsed.errors()).isEmpty();
        String stored = VariablePresetValidator.toJson(parsed.variables());
        assertThat(stored).contains("\"allowEmptyString\":true");
        List<ProcessVariable> reread = VariablePresetValidator.parseStored(stored);
        assertThat(reread).hasSize(1);
        assertThat(reread.get(0).getAllowEmptyString()).isTrue();
        assertThat(reread.get(0).getValue()).isEmpty();
    }

    /** Плейсхолдеры допустимы в значении ЛЮБОГО типа как есть. */
    @ParameterizedTest(name = "{0} placeholder in {1}")
    @MethodSource("placeholdersByType")
    void placeholders_allowedInAnyType(String placeholder, ProcessVariableType type) {
        String value = type == ProcessVariableType.JSON
            ? "{\"id\":\"" + placeholder + "\"}" : "prefix-" + placeholder + "-suffix";
        assertThat(VariablePresetValidator.parseAndValidate(jsonVar("v", type, value)).errors())
            .as("%s в %s обязан проходить", placeholder, type)
            .isEmpty();
    }

    static Stream<Arguments> placeholdersByType() {
        return VariablePresetValidator.PLACEHOLDERS.stream()
            .flatMap(p -> Stream.of(ProcessVariableType.values()).map(t -> Arguments.of("{{" + p + "}}", t)));
    }

    @Test
    void placeholders_unknownName_rejected_unbalanced_rejected() {
        assertThat(VariablePresetValidator
            .parseAndValidate(jsonVar("v", ProcessVariableType.STRING, "{{nope}}")).errors())
            .isNotEmpty();
        assertThat(VariablePresetValidator
            .parseAndValidate(jsonVar("v", ProcessVariableType.STRING, "{{uuid}")).errors())
            .isNotEmpty();
        assertThat(VariablePresetValidator
            .parseAndValidate(jsonVar("v", ProcessVariableType.STRING, "x}}y")).errors())
            .isNotEmpty();
    }

    /** Лимиты WO: ≤100 переменных, значение ≤256 КБ, уникальные имена. */
    @Test
    void limits_tooManyVariables_rejected() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"v").append(i).append("\",\"type\":\"STRING\",\"value\":\"x\"}");
        }
        sb.append(']');
        assertThat(VariablePresetValidator.parseAndValidate(sb.toString()).errors())
            .anyMatch(e -> e.field().equals("variables"));
    }

    @Test
    void limits_oversizeValue_rejected() {
        String big = "x".repeat((int) (VariablePresetValidator.MAX_VALUE_BYTES + 1));
        assertThat(VariablePresetValidator.parseAndValidate(jsonVar("v", ProcessVariableType.STRING, big)).errors())
            .anyMatch(e -> e.field().contains("value"));
    }

    @Test
    void limits_duplicateName_rejected() {
        String json = "[{\"name\":\"a\",\"type\":\"STRING\",\"value\":\"1\"},"
            + "{\"name\":\"a\",\"type\":\"STRING\",\"value\":\"2\"}]";
        assertThat(VariablePresetValidator.parseAndValidate(json).errors())
            .anyMatch(e -> e.field().contains("name"));
    }

    /**
     * Пример владельца (дословно из запроса): createdEmployeeId LONG, sourceUuid
     * UUID, stages JSON с экранированной строкой и кириллицей — round-trip
     * без потерь.
     */
    @Test
    void ownerExample_roundTrip_withoutLoss() {
        String json = "["
            + "{\"name\":\"createdEmployeeId\",\"type\":\"LONG\",\"value\":\"12345\"},"
            + "{\"name\":\"sourceUuid\",\"type\":\"UUID\",\"value\":\"123e4567-e89b-12d3-a456-426614174000\"},"
            + "{\"name\":\"stages\",\"type\":\"JSON\",\"value\":\"{\\\"title\\\":\\\"Привет \\\\\\\"мир\\\\\\\"\\\",\\\"n\\\":2}\"}"
            + "]";
        VariablePresetValidator.VariablesResult parsed =
            VariablePresetValidator.parseAndValidate(json);
        assertThat(parsed.errors()).isEmpty();
        List<ProcessVariable> reread =
            VariablePresetValidator.parseStored(VariablePresetValidator.toJson(parsed.variables()));
        assertThat(reread).hasSize(3);
        assertThat(reread.get(0).getName()).isEqualTo("createdEmployeeId");
        assertThat(reread.get(0).getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(reread.get(2).getValue()).contains("Привет");
    }

    private static String jsonVar(String name, ProcessVariableType type, String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "[{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"value\":\"" + escaped + "\"}]";
    }
}
