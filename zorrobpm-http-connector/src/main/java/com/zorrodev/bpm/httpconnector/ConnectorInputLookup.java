package com.zorrodev.bpm.httpconnector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.exchange.ProcessVariable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WO-C8-58, шаг 1 — чтение входов коннектора в ДВУХ формах.
 *
 * <p>Движок переводит target с точкой во вложенный контекст (Zeebe 8.5: target
 * {@code http.url} → {@code http: {url: …}}), поэтому один и тот же вход может
 * прийти в теле задания двумя способами:
 *
 * <ul>
 *   <li><b>плоский ключ</b> {@code http.url} — действующий контракт; в живой таблице
 *       {@code variables} прод-инстансов ({@code connNative v1}, {@code connCamunda v1})
 *       лежат именно плоские ключи {@code http.*};</li>
 *   <li><b>вложенный путь</b> {@code http} → {@code url} — форма, которую пишет
 *       движок после перехода на вложенные target'ы.</li>
 * </ul>
 *
 * <p><b>Приоритет — вложенная форма.</b> Обоснование:
 * <ol>
 *   <li>вложенную форму пишет ТЕКУЩИЙ запуск движка — она свежее плоского ключа,
 *       оставшегося от предыдущего запуска того же инстанса;</li>
 *   <li>плоский ключ остаётся РАБОЧИМ как fallback: сохранённое состояние живых
 *       инстансов продолжает исполняться без миграции данных;</li>
 *   <li>fallback <b>пофайловый</b>: если вложенный контекст есть, но нужного ключа
 *       в нём нет — берётся плоский ключ; если нет и его — вход отсутствует.</li>
 * </ol>
 *
 * <p>Пофайловый fallback — ровно то, что нужно для СМЕШАННОГО состояния: часть
 * входов пришла вложенной (новый движок), часть — плоской (сохранённое состояние).
 *
 * <p>Класс НЕ меняет контракт воркера: плоская тройка результата
 * ({@code http.status}/{@code http.headers}/{@code http.body}) остаётся, а
 * вложенный контекст {@code http} добавляется рядом (см.
 * {@link HttpConnectorWorker#execute}).
 */
final class ConnectorInputLookup {

    /**
     * Свой инстанс, не бина: в модуле нет бина Jackson 2 (движок отдаёт
     * tools.jackson/Jackson 3) — тот же приём, что у {@link HttpConnectorWorker}.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, ProcessVariable> inputs;
    /** Разобранные вложенные контексты (родитель → объект) — кэш на тело задания. */
    private final Map<String, JsonNode> contexts = new LinkedHashMap<>();

    ConnectorInputLookup(Map<String, ProcessVariable> inputs) {
        this.inputs = inputs == null ? Map.of() : inputs;
    }

    /** Плоская переменная по точному имени (null — нет). */
    ProcessVariable flat(String name) {
        return inputs.get(name);
    }

    /** Все имена переменных тела задания (плоские, как есть). */
    java.util.Set<String> names() {
        return inputs.keySet();
    }

    /** Вложенный контекст по имени родителя (null — нет или не объект). */
    JsonNode context(String parent) {
        if (contexts.containsKey(parent)) {
            return contexts.get(parent);
        }
        ProcessVariable pv = inputs.get(parent);
        JsonNode node = null;
        if (pv != null && pv.getValue() != null && !pv.getValue().isBlank()) {
            try {
                JsonNode parsed = MAPPER.readTree(pv.getValue());
                if (parsed != null && parsed.isObject()) {
                    node = parsed;
                }
            } catch (JsonProcessingException e) {
                node = null;
            }
        }
        contexts.put(parent, node);
        return node;
    }

    /**
     * Лист вложенной формы по точечному имени ({@code http.url} → {@code http}→{@code url}).
     * null — вложенной формы нет (или в ней нет такого ключа).
     */
    JsonNode leaf(String dottedName) {
        int dot = dottedName.indexOf('.');
        if (dot <= 0 || dot == dottedName.length() - 1) {
            return null;
        }
        JsonNode node = context(dottedName.substring(0, dot));
        if (node == null) {
            return null;
        }
        for (String segment : dottedName.substring(dot + 1).split("\\.")) {
            if (!node.isObject()) {
                return null;
            }
            node = node.get(segment);
            if (node == null || node.isMissingNode()) {
                return null;
            }
        }
        return node.isNull() ? null : node;
    }

    /**
     * Разрешённое значение входа: ВЛОЖЕННЫЙ лист (приоритет) либо ПЛОСКАЯ переменная
     * (fallback). Приоритет форм решается ЗДЕСЬ — в одной точке на весь воркер, а не
     * в каждом accessor'е по отдельности (иначе правило разъехалось бы при правке).
     */
    private record Resolved(JsonNode node, ProcessVariable flat) {
        boolean nested() {
            return node != null;
        }
    }

    private Resolved resolve(String name) {
        JsonNode leaf = leaf(name);
        if (leaf != null) {
            return new Resolved(leaf, null);
        }
        return new Resolved(null, flat(name));
    }

    /** Есть ли вход в какой-либо из форм. */
    boolean has(String name) {
        Resolved resolved = resolve(name);
        if (resolved.nested()) {
            return true;
        }
        ProcessVariable pv = resolved.flat();
        return pv != null && pv.getValue() != null && !pv.getValue().isBlank();
    }

    /**
     * Текстовое значение входа: вложенная форма приоритетна, плоский ключ — fallback.
     * null — входа нет (или значение пустое).
     */
    String text(String name) {
        Resolved resolved = resolve(name);
        if (resolved.nested()) {
            return textOf(resolved.node());
        }
        ProcessVariable pv = resolved.flat();
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            return null;
        }
        return pv.getValue().strip();
    }

    /**
     * Тело запроса/ответа — в отличие от {@link #text(String)} НЕ обрезается:
     * плоский ключ отдаётся дословно (побайтовое тело уходит в сокет).
     */
    String body(String name) {
        Resolved resolved = resolve(name);
        if (resolved.nested()) {
            return textOf(resolved.node());
        }
        ProcessVariable pv = resolved.flat();
        return pv == null ? null : pv.getValue();
    }

    /**
     * Карта строк (заголовки/query-параметры). null — значение есть, но это НЕ объект
     * (вызывающий превращает это в детерминированную ошибку конфигурации).
     */
    Map<String, String> stringMap(String name) {
        Resolved resolved = resolve(name);
        if (resolved.nested()) {
            return resolved.node().isObject() ? flatten(resolved.node()) : null;
        }
        ProcessVariable pv = resolved.flat();
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            return Map.of();
        }
        return parseStringMap(pv.getValue());
    }

    /**
     * Таймаут в секундах. Вложенная форма несёт число; плоский ключ — строку с числом
     * (как раньше). Диапазон и сообщения об ошибке — прежние.
     */
    int timeoutSeconds(String name, int def, int max) {
        Resolved resolved = resolve(name);
        if (resolved.nested()) {
            JsonNode node = resolved.node();
            long seconds = node.isNumber() ? node.longValue() : parseSeconds(name, node.asText());
            return checkedSeconds(name, seconds, max);
        }
        ProcessVariable pv = resolved.flat();
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            return def;
        }
        return checkedSeconds(name, parseSeconds(name, pv.getValue().strip()), max);
    }

    private static long parseSeconds(String name, String raw) {
        try {
            return (long) Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new HttpConnectorWorker.HttpConnectorConfigException(
                HttpConnectorWorker.ERR_CONFIG,
                "input '" + name + "' must be a number of seconds (got '" + raw + "')");
        }
    }

    private static int checkedSeconds(String name, long seconds, int max) {
        if (seconds <= 0 || seconds > max) {
            throw new HttpConnectorWorker.HttpConnectorConfigException(HttpConnectorWorker.ERR_CONFIG,
                "input '" + name + "' must be within 1.." + max + " seconds (got " + seconds + ")");
        }
        return (int) seconds;
    }

    /** Текст узла: строка — как есть; число/булево — его запись; объект/массив — компактный JSON. */
    private static String textOf(JsonNode node) {
        if (node.isContainerNode()) {
            return node.toString();
        }
        return node.asText();
    }

    /** JSON-объект → карта строк (значение не-строки — его текстовая запись). */
    private static Map<String, String> flatten(JsonNode object) {
        Map<String, String> result = new LinkedHashMap<>();
        object.fields().forEachRemaining(e -> result.put(e.getKey(),
            e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString()));
        return result;
    }

    /**
     * Разбор JSON-объекта из строки в карту строк. null — не объект (или битый JSON).
     * Общая точка для плоской формы заголовков/query-параметров.
     */
    static Map<String, String> parseStringMap(String value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(value);
            if (!node.isObject()) {
                return null;
            }
            return flatten(node);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
