package com.zorrodev.bpm.httpconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * WO-OPS-30 Ф2б — общая мок-цель для измерения HTTP-коннектора.
 *
 * <p>Это ВТОРАЯ реализация той же мок-цели, что и {@code docs/analysis/c8-oracle/mock/http-mock.js}
 * (живая сторона — коннектор Camunda в контейнере). Обе читают ОДНУ таблицу маршрутов из
 * канонического списка кейсов {@code http-connector-cases.json} (секция {@code routes}), поэтому
 * «мок значит разное для живой стороны и для нашей» исключено конструктивно: расхождение двух
 * реализаций проявилось бы как несовпадение наблюдённого ответа с объявленным в таблице, и
 * кейс упал бы, а не «прошёл с другой семантикой».
 *
 * <p>Зачем вторая реализация, а не один контейнер: тест должен идти в CI без внешних
 * контейнеров. В «кампанийном» прогоне (живая сторона против нашей) оба движка бьют в ОДИН
 * контейнер {@code zbpm-cmp-mock} — тогда запрос совпадает байт в байт.
 *
 * <p>Наблюдение «что реально пришло» — журнал запросов ({@link #recorded()}): это единственный
 * канал, который доказывает, что коннектор УШЁЛ в сеть с теми method/path/query/headers/body,
 * а не «вызвал что-то» (P-67: ассерт «объект не null» не доказывает негативное утверждение).
 */
final class F2bMockTarget {

    /** Одна запись журнала запросов. */
    record RecordedRequest(String method, String path, Map<String, String> query,
                            Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final Map<String, JsonNode> routes;
    private final List<RecordedRequest> recorded = Collections.synchronizedList(new ArrayList<>());
    private final ObjectMapper mapper = new ObjectMapper();

    F2bMockTarget(JsonNode routes) throws IOException {
        this.routes = new LinkedHashMap<>();
        routes.fields().forEachRemaining(e -> this.routes.put(e.getKey(), e.getValue()));
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /** База вида {@code http://127.0.0.1:PORT} — подставляется вместо {@code {MOCK}}. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Хост мока — он же попадает в allowlist SSRF-гейта. */
    String host() {
        return "127.0.0.1";
    }

    List<RecordedRequest> recorded() {
        return recorded;
    }

    /** Запросы одного кейса (по заголовку {@code x-f2b-case}). */
    List<RecordedRequest> recordedFor(String caseId) {
        List<RecordedRequest> out = new ArrayList<>();
        for (RecordedRequest r : recorded) {
            if (caseId.equals(r.headers().get("x-f2b-case"))) {
                out.add(r);
            }
        }
        return out;
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT),
            v.isEmpty() ? "" : v.get(0)));
        Map<String, String> query = parseQuery(rawQuery);
        recorded.add(new RecordedRequest(exchange.getRequestMethod(), path, query, headers, body));

        /* Спец-маршрут: рвём соединение — сетевая ошибка, которую клиент не может распарсить. */
        if ("/f2b/close".equals(path)) {
            exchange.close();
            return;
        }

        /* Спец-маршрут: 302 с Location обратно на сам мок. */
        if ("/f2b/redirect".equals(path)) {
            String host = exchange.getRequestHeaders().getFirst("Host");
            exchange.getResponseHeaders().add("Location", "http://" + host + "/f2b/ok");
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }

        /* Спец-маршрут: медленный ответ (задержка из ?ms=). */
        if ("/f2b/slow".equals(path)) {
            long ms = Long.parseLong(query.getOrDefault("ms", "1000"));
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            JsonNode route = routes.get("/f2b/slow");
            send(exchange, route != null ? route : fallbackJson("zorro-f2b"));
            return;
        }

        JsonNode route = routes.get(path);
        if (route == null) {
            byte[] payload = "{\"error\":\"no such mock route\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            return;
        }
        send(exchange, route);
    }

    private JsonNode fallbackJson(String marker) {
        return mapper.createObjectNode().put("probe", marker);
    }

    /** Ответ по маршруту: статус, content-type, тело (либо маркер, либо N байт-заглушки). */
    private void send(HttpExchange exchange, JsonNode route) throws IOException {
        int status = route.path("status").asInt(200);
        String contentType = route.path("contentType").asText("application/json");
        String marker = route.path("bodyMarker").asText("");
        JsonNode extra = route.get("extraHeaders");
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.getResponseHeaders().add("X-F2b-Mock", "zorro-f2b");
        if (extra != null) {
            extra.fields().forEachRemaining(e -> exchange.getResponseHeaders().add(e.getKey(), e.getValue().asText()));
        }
        byte[] payload;
        if (route.has("bodyBytes")) {
            payload = new byte[route.get("bodyBytes").asInt()];
            java.util.Arrays.fill(payload, (byte) 'x');
        } else if (marker.isEmpty()) {
            payload = new byte[0];
        } else if (contentType.toLowerCase(Locale.ROOT).contains("json")) {
            payload = ("{\"probe\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8);
        } else {
            payload = marker.getBytes(StandardCharsets.UTF_8);
        }
        /* 204/304 не принимают тело — для наших маршрутов это не встречается, но пустое тело с 200 — да. */
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
        exchange.close();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(decode(pair), "");
            } else {
                out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** Разбор URI-строки с подстановкой базы — используется тестом при сборке адреса. */
    static URI uri(String base, String path) {
        return URI.create(base + path);
    }
}
