package com.zorrodev.bpm.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.VariablePresetTargetKind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * WO-VT-1: серверная валидация содержимого шаблона переменных.
 *
 * <p>Правила типов — ТЕ ЖЕ, что действуют для значений в движке: LONG/DOUBLE/BOOLEAN
 * парсятся, UUID — формат, JSON — валидный JSON, STRING — любая строка. Движок на
 * входе не валидирует вовсе (пишет текстом), поэтому «те же правила» здесь означают
 * проверку парсинга, а не побайтовое совпадение с каким-то методом движка — такого
 * метода нет, сверено grep'ом (P-1: не выдумывать несуществующий образец).
 *
 * <p>Плейсхолдеры {@code {{uuid}}}, {@code {{now}}}, {@code {{random:long}}},
 * {@code {{seq}}} допустимы в значении ЛЮБОГО типа как есть: тип после подстановки
 * проверяет клиент, сервер проверяет только синтаксис {@code {{…}}} (известное имя,
 * сбалансированные скобки). Значение с корректным плейсхолдером проверку типа НЕ
 * проходит — иначе {@code {{uuid}}} в LONG-шаблоне был бы невозможен.
 *
 * <p>Чистый класс без Spring-зависимостей — гоняется табличным unit-тестом по
 * КАЖДОМУ типу (валид/невалид), плейсхолдерам и лимитам.
 */
public final class VariablePresetValidator {

    /** WO-VT-1: не более 100 переменных в шаблоне. */
    public static final int MAX_VARIABLES = 100;
    /** WO-VT-1: значение не более 256 КБ (в байтах UTF-8). */
    public static final long MAX_VALUE_BYTES = 256L * 1024;
    /** WO-VT-1: не более 200 шаблонов на (ключ процесса, владелец). */
    public static final int MAX_PRESETS_PER_KEY_OWNER = 200;
    /** WO-VT-1: допустимые плейсхолдеры (закрытый набор — неизвестное имя отклоняется). */
    public static final Set<String> PLACEHOLDERS = Set.of("uuid", "now", "random:long", "seq");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VariablePresetValidator() {
    }

    public record FieldError(String field, String message) {
    }

    /**
     * Проверяет топ-level поля привязки. Возвращает список ошибок (пуст = ок).
     *
     * @param processDefinitionKey ключ процесса (non-blank)
     * @param targetKind           вид места применения
     * @param targetRef            привязка (обязательна для всех видов кроме START)
     */
    public static List<FieldError> validateBinding(
            String processDefinitionKey, VariablePresetTargetKind targetKind, String targetRef) {
        List<FieldError> errors = new ArrayList<>();
        if (processDefinitionKey == null || processDefinitionKey.isBlank()) {
            errors.add(new FieldError("processDefinitionKey", "processDefinitionKey is required"));
        } else if (processDefinitionKey.length() > 255) {
            errors.add(new FieldError("processDefinitionKey", "processDefinitionKey is too long (max 255)"));
        }
        if (targetKind == null) {
            errors.add(new FieldError("targetKind", "targetKind is required"));
        } else if (targetKind == VariablePresetTargetKind.START) {
            if (targetRef != null && !targetRef.isBlank()) {
                errors.add(new FieldError("targetRef", "targetRef must be empty for START"));
            }
        } else {
            if (targetRef == null || targetRef.isBlank()) {
                errors.add(new FieldError("targetRef", "targetRef is required for " + targetKind));
            } else if (targetRef.length() > 255) {
                errors.add(new FieldError("targetRef", "targetRef is too long (max 255)"));
            }
        }
        return errors;
    }

    public static List<FieldError> validateName(String name) {
        List<FieldError> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add(new FieldError("name", "name is required"));
        } else if (name.length() > 255) {
            errors.add(new FieldError("name", "name is too long (max 255)"));
        }
        return errors;
    }

    /**
     * Парсит {@code variables} (JSON-массив {@code {name,type,value}}) и проверяет
     * каждое значение. Возвращает канонический список {@code ProcessVariable}
     * (порядок и содержимое — как в исходнике) либо список ошибок.
     *
     * <p>Результат — пара «переменные + ошибки»: вызывающий решает, бросать ли
     * {@code PRESET_VALIDATION_FAILED}. Разделение нужно, чтобы сервис мог
     * присоединить ошибки привязки/имени к тем же полям, а тест — увидеть обе
     * стороны без try/catch.
     */
    public record VariablesResult(List<ProcessVariable> variables, List<FieldError> errors) {
    }

    public static VariablesResult parseAndValidate(String variablesJson) {
        List<FieldError> errors = new ArrayList<>();
        if (variablesJson == null || variablesJson.isBlank()) {
            errors.add(new FieldError("variables", "variables must be a non-empty JSON array"));
            return new VariablesResult(List.of(), errors);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(variablesJson);
        } catch (Exception e) {
            errors.add(new FieldError("variables", "variables must be valid JSON: " + shortCause(e)));
            return new VariablesResult(List.of(), errors);
        }
        if (!root.isArray()) {
            errors.add(new FieldError("variables", "variables must be a JSON array"));
            return new VariablesResult(List.of(), errors);
        }
        if (root.size() > MAX_VARIABLES) {
            errors.add(new FieldError("variables",
                "too many variables (max " + MAX_VARIABLES + ", got " + root.size() + ")"));
            return new VariablesResult(List.of(), errors);
        }
        List<ProcessVariable> out = new ArrayList<>(root.size());
        Set<String> seenNames = new HashSet<>();
        int index = 0;
        for (JsonNode node : root) {
            String field = "variables[" + index + "]";
            if (!node.isObject()) {
                errors.add(new FieldError(field, "must be an object {name,type,value}"));
                index++;
                continue;
            }
            String name = textOrNull(node.get("name"));
            String typeName = textOrNull(node.get("type"));
            String value = textOrNull(node.get("value"));
            boolean rowOk = true;
            // WO-VT-1 п.4: флаг allowEmptyString — только boolean, только для STRING.
            Boolean allowEmpty = null;
            JsonNode allowNode = node.get("allowEmptyString");
            if (allowNode != null && !allowNode.isNull()) {
                if (!allowNode.isBoolean()) {
                    errors.add(new FieldError(field + ".allowEmptyString",
                        "allowEmptyString must be a boolean"));
                    rowOk = false;
                } else {
                    allowEmpty = allowNode.asBoolean();
                }
            }
            if (name == null || name.isBlank()) {
                errors.add(new FieldError(field + ".name", "name is required"));
                rowOk = false;
            } else if (!seenNames.add(name)) {
                errors.add(new FieldError(field + ".name", "duplicate variable name '" + name + "'"));
                rowOk = false;
            }
            ProcessVariableType type = null;
            if (typeName == null || typeName.isBlank()) {
                errors.add(new FieldError(field + ".type", "type is required"));
                rowOk = false;
            } else {
                try {
                    type = ProcessVariableType.valueOf(typeName);
                } catch (IllegalArgumentException e) {
                    errors.add(new FieldError(field + ".type", "unknown type '" + typeName + "'"));
                    rowOk = false;
                }
            }
            if (value == null) {
                errors.add(new FieldError(field + ".value", "value is required (string)"));
                rowOk = false;
            } else if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
                errors.add(new FieldError(field + ".value", "value exceeds 256 KB"));
                rowOk = false;
            }
            if (rowOk) {
                if (Boolean.TRUE.equals(allowEmpty) && type != ProcessVariableType.STRING) {
                    errors.add(new FieldError(field + ".allowEmptyString",
                        "allowEmptyString is only for STRING"));
                } else {
                    String placeholderError = checkPlaceholders(value);
                    if (placeholderError != null) {
                        errors.add(new FieldError(field + ".value", placeholderError));
                    } else if (!containsPlaceholder(value)) {
                        String typeError = checkTypedValue(type, value);
                        if (typeError != null) {
                            errors.add(new FieldError(field + ".value", typeError));
                        } else {
                            out.add(presetVariable(name, type, value, allowEmpty));
                        }
                    } else {
                        // Корректный плейсхолдер: тип проверит клиент после подстановки.
                        out.add(presetVariable(name, type, value, allowEmpty));
                    }
                }
            }
            index++;
        }
        return new VariablesResult(out, errors);
    }

    /**
     * Сериализует проверенный список обратно в канонический JSON (то, что лежит в БД
     * и что отдаёт export — round-trip import→export побайтово стабилен для
     * эквивалентных документов).
     */
    public static String toJson(List<ProcessVariable> variables) {
        try {
            return MAPPER.writeValueAsString(variables.stream()
                .map(v -> {
                    ProcessVariable c = new ProcessVariable();
                    c.setName(v.getName());
                    c.setType(v.getType());
                    c.setValue(v.getValue());
                    // WO-VT-1 п.4: флаг — часть значения, без него round-trip
                    // «пустая строка как значение» неотличима от «спросить».
                    c.setAllowEmptyString(v.getAllowEmptyString());
                    return c;
                })
                .toList());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize validated variables", e);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String shortCause(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        int nl = m.indexOf('\n');
        String first = nl < 0 ? m : m.substring(0, nl);
        return first.length() > 160 ? first.substring(0, 160) + "…" : first;
    }

    static boolean containsPlaceholder(String value) {
        return value.contains("{{") || value.contains("}}");
    }

    /**
     * Строгая проверка синтаксиса плейсхолдеров: каждая {@code {{…}}} обязана
     * закрыться, имя внутри — из закрытого набора, висячих скобок нет.
     * Возвращает null, если синтаксис корректен (или плейсхолдеров нет вовсе —
     * этот метод зовётся только когда скобки уже найдены).
     */
    static String checkPlaceholders(String value) {
        int pos = 0;
        while (true) {
            int open = value.indexOf("{{", pos);
            int close = value.indexOf("}}", pos);
            if (open < 0 && close < 0) {
                return null;
            }
            if (close >= 0 && (open < 0 || close < open)) {
                return "unbalanced placeholder braces ('}}' without '{{')}}";
            }
            int end = value.indexOf("}}", open + 2);
            if (end < 0) {
                return "unbalanced placeholder braces ('{{' without '}}')";
            }
            String inner = value.substring(open + 2, end).trim();
            if (!PLACEHOLDERS.contains(inner)) {
                return "unknown placeholder '{{" + inner + "}}' (allowed: "
                    + String.join(", ", PLACEHOLDERS.stream().sorted().map(s -> "{{" + s + "}}").toList()) + ")";
            }
            pos = end + 2;
        }
    }

    /**
     * WO-VT-1 п.4: сборка проверенной переменной с сохранением флага
     * {@code allowEmptyString} ({@code true} хранится, {@code false}/null —
     * нормализуется в null, чтобы round-trip не плодил шум).
     */
    private static ProcessVariable presetVariable(
            String name, ProcessVariableType type, String value, Boolean allowEmpty) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        v.setAllowEmptyString(Boolean.TRUE.equals(allowEmpty) ? Boolean.TRUE : null);
        return v;
    }

    private static String checkTypedValue(ProcessVariableType type, String value) {
        // WO-VT-1 п.4: пустое значение для ЛЮБОГО типа = «спросить при запуске»,
        // формат не проверяется.
        if (value.isEmpty()) {
            return null;
        }
        return switch (type) {
            case LONG -> {
                try {
                    Long.parseLong(value);
                    yield null;
                } catch (NumberFormatException e) {
                    yield "not a LONG: '" + abbrev(value) + "'";
                }
            }
            case DOUBLE -> {
                try {
                    double d = Double.parseDouble(value);
                    if (!Double.isFinite(d)) {
                        yield "not a finite DOUBLE: '" + abbrev(value) + "'";
                    }
                    yield null;
                } catch (NumberFormatException e) {
                    yield "not a DOUBLE: '" + abbrev(value) + "'";
                }
            }
            case BOOLEAN -> {
                if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                    yield null;
                }
                yield "not a BOOLEAN (true/false): '" + abbrev(value) + "'";
            }
            case UUID -> {
                try {
                    UUID.fromString(value);
                    yield null;
                } catch (IllegalArgumentException e) {
                    yield "not a UUID: '" + abbrev(value) + "'";
                }
            }
            case JSON -> {
                try {
                    MAPPER.readTree(value);
                    yield null;
                } catch (Exception e) {
                    yield "not valid JSON: " + shortCause(e);
                }
            }
            case STRING -> null;
        };
    }

    private static String abbrev(String value) {
        return value.length() > 60 ? value.substring(0, 60) + "…" : value;
    }
}
