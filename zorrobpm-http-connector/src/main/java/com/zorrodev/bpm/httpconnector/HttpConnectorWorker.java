package com.zorrodev.bpm.httpconnector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.handler.JobHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * WO-ENG-31 Фаза 2: built-in воркер HTTP/REST outbound-коннектора (вариант (b)).
 *
 * <p>Обычный {@code SERVICE_TASK} с {@code zeebe:taskDefinition type="zorrobpm:http"} идёт
 * штатным путём движка без единой изменённой строки (outbox → {@code zorrobpm.jobs.zorrobpm:http}
 * → этот бин через {@code HandlerAutoConfiguration}, как внешние воркеры). Конфиг — через
 * input io-mapping с зарезервированными именами {@code http.*} (способ A из Фазы 1):
 * input-mapping уже вычислил FEEL и положил значения в {@code JobDetailModel.variables}.
 *
 * <p>Контракт (решение CTO п.7): результат — плоская тройка
 * {@code http.status} (LONG) / {@code http.headers} (JSON, без {@code set-cookie}) /
 * {@code http.body} (JSON или STRING по Content-Type); выбор в переменные процесса —
 * существующий output io-mapping элемента, имена в коде НЕ хардкодятся сверх этой тройки.
 *
 * <p>Ошибки (решение CTO п.3 — строго): {@code 2xx → SUCCESS}; {@code non-2xx →}
 * {@code throwServiceTaskError(serviceTaskId, "HTTP_<status>", vars)} (boundary ловит или
 * engine сам поднимает {@code Unhandled BPMN error} incident — зеркало ручного REST-пути
 * WO-DIFF-5); детерминированные ошибки (выключен, SSRF-reject, невалидный конфиг,
 * oversize, литеральный секрет) → {@code HTTP_CONNECTOR_<X>} без пустых ретраев;
 * транзиентные (DNS/connect/timeout) → исключение → {@code FAILED → failServiceTask}
 * (ретраи движка, затем incident).
 */
@Slf4j
@Component
public class HttpConnectorWorker implements JobHandler {

    static final String JOB_TYPE = "zorrobpm:http";

    static final String ERR_DISABLED = "HTTP_CONNECTOR_DISABLED";
    static final String ERR_CONFIG = "HTTP_CONNECTOR_CONFIG";

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Set<String> REDIRECT_STATUSES = Set.of("301", "302", "303", "307", "308");

    /** Входные имена, в которых НИКОГДА не должно быть секрета литералом (только authRef-ссылка). */
    private static final Set<String> SECRET_LITERAL_INPUTS = Set.of(
        "http.auth", "http.apikey", "http.api-key", "http.token",
        "http.password", "http.clientsecret", "http.client-secret", "http.authorization");

    /** Заголовки ответа, недоступные маппингу (решение CTO п.4 — фильтруется по умолчанию). */
    private static final Set<String> FILTERED_RESPONSE_HEADERS = Set.of("set-cookie");

    private final HttpConnectorProperties properties;
    private final HttpSsrfGate ssrfGate;
    private final ActivityService activityService;
    // Свой инстанс, не контейнерный бин: engine отдаёт tools.jackson (Jackson 3),
    // а обмену со стартером нужен com.fasterxml (Jackson 2) — бина Jackson 2 в
    // app-контексте нет (ловил полный verify: NoSuchBeanDefinitionException).
    // Прецедент — TokenService/JsonSchemaValidator (свой MAPPER, не бин).
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpConnectorWorker(HttpConnectorProperties properties, HttpSsrfGate ssrfGate,
            ActivityService activityService) {
        this.properties = properties;
        this.ssrfGate = ssrfGate;
        this.activityService = activityService;
    }

    @Override
    public String getJob() {
        return JOB_TYPE;
    }

    @Override
    public List<ProcessVariable> handleJob(JobDetailModel model) {
        try {
            return execute(model);
        } catch (HttpSsrfGate.SsrfRejectedException | HttpConnectorConfigException e) {
            // Детерминированно: ретраить бессмысленно — BPMN-ошибка без пустых ретраев.
            String code = e instanceof HttpSsrfGate.SsrfRejectedException ? ERR_CONFIG : ((HttpConnectorConfigException) e).errorCode;
            log.warn("HTTP connector deterministic error for task {}: {}", model.getServiceTaskId(), e.getMessage());
            activityService.throwServiceTaskError(model.getServiceTaskId(), code,
                List.of(contractErrorVar(e.getMessage())));
            return List.of();
        } catch (IOException | InterruptedException e) {
            // Транзиентно: проброс → FAILED → failServiceTask (ретраи/инцидент — engine).
            // kill воркера между HTTP-эффектом и completion — задокументированное at-least-once
            // (решение CTO п.9): completion-идемпотентность уже есть (correlationId=outboxId),
            // дедуп HTTP-стороны не строим (YAGNI), автору — идемпотентные методы/ключи.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("HTTP connector transient failure: " + e.getMessage(), e);
        }
    }

    private List<ProcessVariable> execute(JobDetailModel model) throws IOException, InterruptedException {
        if (!properties.isEnabled()) {
            throw new HttpConnectorConfigException(ERR_DISABLED,
                "zorrobpm.http-connector.enabled=false — ask the administrator to enable the connector");
        }
        Map<String, ProcessVariable> inputs = model.getVariables() != null ? model.getVariables() : Map.of();
        rejectLiteralSecrets(inputs);

        String url = requiredText(inputs, "http.url");
        String method = textOrDefault(inputs, "http.method", "GET").toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "http.method must be one of " + ALLOWED_METHODS + " (got '" + method + "')");
        }
        Map<String, String> headers = mapOrEmpty(inputs, "http.headers");
        Map<String, String> queryParameters = mapOrEmpty(inputs, "http.queryParameters");
        String body = inputs.containsKey("http.body") ? decodeText(inputs.get("http.body")) : null;
        if (body != null && !BODY_METHODS.contains(method)) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "http.body is only allowed for POST/PUT/PATCH (got method '" + method + "')");
        }
        int connectTimeout = timeoutSeconds(inputs, "http.connectionTimeout",
            properties.getDefaultConnectionTimeoutSeconds(), properties.getMaxConnectionTimeoutSeconds());
        int readTimeout = timeoutSeconds(inputs, "http.readTimeout",
            properties.getDefaultReadTimeoutSeconds(), properties.getMaxReadTimeoutSeconds());

        String authType = textOrDefault(inputs, "http.authType", "none").toLowerCase(Locale.ROOT);
        String authRef = textOrNull(inputs, "http.authRef");
        AuthHeader auth = resolveAuth(authType, authRef);

        URI uri = buildUri(url, queryParameters, auth);
        // SSRF-гейт ДО единого байта в сокет (единая точка, P-66).
        ssrfGate.validate(uri);

        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(connectTimeout))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        HttpResponse<InputStream> response = sendWithRedirects(client, method, uri, headers, body, auth, readTimeout);
        int status = response.statusCode();
        Map<String, String> responseHeaders = filterResponseHeaders(response.headers().map());
        String contentType = response.headers().firstValue("content-type").orElse("");
        String responseBody = readBounded(response.body(), properties.getMaxResponseBytes());
        // Тело могли не дочитать до конца при oversize — поток закрываем, соединение не переиспользуем.
        response.body().close();

        String bodyType = isJsonContent(contentType) ? "JSON" : "STRING";
        if (status >= 200 && status < 300) {
            List<ProcessVariable> result = new ArrayList<>();
            result.add(longVar("http.status", status));
            result.add(jsonVar("http.headers", toJson(responseHeaders)));
            result.add(bodyVar(responseBody, bodyType));
            return result;
        }
        // Решение CTO п.3 — строго: non-2xx без молчаливого success.
        // throwServiceTaskError берёт CONTRACT-модель переменных (engine-API),
        // SUCCESS-путь выше — EXCHANGE-модель (JobHandler SPI): не путать.
        List<com.zorrodev.bpm.contract.model.ProcessVariable> errorVars = new ArrayList<>();
        errorVars.add(contractLongVar("http.status", status));
        errorVars.add(contractJsonVar("http.headers", toJson(responseHeaders)));
        errorVars.add(contractBodyVar(responseBody, bodyType));
        errorVars.add(contractErrorVar("HTTP " + status));
        activityService.throwServiceTaskError(model.getServiceTaskId(), "HTTP_" + status, errorVars);
        return List.of();
    }

    private HttpResponse<InputStream> sendWithRedirects(HttpClient client, String method, URI uri,
            Map<String, String> headers, String body, AuthHeader auth, int readTimeout)
            throws IOException, InterruptedException {
        URI current = uri;
        String currentMethod = method;
        String currentBody = body;
        int maxRedirects = properties.getMaxRedirects();
        for (int hop = 0; ; hop++) {
            HttpRequest request = buildRequest(current, currentMethod, headers, currentBody, auth, readTimeout);
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (!REDIRECT_STATUSES.contains(String.valueOf(status)) || hop >= maxRedirects) {
                if (REDIRECT_STATUSES.contains(String.valueOf(status)) && hop >= maxRedirects) {
                    response.body().close();
                    throw new HttpConnectorConfigException(ERR_CONFIG,
                        "too many redirects (max " + maxRedirects + ") — "
                        + "ask the administrator to raise zorrobpm.http-connector.max-redirects");
                }
                return response;
            }
            String location = response.headers().firstValue("location").orElse(null);
            response.body().close();
            if (location == null || location.isBlank()) {
                throw new HttpConnectorConfigException(ERR_CONFIG,
                    "redirect (" + status + ") without Location header");
            }
            URI next = current.resolve(location.strip());
            // Каждый хоп — через тот же гейт (редирект на internal-хост закрыт здесь).
            ssrfGate.validate(next);
            current = next;
            if (status == 303 || ((status == 301 || status == 302) && currentBody != null)) {
                currentMethod = "GET";
                currentBody = null;
            }
        }
    }

    private static HttpRequest buildRequest(URI uri, String method, Map<String, String> headers,
            String body, AuthHeader auth, int readTimeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(readTimeout));
        headers.forEach(builder::header);
        auth.applyTo(builder);
        if (BODY_METHODS.contains(method)) {
            builder.method(method, body != null
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    private URI buildUri(String url, Map<String, String> queryParameters, AuthHeader auth) {
        String base = url.strip();
        StringBuilder query = new StringBuilder();
        queryParameters.forEach((k, v) -> appendQueryParam(query, k, v));
        if (auth.queryParam != null) {
            appendQueryParam(query, auth.queryParam.name(), auth.queryParam.value());
        }
        if (query.length() == 0) {
            return URI.create(base);
        }
        String separator = base.contains("?") ? (base.endsWith("?") || base.endsWith("&") ? "" : "&") : "?";
        return URI.create(base + separator + query);
    }

    private static void appendQueryParam(StringBuilder query, String name, String value) {
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        query.append('=');
        query.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    /** Секреты — только ссылками через authRef, литералы в inputs — reject (fail-closed). */
    private void rejectLiteralSecrets(Map<String, ProcessVariable> inputs) {
        for (String name : inputs.keySet()) {
            if (name != null && SECRET_LITERAL_INPUTS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new HttpConnectorConfigException(ERR_CONFIG,
                    "secret '" + name + "' must not be passed literally — use http.authRef instead");
            }
        }
        if (inputs.containsKey("http.headers")) {
            Map<String, String> headers = tryParseStringMap(inputs.get("http.headers"));
            if (headers != null) {
                for (String headerName : headers.keySet()) {
                    if ("authorization".equalsIgnoreCase(headerName) || "proxy-authorization".equalsIgnoreCase(headerName)) {
                        throw new HttpConnectorConfigException(ERR_CONFIG,
                            "Authorization header must not be passed literally in http.headers — use http.authRef instead");
                    }
                }
            }
        }
    }

    private AuthHeader resolveAuth(String authType, String authRef) {
        if ("none".equals(authType)) {
            if (authRef != null) {
                throw new HttpConnectorConfigException(ERR_CONFIG,
                    "http.authRef is set but http.authType is 'none'");
            }
            return AuthHeader.none();
        }
        if (authRef == null || authRef.isBlank()) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "http.authType '" + authType + "' requires http.authRef (secret name)");
        }
        String secretJson = properties.getSecrets() != null ? properties.getSecrets().get(authRef) : null;
        if (secretJson == null) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "unknown http.authRef '" + authRef + "' (not in zorrobpm.http-connector.secrets)");
        }
        JsonNode secret;
        try {
            secret = objectMapper.readTree(secretJson);
        } catch (JsonProcessingException e) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "secret '" + authRef + "' is not valid JSON");
        }
        String secretType = secret.path("type").asText("").toLowerCase(Locale.ROOT);
        if (!authType.equals(secretType)) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "http.authType '" + authType + "' does not match secret '" + authRef + "' type '" + secretType + "'");
        }
        // Решение CTO п.5: только none/apiKey/basic/bearer; OAuth — отдельный WO.
        return switch (authType) {
            case "bearer" -> {
                String token = secret.path("token").asText(null);
                if (token == null || token.isBlank()) {
                    throw new HttpConnectorConfigException(ERR_CONFIG,
                        "secret '" + authRef + "' (bearer) must contain a non-empty 'token'");
                }
                yield AuthHeader.bearer(token);
            }
            case "basic" -> {
                String username = secret.path("username").asText(null);
                String password = secret.path("password").asText(null);
                if (username == null || password == null) {
                    throw new HttpConnectorConfigException(ERR_CONFIG,
                        "secret '" + authRef + "' (basic) must contain 'username' and 'password'");
                }
                yield AuthHeader.basic(username, password);
            }
            case "apikey" -> {
                String name = secret.path("name").asText(null);
                String value = secret.path("value").asText(null);
                String in = secret.path("in").asText("header").toLowerCase(Locale.ROOT);
                if (name == null || name.isBlank() || value == null) {
                    throw new HttpConnectorConfigException(ERR_CONFIG,
                        "secret '" + authRef + "' (apiKey) must contain 'name' and 'value'");
                }
                if (!in.equals("header") && !in.equals("query")) {
                    throw new HttpConnectorConfigException(ERR_CONFIG,
                        "secret '" + authRef + "' (apiKey) 'in' must be 'header' or 'query'");
                }
                yield AuthHeader.apiKey(name, value, in);
            }
            default -> throw new HttpConnectorConfigException(ERR_CONFIG,
                "unsupported http.authType '" + authType + "' (supported: none, apiKey, basic, bearer; "
                + "OAuth — отдельный WO)");
        };
    }

    private record AuthHeader(String headerName, String headerValue, QueryParam queryParam) {
        static AuthHeader none() {
            return new AuthHeader(null, null, null);
        }
        static AuthHeader bearer(String token) {
            return new AuthHeader("Authorization", "Bearer " + token, null);
        }
        static AuthHeader basic(String username, String password) {
            String encoded = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            return new AuthHeader("Authorization", "Basic " + encoded, null);
        }
        static AuthHeader apiKey(String name, String value, String in) {
            return "query".equals(in)
                ? new AuthHeader(null, null, new QueryParam(name, value))
                : new AuthHeader(name, value, null);
        }
        void applyTo(HttpRequest.Builder builder) {
            if (headerName != null) {
                builder.header(headerName, headerValue);
            }
        }
    }

    private record QueryParam(String name, String value) {
    }

    private Map<String, String> filterResponseHeaders(Map<String, List<String>> raw) {
        // TreeMap CASE_INSENSITIVE — set-cookie ловится в любом регистре.
        Map<String, List<String>> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byName.putAll(raw);
        Map<String, String> result = new LinkedHashMap<>();
        byName.forEach((name, values) -> {
            if (FILTERED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            result.put(name.toLowerCase(Locale.ROOT), String.join(", ", values));
        });
        return result;
    }

    private String readBounded(InputStream body, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = body.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                // Решение CTO п.4: oversize → reject с BPMN-ошибкой, не silent-truncate.
                throw new HttpConnectorConfigException(ERR_CONFIG,
                    "response body exceeds zorrobpm.http-connector.max-response-bytes (" + maxBytes + ")");
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isJsonContent(String contentType) {
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains("json");
    }

    private String requiredText(Map<String, ProcessVariable> inputs, String name) {
        ProcessVariable pv = inputs.get(name);
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            throw new HttpConnectorConfigException(ERR_CONFIG, "missing required input '" + name + "'");
        }
        return pv.getValue().strip();
    }

    private String textOrDefault(Map<String, ProcessVariable> inputs, String name, String def) {
        String v = textOrNull(inputs, name);
        return v != null ? v : def;
    }

    private static String textOrNull(Map<String, ProcessVariable> inputs, String name) {
        ProcessVariable pv = inputs.get(name);
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            return null;
        }
        return pv.getValue().strip();
    }

    private static String decodeText(ProcessVariable pv) {
        return pv.getValue() != null ? pv.getValue() : "";
    }

    private Map<String, String> mapOrEmpty(Map<String, ProcessVariable> inputs, String name) {
        ProcessVariable pv = inputs.get(name);
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed = tryParseStringMap(pv);
        if (parsed == null) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "input '" + name + "' must be a JSON object of string to string (got type '" + pv.getType() + "')");
        }
        return parsed;
    }

    private Map<String, String> tryParseStringMap(ProcessVariable pv) {
        return tryParseStringMapValue(pv.getValue());
    }

    private Map<String, String> tryParseStringMapValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isObject()) {
                return null;
            }
            Map<String, String> result = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> result.put(e.getKey(),
                e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString()));
            return result;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private int timeoutSeconds(Map<String, ProcessVariable> inputs, String name, int def, int max) {
        ProcessVariable pv = inputs.get(name);
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            return def;
        }
        long seconds;
        try {
            seconds = (long) Double.parseDouble(pv.getValue().strip());
        } catch (NumberFormatException e) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "input '" + name + "' must be a number of seconds (got '" + pv.getValue() + "')");
        }
        if (seconds <= 0 || seconds > max) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "input '" + name + "' must be within 1.." + max + " seconds (got " + seconds + ")");
        }
        return (int) seconds;
    }

    private static ProcessVariable longVar(String name, long value) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(Long.toString(value));
        pv.setType("LONG");
        return pv;
    }

    private ProcessVariable jsonVar(String name, String json) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(json);
        pv.setType("JSON");
        return pv;
    }

    private ProcessVariable bodyVar(String body, String type) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName("http.body");
        pv.setValue(body);
        pv.setType(type);
        return pv;
    }

    private static ProcessVariable errorVar(String message) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName("http.error");
        pv.setValue(message);
        pv.setType("STRING");
        return pv;
    }

    /** CONTRACT-модель (engine-API throwServiceTaskError) — отдельно от EXCHANGE-модели выше. */
    private static com.zorrodev.bpm.contract.model.ProcessVariable contractVar(
            String name, String value, com.zorrodev.bpm.contract.model.ProcessVariableType type) {
        com.zorrodev.bpm.contract.model.ProcessVariable pv =
            new com.zorrodev.bpm.contract.model.ProcessVariable();
        pv.setName(name);
        pv.setValue(value);
        pv.setType(type);
        return pv;
    }

    private static com.zorrodev.bpm.contract.model.ProcessVariable contractLongVar(String name, long value) {
        return contractVar(name, Long.toString(value),
            com.zorrodev.bpm.contract.model.ProcessVariableType.LONG);
    }

    private static com.zorrodev.bpm.contract.model.ProcessVariable contractJsonVar(String name, String json) {
        return contractVar(name, json, com.zorrodev.bpm.contract.model.ProcessVariableType.JSON);
    }

    private static com.zorrodev.bpm.contract.model.ProcessVariable contractBodyVar(String body, String bodyType) {
        return contractVar("http.body", body,
            "JSON".equals(bodyType)
                ? com.zorrodev.bpm.contract.model.ProcessVariableType.JSON
                : com.zorrodev.bpm.contract.model.ProcessVariableType.STRING);
    }

    private static com.zorrodev.bpm.contract.model.ProcessVariable contractErrorVar(String message) {
        return contractVar("http.error", message,
            com.zorrodev.bpm.contract.model.ProcessVariableType.STRING);
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize response headers", e);
        }
    }

    /** Детерминированная ошибка коннектора — несёт код BPMN-ошибки (HTTP_CONNECTOR_*). */
    static class HttpConnectorConfigException extends RuntimeException {
        final String errorCode;
        HttpConnectorConfigException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }
}
