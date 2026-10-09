package com.zorrodev.bpm.engine.handler;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WO-IN-4 раунд 2: общий лексер имён FEEL-переменных (вынесен из приватного
 * {@code MultiInstanceExecutor.extractFeelVariableNames}, WO-PERF-9 B-8).
 *
 * <p>Контракт: идентификаторы, на которые FEEL может сослаться из биндингов —
 * консервативный SUPERSET всех имён, которые движок Camunda FEEL способен
 * резолвить. Привязки — map по точному имени, имена чувствительны к регистру,
 * поэтому любое имя, которое движок может запросить, встречается дословно как
 * токен-идентификатор в исходнике. Нереференсируемые написания (строковые
 * литералы вырезаны; оставшиеся токены комментариев/ключевых слов) дают лишь
 * безвредные лишние чтения — нужную строку не убирают никогда. Точечные пути
 * ({@code a.b}) дают обе части (движок резолвит корень {@code a}). Unicode-буквы
 * включены — FEEL-идентификаторы не ASCII-only.
 */
public final class FeelVariableNames {

    private FeelVariableNames() {
    }

    public static Set<String> extract(String expression) {
        Set<String> names = new LinkedHashSet<>();
        if (expression == null || expression.isBlank()) {
            return names;
        }
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                flush(cur, names);
                inString = true;
                continue;
            }
            if (c == '_' || Character.isLetter(c)) {
                cur.append(c);
                continue;
            }
            if (cur.length() > 0 && Character.isDigit(c)) {
                cur.append(c);
                continue;
            }
            flush(cur, names);
        }
        flush(cur, names);
        return names;
    }

    private static void flush(StringBuilder cur, Set<String> names) {
        if (cur.length() > 0) {
            names.add(cur.toString());
            cur.setLength(0);
        }
    }
}
