package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WO-C8-51 (часть B): объяснение инцидента «выражение коллекции не дало список»
 * для MI {@code inputCollection} и ad-hoc {@code activeElementsCollection}.
 *
 * <p>Инцидент без объяснения у потребителя детектировался как «Whole process
 * frozen» (§2-бис WO): сообщение печатало только значение, поэтому самый частый
 * прод-случай — воркер положил JSON-текст в STRING-переменную — не отличался от
 * «переменная пустая» и от «воркер не заполнил».
 *
 * <p>Контракт формата (префиксы сообщений в вызывающих НЕ меняются — по ним
 * грепают): «<префикс>: variable '<имя>' has type <TYPE>, value: '<превью>'.
 * <подсказка>». Значение превью-обрезается, тип и подсказка — из настоящих
 * данных скоупа, не догадка: переменные ищутся по именам, извлечённым из
 * выражения ({@link FeelVariableNames}), в том же списке, что пошёл в FEEL.
 */
public final class CollectionNotAListDiagnosis {

    /** Превью значения — сообщение инцидента читают глазами в UI, не логи парсера. */
    private static final int MAX_VARIABLE_VALUE_PREVIEW = 200;

    private static final String HINT_STRING_NOT_PARSED =
        "A STRING is not parsed into a collection: the worker output must have type JSON (array).";
    private static final String HINT_VARIABLE_EMPTY =
        "The variable is empty: the worker did not fill the collection.";
    private static final String HINT_LIST_REQUIRED = "A list is required.";

    private CollectionNotAListDiagnosis() {
    }

    /**
     * @param expression     исходное FEEL-выражение ('='-префикс уже снят парсером)
     * @param evaluated      что вернул FEEL-движок (scala Map/List, String, Number, Boolean, null)
     * @param scopeVariables переменные, с которыми выражение исполнилось (тот же список)
     */
    public static String describe(String expression, Object evaluated, List<ProcessVariable> scopeVariables) {
        Set<String> referenced = FeelVariableNames.extract(expression);
        Map<String, ProcessVariable> byName = new HashMap<>();
        if (scopeVariables != null) {
            for (ProcessVariable variable : scopeVariables) {
                if (variable.getName() != null && !byName.containsKey(variable.getName())) {
                    byName.put(variable.getName(), variable);
                }
            }
        }
        List<ProcessVariable> known = new ArrayList<>();
        List<String> notSet = new ArrayList<>();
        for (String name : referenced) {
            ProcessVariable variable = byName.get(name);
            if (variable == null || variable.getValue() == null) {
                notSet.add(name);
            } else {
                known.add(variable);
            }
        }
        return what(evaluated, known, notSet) + " " + hint(evaluated, known);
    }

    private static String what(Object evaluated, List<ProcessVariable> known, List<String> notSet) {
        if (!known.isEmpty()) {
            String variables = known.stream()
                .map(v -> "'" + v.getName() + "' has type " + v.getType() + ", value: '" + preview(v.getValue()) + "'")
                .collect(Collectors.joining("; "));
            String prefix = known.size() == 1 ? "variable " : "variables ";
            String objectNote = known.size() == 1 && known.get(0).getType() == ProcessVariableType.JSON && isObject(evaluated)
                ? " (the JSON value is an object, not an array)" : "";
            return prefix + variables + objectNote;
        }
        if (!notSet.isEmpty()) {
            String names = notSet.stream().map(n -> "'" + n + "'").collect(Collectors.joining(", "));
            return notSet.size() == 1
                ? "variable " + names + " is not set (null)"
                : "variables " + names + " are not set (null)";
        }
        return "the expression evaluated to " + shape(evaluated);
    }

    private static String hint(Object evaluated, List<ProcessVariable> known) {
        boolean stringWithText = known.stream().anyMatch(v -> v.getType() == ProcessVariableType.STRING
            && v.getValue() != null && !v.getValue().isEmpty());
        if (stringWithText) {
            return HINT_STRING_NOT_PARSED;
        }
        if (evaluated == null) {
            return HINT_VARIABLE_EMPTY;
        }
        boolean emptyStringVariable = known.stream().anyMatch(v -> v.getType() == ProcessVariableType.STRING
            && (v.getValue() == null || v.getValue().isEmpty()));
        if (emptyStringVariable) {
            return HINT_VARIABLE_EMPTY;
        }
        return HINT_LIST_REQUIRED;
    }

    private static String shape(Object value) {
        if (value == null) {
            return "null";
        }
        String label;
        if (value instanceof CharSequence) {
            label = "a STRING";
        } else if (value instanceof Number) {
            label = "a NUMBER";
        } else if (value instanceof Boolean) {
            label = "a BOOLEAN";
        } else if (isObject(value)) {
            label = "an OBJECT";
        } else {
            label = value.getClass().getSimpleName();
        }
        return label + " (" + preview(String.valueOf(value)) + ")";
    }

    private static boolean isObject(Object value) {
        return value instanceof java.util.Map || value instanceof scala.collection.Map;
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_VARIABLE_VALUE_PREVIEW) {
            return value;
        }
        return value.substring(0, MAX_VARIABLE_VALUE_PREVIEW) + "…";
    }
}
