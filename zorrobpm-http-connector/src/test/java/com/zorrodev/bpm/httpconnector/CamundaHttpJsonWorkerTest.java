package com.zorrodev.bpm.httpconnector;

import com.sun.net.httpserver.HttpServer;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-ENG-32: движок понимает камундовский REST-коннектор ({@code io.camunda:http-json:1})
 * как есть — элемент, смоделированный штатным темплейтом Camunda Modeler
 * ({@code io.camunda.connectors.HttpJson.v2}), исполняется без переписывания.
 *
 * <p>Стенд тот же, что у {@link HttpConnectorWorkerTest}: НАСТОЯЩИЙ локальный
 * {@link HttpServer} и настоящий {@code java.net.http.HttpClient} внутри прода. Ассерты
 * на то, что сервер реально увидел (метод/URL/тело/заголовки), а не на «вызвалось что-то»:
 * иначе тест был бы зелёным и без перевода (P-67).
 *
 * <p>Все входы в тесте — ровно те имена, которые пишет в BPMN настоящий шаблон Camunda
 * (проверено по файлу `camunda/connectors@main`
 * `connectors/http/rest/element-templates/http-json-connector.json`, version 18),
 * а первый тест воспроизводит ЖИВОЙ элемент пользователя `srvRouteStart`
 * (WO `governance/workorders/WO-ENG-32-camunda-http-json-compat.md`, стр. 28-47).
 */
class CamundaHttpJsonWorkerTest {

    /** NEW5-06: сколько байт успевает отдать медленный сервер (1 байт/с). */
    private static final int SLOW_BODY_BYTES = 12;

    private static final String LIVE_ROUTE_BODY = "{\"statusId\":2,\"started\":\"2026-09-30T09:45:31\"}";

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> lastRequestHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile String lastRequestBody;
    private volatile String lastRequestQuery;
    private volatile String lastRequestMethod;

    private final ActivityService activityService = mock(ActivityService.class);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            lastRequestMethod = exchange.getRequestMethod();
            lastRequestQuery = exchange.getRequestURI().getRawQuery();
            exchange.getRequestHeaders().forEach((k, v) -> lastRequestHeaders.put(k.toLowerCase(), String.join(",", v)));
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            switch (path) {
                case "/echo" -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    body = lastRequestBody.getBytes(StandardCharsets.UTF_8);
                }
                case "/missing" -> {
                    status = 404;
                    body = "nope".getBytes(StandardCharsets.UTF_8);
                }
                case "/slow-body" -> {
                    // Заголовки уходят сразу, тело — по 1 байту в секунду.
                    exchange.getResponseHeaders().add("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        for (int i = 0; i < SLOW_BODY_BYTES; i++) {
                            os.write('x');
                            os.flush();
                            Thread.sleep(1000);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                default -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                }
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ── стенды ────────────────────────────────────────────────────────────────

    private HttpConnectorProperties props(String allowedHosts, boolean allowPrivate) {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts(allowedHosts);
        props.setAllowPrivateNetworks(allowPrivate);
        return props;
    }

    /** Локальный стенд: loopback allowlisted, приватные разрешены (как в ENG-31 тестах). */
    private CamundaHttpJsonWorker localWorker() {
        return localWorker(props("127.0.0.1", true));
    }

    private CamundaHttpJsonWorker localWorker(HttpConnectorProperties props) {
        return new CamundaHttpJsonWorker(
            new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService), activityService, props);
    }

    /** Прод-подобный deny-all: пустой allowlist, приватные запрещены. */
    private CamundaHttpJsonWorker denyAllWorker() {
        return localWorker(props("", false));
    }

    // ──Camunda-диалект: значения переменных движок кладёт строками ─────────────

    /** Вход io-mapping в форме, которую видит воркер: тип выведен по значению (STRING/LONG/JSON/BOOLEAN). */
    private static ProcessVariable input(String name, String value) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(value);
        pv.setType(inferredType(value));
        return pv;
    }

    private static String inferredType(String value) {
        if (value == null) {
            return "STRING";
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return "BOOLEAN";
        }
        if (value.strip().matches("-?\\d+(\\.\\d+)?")) {
            return "LONG";
        }
        if (value.strip().startsWith("{")) {
            return "JSON";
        }
        return "STRING";
    }

    private static JobDetailModel camundaJob(Map<String, String> inputs, Map<String, String> taskHeaders) {
        JobDetailModel model = new JobDetailModel();
        model.setServiceTaskId(UUID.randomUUID());
        model.setProcessInstanceId(UUID.randomUUID());
        model.setJob(CamundaHttpJsonWorker.JOB_TYPE);
        Map<String, ProcessVariable> vars = new HashMap<>();
        inputs.forEach((k, v) -> vars.put(k, input(k, v)));
        model.setVariables(vars);
        model.setTaskHeaders(taskHeaders.isEmpty() ? null : new LinkedHashMap<>(taskHeaders));
        return model;
    }

    private static JobDetailModel camundaJob(Map<String, String> inputs) {
        return camundaJob(inputs, Map.of());
    }

    private static Map<String, String> camundaInputs(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    /**
     * Ни один заголовок, который сервер реально получил, не содержит секрет.
     *
     * <p>Проверяет не «Authorization пуст», а «секрета нет ни в одном заголовке» — иначе
     * правка, которая пристроила литерал в нестандартный заголовок ({@code X-Api-Token}),
     * прошла бы незамеченной. Само значение секрета в ассерт не подставляется осмысленно:
     * сравнение идёт по факту наличия в записанных заголовках.
     */
    private void assertServerSawNoSecret(String secret) {
        lastRequestHeaders.forEach((name, value) -> assertThat(value)
            .as("сервер не должен получать заголовок '%s' со значением секрета", name)
            .doesNotContain(secret));
    }

    /** Детерминированный отказ с кодом {@code HTTP_CONNECTOR_CONFIG}. */
    private void assertConfigError(UUID serviceTaskId) {
        verify(activityService).throwServiceTaskError(eq(serviceTaskId), eq("HTTP_CONNECTOR_CONFIG"), any());
    }

    /**
     * Отказ НАЗЫВАЕТ конкретный отвергнутый вход в {@code http.error} — то, что читает
     * автор процесса в инциденте.
     *
     * <p>Отдельный ассерт, а не часть «секрет не ушёл»: без него тесты на «ничего не
     * вылетело» зелёные и по ложной причине (отбитый инлайн-токен даёт ту же BPMN-ошибку,
     * что и отсутствие {@code http.url}, — P-67), а охранник можно снять незаметно.
     * Благодаря этому ассерту снятие охранника роняет ИМЕННО его.
     */
    private void assertConfigErrorNaming(UUID serviceTaskId, String expectedInput) {
        verify(activityService).throwServiceTaskError(eq(serviceTaskId), eq("HTTP_CONNECTOR_CONFIG"),
            argThat(vars -> vars != null && vars.stream().anyMatch(v -> "http.error".equals(v.getName())
                && v.getValue() != null && v.getValue().contains(expectedInput))));
    }

    private static String varValue(List<ProcessVariable> vars, String name) {
        return vars.stream().filter(v -> name.equals(v.getName())).findFirst()
            .orElseThrow(() -> new AssertionError("no variable '" + name + "' in " + vars)).getValue();
    }

    // ═══ Критерий 1: Camunda-элемент исполняется как есть ══════════════════════

    /**
     * Критерий 1 на ЖИВОМ элементе пользователя (`srvRouteStart`, PATCH на approval-api,
     * `authentication.type=noAuth`, `storeResponse=false`, `resultExpression` в taskHeaders):
     * HTTP-запрос уходит на тот же URL тем же методом с тем же телом — без единой правки
     * элемента. Ни один вход не переписан на {@code http.*}.
     *
     * <p>POF: без слоя перевода падает на {@code missing required input 'http.url'} —
     * смоук-тест самого факта обработки не годится, поэтому ассертим то, что УВИДЕЛ сервер.
     */
    @Test
    void criterion1_liveRouteStartElement_executesUnchanged() {
        JobDetailModel model = camundaJob(
            camundaInputs(
                "authentication.type", "noAuth",
                "method", "PATCH",
                "url", baseUrl + "/echo",
                "headers", "{\"Content-Type\":\"application/json\"}",
                "body", LIVE_ROUTE_BODY,
                "storeResponse", "false",
                "ignoreNullValues", "false",
                "followRedirects", "false",
                "connectionTimeoutInSeconds", "20",
                "readTimeoutInSeconds", "20",
                "documentReturnFormat.choice", "JSON"),
            camundaTaskHeaders());

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(lastRequestMethod).isEqualTo("PATCH");
        assertThat(lastRequestBody).isEqualTo(LIVE_ROUTE_BODY);
        assertThat(lastRequestHeaders.get("content-type")).isEqualTo("application/json");
        assertThat(varValue(result, "http.body")).isEqualTo(LIVE_ROUTE_BODY);
        verify(activityService, never()).throwServiceTaskError(any(), any(), any());
    }

    /** Полный набор taskHeaders, который пишет применяемый шаблон Camunda. */
    private static Map<String, String> camundaTaskHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("elementTemplateId", "io.camunda.connectors.HttpJson.v2");
        headers.put("elementTemplateVersion", "18");
        headers.put("resultExpression", "={ myResponseBody: response.body }");
        headers.put("retryBackoff", "PT30S");
        return headers;
    }

    /** Критерий 1: queryParameters и заголовки доезжают (входы Camunda, не `http.*`). */
    @Test
    void criterion1_queryParametersAndHeaders_mapped() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "method", "GET",
            "queryParameters", "{\"page\":\"2\"}",
            "headers", "{\"X-Tenant\":\"t1\"}"));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(lastRequestQuery).isEqualTo("page=2");
        assertThat(lastRequestHeaders.get("x-tenant")).isEqualTo("t1");
    }

    /**
     * Критерий 1: таймауты Camunda реально управляют обменом, а не просто «приняты».
     * Медленный сервер (1 байт/с) + {@code readTimeoutInSeconds=2} ⇒ обмен обрывается
     * по дедлайну. Отличающее значение между «перевод работает» и «перевода нет»:
     * 200 за 12 секунд против HttpTimeoutException за ~2с.
     */
    @Test
    void criterion1_camundaReadTimeout_reallyBoundsTheExchange() {
        HttpConnectorProperties props = props("127.0.0.1", true);
        props.setDefaultReadTimeoutSeconds(20);
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/slow-body",
            "readTimeoutInSeconds", "2"));

        assertThatThrownBy(() -> localWorker(props).handleJob(model))
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseInstanceOf(java.net.http.HttpTimeoutException.class);
    }

    /** Критерий 1: `authentication.type=bearer` + авторский input `http.authRef` — секрет с сервера. */
    @Test
    void criterion1_bearerViaAuthorAddedHttpAuthRef_usesServerSideSecret() {
        HttpConnectorProperties props = props("127.0.0.1", true);
        props.getSecrets().put("approval", "{\"type\":\"bearer\",\"token\":\"srv-side-token\"}");
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "authentication.type", "bearer",
            "http.authRef", "approval"));

        List<ProcessVariable> result = localWorker(props).handleJob(model);

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(lastRequestHeaders.get("authorization")).isEqualTo("Bearer srv-side-token");
    }

    /**
     * `resultExpression` в taskHeaders НЕ исполняется (второй движок выражений мы не
     * заводим) — но и не ломает элемент: ответ приходит в штатную тройку.
     *
     * <p>Почему не reject: шаблон Camunda пишет это свойство С НЕПУСТЫМ значением по
     * умолчанию, то есть оно есть у каждого применённого шаблона (и в живом элементе
     * пользователя). Reject здесь означал бы, что «применить шаблон» не работает никогда.
     */
    @Test
    void criterion1_resultExpressionHeader_isIgnoredNotRejected() {
        JobDetailModel model = camundaJob(
            camundaInputs("url", baseUrl + "/ok", "method", "GET"),
            camundaTaskHeaders());

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(varValue(result, "http.body")).isEqualTo("{\"ok\":true}");
    }

    // ═══ Критерий 2: переведённый путь проходит тот же SSRF-гейт ═══════════════

    /**
     * Критерий 2: URL на loopback при пустом allowlist — детерминированная
     * {@code HTTP_CONNECTOR_CONFIG} и НИ БАЙТА в сокет (сервер не получил запроса).
     *
     * <p>POF мутацией прода: снять {@code ssrfGate.validate(uri)} — тест краснеет
     * (запрос уходит, {@code requestCount} = 1).
     */
    @Test
    void criterion2_camundaUrlOnPrivateRangeWithoutAllowlist_rejectedBeforeAnyByte() {
        JobDetailModel model = camundaJob(camundaInputs("url", baseUrl + "/ok", "method", "GET"));

        List<ProcessVariable> result = denyAllWorker().handleJob(model);

        assertThat(result).isEmpty();
        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()), eq("HTTP_CONNECTOR_CONFIG"), any());
        assertThat(requestCount.get())
            .as("SSRF-гейт обязан отсечь ДО сокета — ни байта на сервер")
            .isZero();
    }

    /** Критерий 2: хост не в allowlist — тоже до сокета (гейт тот же самый). */
    @Test
    void criterion2_camundaUrlOnHostOutsideAllowlist_rejectedBeforeAnyByte() {
        CamundaHttpJsonWorker worker = localWorker(props("example.com", false));
        JobDetailModel model = camundaJob(camundaInputs("url", baseUrl + "/ok"));

        worker.handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()), eq("HTTP_CONNECTOR_CONFIG"), any());
        assertThat(requestCount.get()).as("хост вне allowlist — ни байта в сокет").isZero();
    }

    // ═══ Критерий 3: инлайн-секреты в Camunda-входах ══════════════════════════

    /** Критерий 3: литеральный bearer-токен — reject, сервер не вызван, секрет наружу не уходит. */
    @Test
    void criterion3_bearerTokenLiteral_noRequestLeavesTheWorker() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "authentication.type", "bearer",
            "authentication.token", "leaked-token-value"));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(result).isEmpty();
        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).as("инлайн-секрет не должен дойти до сокета").isZero();
        assertThat(lastRequestHeaders.get("authorization")).isNull();
        assertServerSawNoSecret("leaked-token-value");
    }

    /** Критерий 3: литеральный пароль basic. */
    @Test
    void criterion3_basicPasswordLiteral_noRequestLeavesTheWorker() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "authentication.type", "basic",
            "authentication.username", "svc",
            "authentication.password", "hunter2"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    /** Критерий 3: литеральный apiKey. */
    @Test
    void criterion3_apiKeyValueLiteral_noRequestLeavesTheWorker() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "authentication.type", "apiKey",
            "authentication.name", "X-Api-Key",
            "authentication.apiKeyLocation", "headers",
            "authentication.value", "literal-api-key"));

        localWorker().handleJob(model);

                assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    /** Критерий 3: клиентский секрет OAuth (Camunda-подобная авторизация не поддержана). */
    @Test
    void criterion3_oauthClientSecretLiteral_noRequestLeavesTheWorker() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "authentication.type", "oauth-client-credentials-flow",
            "authentication.oauthTokenEndpoint", "https://login.example.com/token",
            "authentication.clientId", "client",
            "authentication.clientSecret", "literal-client-secret"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    /** Критерий 3: mutual TLS (clientTls.*) — не поддержан, reject, а не «молча без TLS». */
    @Test
    void criterion3_clientTlsMaterial_noRequestLeavesTheWorker() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "clientTls.clientCertificate", "-----BEGIN CERTIFICATE-----",
            "clientTls.clientPrivateKey", "-----BEGIN PRIVATE KEY-----"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    /**
     * Критерий 3, вторая линия: литеральный {@code Authorization} внутри входа
     * {@code headers} отклоняется тем же охранником, что и на нашем диалекте (тот же
     * единственный {@code rejectLiteralSecrets} вызывается на переведённом теле).
     */
    @Test
    void criterion3_literalAuthorizationHeader_noRequestLeavesTheWorker() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "headers", "{\"Authorization\":\"Bearer literal-in-header\"}"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    /**
     * Критерий 3, НЕГАТИВНЫЙ КОНТРОЛЬ: забытый во входах {@code authentication.token}
     * отвергается даже когда авторизация объявлена как {@code noAuth}.
     *
     * <p>Этот тест, а не «bearer + токен», доказывает охранник по-настоящему: с типом
     * {@code noAuth} делегату нечего отвергать, поэтому снятый охранник приводит к
     * РЕАЛЬНОМУ HTTP-вызову без токена (requestCount = 1), и тест краснеет. На bearer-тесте
     * снятие охранника не видно: тот же отказ даёт проверка «authType требует http.authRef»
     * в делегате, то есть тест зелёный по ложной причине (обнаружено мутацией M-2a/M-2b).
     */
    @Test
    void criterion3_strayAuthenticationInput_rejectedEvenWhenAuthIsNone() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "method", "GET",
            "authentication.type", "noAuth",
            "authentication.token", "leaked-token-value"));

        localWorker().handleJob(model);

        // Порядок ассертов НЕ случайный: сначала сетевой факт, потом код/сообщение.
        // Так мутация «скопировать литерал в нестандартный заголовок» роняет ИМЕНА скан
        // секрета, а не ассерт на текст ошибки (иначе оба мутанга краснели на одном и том же
        // месте, и доказательство «секрет не уехал» осталось бы непроверенным).
        assertServerSawNoSecret("leaked-token-value");
        assertThat(requestCount.get()).as("забытый вход аутентификации — не «молча проигнорировать»").isZero();
        assertConfigErrorNaming(model.getServiceTaskId(), "authentication.token");
    }

    /**
     * B-1 red-team: вход конфигурации авторизации по УРОВНЮ camunda-шаблона (`Configuration`
     * с `configurationTemplate: io.camunda.connectors:rest-authentication:1`) обязан быть
     * отвергнут. Пока этот префикс стоял в списке без единого теста, его снятие было
     * fail-OPEN: у элемента с настроенной «credentials» нет входа `authentication.type`,
     * значит `http.authType` не ставится, делегат берёт дефолт `none` — и НЕАУТЕНТИФИЦИРОВАННЫЙ
     * запрос уходит на allowlisted-хост, молча и без ошибки.
     *
     * <p>Здесь это отвергается РАНЬШЕ тихо-аутентифицированного поведения и раньше
     * проверки `enabled`, так что тест ловит именно снятие охранника.
     */
    @Test
    void criterion3_camundaCredentialConfigurationInput_rejectedAndServerNeverCalled() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "method", "GET",
            "authenticationConfiguration", "my-rest-credential"));

        localWorker().handleJob(model);

        assertServerSawNoSecret("my-rest-credential");
        assertThat(requestCount.get()).as("credential-конфигурация не должна уйти в неаутентифицированный вызов").isZero();
        assertConfigErrorNaming(model.getServiceTaskId(), "authenticationConfiguration");
    }

    /**
     * B-2 red-team: нетронутое обязательное FEEL-поле Camunda Modeler персистит как
     * «пустое FEEL» — `value="="`. Такой элемент (шаблон применён, поля ошибки/результата
     * не заполнялись) обязан исполняться, а не отвергаться: иначе «просто применить шаблон»
     * не работает никогда, то есть ровно то, против чего заводилась критерий 1.
     */
    @Test
    void criterion1_untouchedFeelHeadersSerializedAsBareEquals_executeNotRejected() {
        JobDetailModel model = camundaJob(
            camundaInputs("url", baseUrl + "/ok", "method", "GET"),
            camundaTaskHeadersWith("errorExpression", "=", "resultVariable", "="));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(requestCount.get()).isEqualTo(1);
    }

    /** Обратная сторона B-2: настоящее выражение после маркера — авторское ожидание, режется. */
    @Test
    void errorExpressionHeaderWithRealExpression_stillRejected() {
        JobDetailModel model = camundaJob(
            camundaInputs("url", baseUrl + "/ok"),
            camundaTaskHeadersWith("errorExpression", "= error.response.statusCode = 404"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    /** L-1 red-team: отказ не обходится опечаткой в регистре ключа заголовка. */
    @Test
    void resultVariableHeaderKeyCaseInsensitive_stillRejected() {
        JobDetailModel model = camundaJob(
            camundaInputs("url", baseUrl + "/ok"),
            camundaTaskHeadersWith("ResultVariable", "myResponseBody"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    /**
     * Оформительское #5 red-team: выключенный коннектор отвечает на ЛЮБУЮ задачу
     * {@code HTTP_CONNECTOR_DISABLED} — иначе автор OAuth-элемента читал бы «у меня с
     * авторизацией что-то», вместо «это выключено администратором».
     */
    @Test
    void connectorDisabled_camundaElementReportsDisabledNotConfigError() {
        HttpConnectorProperties props = props("127.0.0.1", true);
        props.setEnabled(false);
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "authentication.type", "oauth-client-credentials-flow"));

        localWorker(props).handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()), eq("HTTP_CONNECTOR_DISABLED"), any());
        assertThat(requestCount.get()).isZero();
    }

    /**
     * Оформительское #2 red-team: переведённая переменная носит НАШЕ имя, а не camunda-имя
     * под нашим ключом (ключ карты и {@code ProcessVariable.name} не должны расходиться).
     */
    @Test
    void translatedVariables_carryOurNamesNotCamundaNames() {
        HttpConnectorWorker delegate = mock(HttpConnectorWorker.class);
        when(delegate.handleJob(any())).thenReturn(List.of());
        new CamundaHttpJsonWorker(delegate, activityService, props("127.0.0.1", true))
            .handleJob(camundaJob(camundaInputs("url", baseUrl + "/ok", "method", "PATCH", "body", "{\"a\":1}")));

        ArgumentCaptor<JobDetailModel> forwarded = ArgumentCaptor.forClass(JobDetailModel.class);
        verify(delegate).handleJob(forwarded.capture());
        Map<String, ProcessVariable> vars = forwarded.getValue().getVariables();
        assertThat(vars.get("http.url").getName()).isEqualTo("http.url");
        assertThat(vars.get("http.method").getName()).isEqualTo("http.method");
        assertThat(vars.get("http.body").getName()).isEqualTo("http.body");
    }

    /**
     * Критерий 3: отказ называет КОНКРЕТНЫЙ отвергнутый вход, и это детерминированно.
     *
     * <p>Элемент как в шаблоне: несколько запрещённых входов сразу. Порядок обхода входа
     * отсортирован, поэтому в {@code http.error} попадает всегда один и тот же
     * {@code authentication.apiKeyLocation} — иначе автору в инциденте показывалось бы
     * случайное имя (наш RED это поймал: без сортировки имя прыгало между прогонами).
     */
    @Test
    void criterion3_rejectionNamesTheOffendingInput_deterministically() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "authentication.type", "apiKey",
            "authentication.name", "X-Api-Key",
            "authentication.apiKeyLocation", "headers",
            "authentication.value", "literal-api-key"));

        localWorker().handleJob(model);

        assertConfigErrorNaming(model.getServiceTaskId(), "authentication.apiKeyLocation");
    }

    /**
     * Критерий 3, третья проверка: переведённое тело, которое уходит дальше по конвейеру,
     * не содержит НИ ОДНОГО Camunda-входа {@code authentication.*} и вообще ни одного
     * переведённого имени — только наш диалект {@code http.*}. Тест не про «делегата
     * позвали», а про СОСТАВ переданного тела: перевод строит новое тело, а не дописывает
     * в старое.
     */
    @Test
    void criterion3_forwardedJobBody_carriesNoCamundaInputs() {
        HttpConnectorProperties props = props("127.0.0.1", true);
        props.getSecrets().put("approval", "{\"type\":\"bearer\",\"token\":\"srv-side-token\"}");
        HttpConnectorWorker delegate = mock(HttpConnectorWorker.class);
        when(delegate.handleJob(any())).thenReturn(List.of());
        CamundaHttpJsonWorker worker = new CamundaHttpJsonWorker(delegate, activityService, props("127.0.0.1", true));
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "method", "GET",
            "authentication.type", "bearer",
            "http.authRef", "approval"));

        worker.handleJob(model);

        ArgumentCaptor<JobDetailModel> forwarded = ArgumentCaptor.forClass(JobDetailModel.class);
        verify(delegate).handleJob(forwarded.capture());
        Map<String, ProcessVariable> vars = forwarded.getValue().getVariables();
        assertThat(vars.keySet())
            .as("в переведённом теле не должно остаться ни одного Camunda-входа")
            .doesNotContain("url", "method", "authentication.type")
            .noneMatch(key -> key.startsWith("authentication."));
        assertThat(vars.keySet())
            .as("а наш диалект — есть, включая авторский input http.authRef")
            .contains("http.url", "http.method", "http.authType", "http.authRef");
        assertThat(vars.get("http.authType").getValue()).isEqualTo("bearer");
        assertThat(vars.get("http.authRef").getValue()).isEqualTo("approval");
    }

    // ═══ Критерий 4: non-2xx строго через BPMN-ошибку ═════════════════════════

    /** Критерий 4: 404 без boundary → {@code HTTP_404} + инцидент, НЕ SUCCESS-данные. */
    @Test
    void criterion4_non2xxWithoutBoundary_throwsBpmnErrorHttp404() {
        JobDetailModel model = camundaJob(camundaInputs("url", baseUrl + "/missing", "method", "GET"));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(result).as("SUCCESS-переменных при non-2xx быть не должно").isEmpty();
        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()), eq("HTTP_404"), any());
    }

    // ═══ Критерий 5: OAuth — явный reject ════════════════════════════════════

    /** Критерий 5: {@code oauth-client-credentials-flow} — явный отказ «отдельный WO». */
    @Test
    void criterion5_oauthClientCredentials_noSilentNoneAndNoRequest() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "authentication.type", "oauth-client-credentials-flow"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).as("молчаливый 'none' вместо OAuth недопустим").isZero();
    }

    /** Критерий 5: второй oauth-тип шаблона — тот же явный отказ. */
    @Test
    void criterion5_oauthRefreshToken_noSilentNoneAndNoRequest() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok",
            "authentication.type", "oauth-refresh-token"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    // ═══ Неподдерживаемые входы/заголовки: явный отказ, а не молчание ═══════════

    /**
     * Вопрос 2 (решение в эскалации от 2026-10-06): {@code followRedirects=true} НЕ
     * расширяет админский потолок редиректов из BPMN — детерминированный отказ с
     * подсказкой. {@code false} (дефолт шаблона, приходит всегда) — обычный путь.
     */
    @Test
    void followRedirectsTrue_noRequestLeavesTheWorker() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok", "followRedirects", "true"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void followRedirectsFalse_passesThrough() {
        JobDetailModel model = camundaJob(camundaInputs(
            "url", baseUrl + "/ok", "method", "GET", "followRedirects", "false"));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(varValue(result, "http.status")).isEqualTo("200");
    }

    /**
     * Вопрос 3 (решение в эскалации): {@code resultVariable} пуст по умолчанию и
     * пишется, только когда автор сам вписал имя переменной — то есть это явное
     * ожидание, которое мы выполнить не можем. Молчать нельзя (вниз уйдёт {@code null}).
     */
    @Test
    void resultVariableHeader_noRequestLeavesTheWorker() {
        JobDetailModel model = camundaJob(
            camundaInputs("url", baseUrl + "/ok"),
            camundaTaskHeadersWith("resultVariable", "myResponseBody"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    /** Пустой {@code resultVariable} (шаблон пишет пустую строку) — обычный путь. */
    @Test
    void resultVariableHeaderEmpty_notRejected() {
        JobDetailModel model = camundaJob(
            camundaInputs("url", baseUrl + "/ok"),
            camundaTaskHeadersWith("resultVariable", ""));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(varValue(result, "http.status")).isEqualTo("200");
    }

    /** {@code errorExpression} — обещание логики ошибок, которую мы не исполняем: явный отказ. */
    @Test
    void errorExpressionHeader_noRequestLeavesTheWorker() {
        JobDetailModel model = camundaJob(
            camundaInputs("url", baseUrl + "/ok"),
            camundaTaskHeadersWith("errorExpression", "= error.response.statusCode = 404"));

        localWorker().handleJob(model);

        assertConfigError(model.getServiceTaskId());
        assertThat(requestCount.get()).isZero();
    }

    private static Map<String, String> camundaTaskHeadersWith(String key, String value) {
        return camundaTaskHeadersWith(key, value, null, null);
    }

    /** Парный вариант: два заголовка сразу (нужно для «оба как пустое FEEL»). */
    private static Map<String, String> camundaTaskHeadersWith(String k1, String v1, String k2, String v2) {
        Map<String, String> headers = camundaTaskHeaders();
        headers.put(k1, v1);
        if (k2 != null) {
            headers.put(k2, v2);
        }
        return headers;
    }

    // ═══ Алиас-бин: отдельный job-type, наш диалект не слома ══════════════════

    /**
     * Вопрос 4 (решение в эскалации): поддержан ровно {@code io.camunda:http-json:1} —
     * ровно тот type, который пишет шаблон {@code io.camunda.connectors.HttpJson.v2}.
     * Очередь строится из {@code getJob()} (HandlerAutoConfiguration), поэтому алиас —
     * отдельный бин, а не параметр. Плюс регресс: наш собственный {@code zorrobpm:http}
     * не перекрыт (иначе «перевод сломал наш диалект» — обратная половина совместимости).
     */
    @Test
    void aliasJobType_isHttpJsonV1_andDoesNotShadowNativeType() {
        HttpConnectorProperties props = props("127.0.0.1", true);
        CamundaHttpJsonWorker alias = localWorker(props);
        HttpConnectorWorker nativeWorker = new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);

        assertThat(alias.getJob()).isEqualTo("io.camunda:http-json:1");
        assertThat(nativeWorker.getJob()).isEqualTo("zorrobpm:http");
        assertThat(alias.getJob()).isNotEqualTo(nativeWorker.getJob());

        // Наш собственный диалект продолжает работать через того же делегата.
        JobDetailModel nativeJob = new JobDetailModel();
        nativeJob.setServiceTaskId(UUID.randomUUID());
        nativeJob.setJob(nativeWorker.getJob());
        Map<String, ProcessVariable> vars = new LinkedHashMap<>();
        vars.put("http.url", input("http.url", baseUrl + "/ok"));
        nativeJob.setVariables(vars);
        assertThat(varValue(alias.handleJob(nativeJob), "http.status")).isEqualTo("200");
    }

    /** Бин-алиас помечен `@Component`: иначе стартер воркеров его не поднимет. */
    @Test
    void aliasWorker_isASpringComponentBean() {
        assertThat(CamundaHttpJsonWorker.class.getAnnotation(org.springframework.stereotype.Component.class))
            .as("CamundaHttpJsonWorker должен быть @Component — HandlerAutoConfiguration берёт бины JobHandler")
            .isNotNull();
    }
}