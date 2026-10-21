package com.zorrodev.bpm.httpconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * WO-OPS-30 Ф2б — НАША сторона измерения: поведение нашего HTTP-коннектора против
 * живого коннектора Camunda 8.5.9 на ОДНОМ списке кейсов.
 *
 * <p>Список кейсов — {@code src/test/resources/http-connector-cases.json}, тот же файл,
 * который читает живой оракул ({@code docs/analysis/c8-oracle/http-connector.js}). Кейсы,
 * ожидания и матрица — общие; расходиться могут только НАБЛЮДЕНИЯ.
 *
 * <p>Что именно меряется: боевые классы {@link CamundaHttpJsonWorker} → {@link HttpConnectorWorker}
 * → {@link HttpSsrfGate} с настоящим {@code java.net.http.HttpClient} и настоящей HTTP-целью.
 * Перевод диалекта Camunda в наш делает ТОТ ЖЕ боевой переводчик, который стоит в проде
 * (прецедент P-66: единственная живая точка контроля).
 *
 * <p>Ассерты — на НАБЛЮДАЕМОЕ, а не на «вызвалось что-то» (P-67): код исхода воркера, код
 * BPMN-ошибки, тип/значение переменных ответа и — главное — что ЦЕЛЬ реально увидела
 * (method/path/query/headers/body из журнала мока). Если убрать SSRF-гейт, отказ по
 * HTTP-404 или разбор ответа — тест покраснеет.
 *
 * <p>Мок-цель: по умолчанию — встроенная (тот же контракт маршрутов из списка кейсов),
 * чтобы тест шёл в CI без внешних контейнеров. В «кампанийном» прогоне задаётся
 * {@code -Df2b.mock.base=http://zbpm-cmp-mock:8080} — тогда обе стороны бьют в ОДИН контейнер
 * и запрос совпадает байт в байт.
 */
class HttpConnectorConformanceIntegrationTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode caseList;
    private static F2bMockTarget embeddedMock;
    private static String mockBase;
    private static String mockHost;
    private static final Map<String, Object> OBSERVED = new LinkedHashMap<>();

    /** Секрет для нашей диалекты (http.authRef) — в кампанийном кейсе f2b-ours-authref-basic. */
    private static final String SECRET_BASIC = "{\"type\":\"basic\",\"username\":\"zorro-user\",\"password\":\"zorro-pass\"}";

    @BeforeAll
    static void loadCases() throws Exception {
        try (InputStream in = HttpConnectorConformanceIntegrationTests.class
                .getResourceAsStream("/http-connector-cases.json")) {
            assertThat(in).as("канонический список кейсов Ф2б должен лежать в ресурсах теста").isNotNull();
            caseList = MAPPER.readTree(in);
        }
        String external = System.getProperty("f2b.mock.base");
        if (external != null && !external.isBlank()) {
            mockBase = external.trim();
            mockHost = URI.create(mockBase).getHost();
        } else {
            embeddedMock = new F2bMockTarget(caseList.get("routes"));
            mockBase = embeddedMock.baseUrl();
            mockHost = embeddedMock.host();
        }
    }

    @AfterAll
    static void writeObservations() throws Exception {
        if (embeddedMock != null) {
            embeddedMock.stop();
        }
        Path out = Path.of("target", "http-connector-observed.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(OBSERVED));
    }

    @BeforeEach
    void resetMock() throws Exception {
        if (embeddedMock != null) {
            embeddedMock.recorded().clear();
        } else {
            HttpURLConnection conn = (HttpURLConnection) new URL(mockBase + "/__reset").openConnection();
            conn.setRequestMethod("POST");
            conn.getResponseCode();
            conn.disconnect();
        }
    }

    static Stream<String> cases() {
        List<String> ids = new ArrayList<>();
        caseList.get("cases").forEach(c -> ids.add(c.get("id").asText()));
        return ids.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void case_behaves_as_the_campaign_expects(String caseId) throws Exception {
        JsonNode c = caseById(caseId);
        JsonNode expect = c.get("expect");

        ActivityService activityService = mock(ActivityService.class);
        HttpConnectorProperties props = propertiesFor(c);
        HttpSsrfGate gate = new HttpSsrfGate(props);
        HttpConnectorWorker worker = new HttpConnectorWorker(props, gate, activityService);
        CamundaHttpJsonWorker connector = new CamundaHttpJsonWorker(worker, activityService, props);

        JobDetailModel model = jobModel(c);
        Outcome outcome;
        try {
            List<ProcessVariable> result = connector.handleJob(model);
            outcome = Outcome.success(result);
        } catch (RuntimeException e) {
            outcome = Outcome.transientFailure(e);
        }
        /* Детерминированная ошибка — это throwServiceTaskError на боевом ActivityService. */
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<com.zorrodev.bpm.contract.model.ProcessVariable>> vars =
            ArgumentCaptor.forClass(List.class);
        boolean errorRaised = false;
        try {
            verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()), code.capture(), vars.capture());
            errorRaised = true;
        } catch (AssertionError ignored) {
            errorRaised = false;
        }

        String observedOutcome;
        Map<String, Object> observedVars = new LinkedHashMap<>();
        if (errorRaised) {
            observedOutcome = "bpmnError";
            observedVars.put("errorCode", code.getValue());
            for (com.zorrodev.bpm.contract.model.ProcessVariable v : vars.getValue()) {
                if ("http.error".equals(v.getName())) {
                    observedVars.put("errorMessage", v.getValue());
                }
            }
        } else if (outcome.transientFailure) {
            observedOutcome = "transient";
            observedVars.put("error", outcome.errorMessage);
        } else {
            observedOutcome = "success";
            outcome.variables.forEach(v -> observedVars.put(v.getName(), v.getValue()));
            outcome.variableTypes.forEach((k, v) -> observedVars.put(k + ".type", v));
        }

        List<F2bMockTarget.RecordedRequest> seen = requestsFor(caseId);
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("outcome", observedOutcome);
        observed.putAll(observedVars);
        observed.put("requestsSeen", seen.size());
        if (!seen.isEmpty()) {
            F2bMockTarget.RecordedRequest r = seen.get(0);
            observed.put("request", Map.of("method", r.method(), "path", r.path(),
                "query", r.query(), "body", r.body()));
        }
        OBSERVED.put(caseId, observed);

        /* ── ожидания нашей стороны ─────────────────────────────────────────── */
        JsonNode ours = expect.get("ours");
        assertThat(observedOutcome)
            .as("исход кейса %s (детали: %s)", caseId, observedVars)
            .isEqualTo(ours.get("outcome").asText());
        if (ours.has("errorCode")) {
            assertThat(observedVars.get("errorCode"))
                .as("код BPMN-ошибки кейса %s", caseId)
                .isEqualTo(ours.get("errorCode").asText());
        }
        if (ours.has("httpStatus")) {
            assertThat(observedVars.get("http.status"))
                .as("http.status кейса %s", caseId)
                .isEqualTo(String.valueOf(ours.get("httpStatus").asInt()));
        }
        if (ours.has("httpBodyType")) {
            assertThat(observedVars.get("http.body.type"))
                .as("тип http.body кейса %s", caseId)
                .isEqualTo(ours.get("httpBodyType").asText());
        }
        if (ours.has("httpBodyContains")) {
            String body = String.valueOf(observedVars.get("http.body"));
            assertThat(body).as("тело ответа кейса %s", caseId).contains(ours.get("httpBodyContains").asText());
        }
        if (ours.has("httpHeadersContain")) {
            String headers = String.valueOf(observedVars.get("http.headers"));
            assertThat(headers).as("заголовки ответа кейса %s", caseId)
                .contains(ours.get("httpHeadersContain").asText());
        }
        if (ours.has("httpHeadersDoNotContain")) {
            String headers = String.valueOf(observedVars.get("http.headers"));
            assertThat(headers).as("заголовки ответа кейса %s", caseId)
                .doesNotContain(ours.get("httpHeadersDoNotContain").asText());
        }

        /* ── запрос, который цель реально увидела ───────────────────────────── */
        JsonNode requestExpectation = expect.has("requestOurs")
            ? expect.get("requestOurs")
            : expect.get("request");
        if (requestExpectation != null && requestExpectation.isBoolean()) {
            assertThat(seen).as("кейс %s: запроса быть не должно", caseId).isEmpty();
        } else if (requestExpectation != null) {
            assertThat(seen).as("кейс %s: цель должна была увидеть запрос", caseId).hasSizeGreaterThan(0);
            F2bMockTarget.RecordedRequest r = seen.get(0);
            assertThat(r.method()).as("method кейса %s", caseId)
                .isEqualTo(requestExpectation.path("method").asText());
            assertThat(r.path()).as("path кейса %s", caseId)
                .isEqualTo(requestExpectation.path("path").asText());
            JsonNode query = requestExpectation.get("query");
            if (query != null) {
                query.fields().forEachRemaining(e ->
                    assertThat(r.query().get(e.getKey()))
                        .as("query %s кейса %s", e.getKey(), caseId)
                        .isEqualTo(e.getValue().asText()));
            }
            JsonNode headers = requestExpectation.get("headers");
            if (headers != null) {
                headers.fields().forEachRemaining(e ->
                    assertThat(r.headers().get(e.getKey().toLowerCase(java.util.Locale.ROOT)))
                        .as("заголовок %s кейса %s", e.getKey(), caseId)
                        .isEqualTo(e.getValue().asText()));
            }
            if (requestExpectation.has("authorization")) {
                assertThat(r.headers().get("authorization"))
                    .as("Authorization кейса %s", caseId)
                    .isEqualTo(requestExpectation.get("authorization").asText());
            }
            if (requestExpectation.has("body")) {
                assertThat(r.body()).as("тело запроса кейса %s", caseId)
                    .isEqualTo(requestExpectation.get("body").asText());
            }
        }
    }

    /** Список кейсов не должен молчать о пропущенных фикстурах (P-59: тест, который не запускается). */
    @Test
    void caseList_is_not_empty_and_covers_the_documented_dimensions() {
        assertThat(caseList.get("cases")).isNotEmpty();
        List<String> groups = new ArrayList<>();
        caseList.get("cases").forEach(c -> groups.add(c.get("group").asText()));
        assertThat(groups).contains("input", "auth", "output", "response-class", "timeout", "by-design");
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private record Outcome(boolean transientFailure, String errorMessage, List<ProcessVariable> variables,
                           Map<String, String> variableTypes) {
        static Outcome success(List<ProcessVariable> vars) {
            Map<String, String> types = new LinkedHashMap<>();
            vars.forEach(v -> types.put(v.getName(), v.getType()));
            return new Outcome(false, null, vars, types);
        }

        static Outcome transientFailure(RuntimeException e) {
            return new Outcome(true, e.getClass().getSimpleName() + ": " + e.getMessage(), List.of(), Map.of());
        }
    }

    private JsonNode caseById(String caseId) {
        for (JsonNode c : caseList.get("cases")) {
            if (caseId.equals(c.get("id").asText())) {
                return c;
            }
        }
        throw new IllegalArgumentException("кейс не найден: " + caseId);
    }

    /** Тело задания в диалекте Camunda — ровно то, что получает живой коннектор. */
    private JobDetailModel jobModel(JsonNode c) {
        Map<String, ProcessVariable> variables = new LinkedHashMap<>();
        JsonNode vars = c.get("job").get("variables");
        vars.fields().forEachRemaining(e -> {
            String name = e.getKey();
            JsonNode value = e.getValue();
            ProcessVariable pv = new ProcessVariable();
            pv.setName(name);
            if (value.isObject()) {
                /* FEEL-карта у Camunda -> JSON-переменная у нас (значение — JSON-текст). */
                pv.setType("JSON");
                pv.setValue(value.toString());
            } else {
                pv.setType("STRING");
                pv.setValue(value.asText().replace("{MOCK}", mockBase));
            }
            variables.put(name, pv);
        });
        Map<String, String> taskHeaders = new LinkedHashMap<>();
        JsonNode headers = c.get("job").get("taskHeaders");
        if (headers != null) {
            headers.fields().forEachRemaining(e -> taskHeaders.put(e.getKey(), e.getValue().asText()));
        }
        JobDetailModel model = new JobDetailModel();
        model.setServiceTaskId(UUID.nameUUIDFromBytes(c.get("id").asText().getBytes(StandardCharsets.UTF_8)));
        model.setProcessInstanceId(UUID.randomUUID());
        model.setProcessDefinitionId(UUID.randomUUID());
        model.setServiceTaskKey(c.get("id").asText());
        model.setJob(c.get("job").path("type").asText("io.camunda:http-json:1"));
        model.setVariables(variables);
        model.setTaskHeaders(taskHeaders);
        return model;
    }

    /**
     * Боевые настройки коннектора. Единственное отступление от боевого дефолта —
     * разрешение мок-цели: без него замерял бы SSRF-гейт, а не коннектор (см. эскалацию
     * в канале). Для кейса с приватным хостом разрешение ВЫКЛЮЧЕНО — гейт обязан отказать.
     */
    private HttpConnectorProperties propertiesFor(JsonNode c) {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts(mockHost);
        JsonNode ours = c.get("expect").get("ours");
        boolean allowPrivate = !ours.has("allowPrivateNetworks")
            || ours.get("allowPrivateNetworks").asBoolean(true);
        props.setAllowPrivateNetworks(allowPrivate);
        props.setSecrets(new LinkedHashMap<>(Map.of("f2b-basic", SECRET_BASIC)));
        return props;
    }

    private List<F2bMockTarget.RecordedRequest> requestsFor(String caseId) throws Exception {
        if (embeddedMock != null) {
            return embeddedMock.recordedFor(caseId);
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(mockBase + "/__requests").openConnection();
        try (InputStream in = conn.getInputStream()) {
            JsonNode all = MAPPER.readTree(in);
            List<F2bMockTarget.RecordedRequest> out = new ArrayList<>();
            for (JsonNode r : all) {
                if (caseId.equals(r.path("case").asText())) {
                    Map<String, String> query = new LinkedHashMap<>();
                    r.path("query").fields().forEachRemaining(e -> query.put(e.getKey(), e.getValue().asText()));
                    Map<String, String> headers = new LinkedHashMap<>();
                    r.path("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
                    out.add(new F2bMockTarget.RecordedRequest(r.path("method").asText(),
                        r.path("path").asText(), query, headers, r.path("body").asText()));
                }
            }
            return out;
        } finally {
            conn.disconnect();
        }
    }

    /** Не используется в тесте, но оставлено явным: вывод в файл — часть контракта измерения. */
    @SuppressWarnings("unused")
    private void dump(OutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
