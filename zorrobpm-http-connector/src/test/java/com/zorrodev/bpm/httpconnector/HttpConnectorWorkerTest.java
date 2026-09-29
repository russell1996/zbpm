package com.zorrodev.bpm.httpconnector;

import com.sun.net.httpserver.HttpServer;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WO-ENG-31 Фаза 2: воркер против РЕАЛЬНОГО локального HTTP-сервера
 * (JDK {@code HttpServer}, настоящий {@code HttpClient} — не mock сокета).
 *
 * <p>Сервер слушает loopback, поэтому тесты идут с {@code allow-private-networks=true}
 * (dev-подобная конфигурация тестового стенда, не прод-дефолт — прод-дефолт
 * {@code false} доказан отдельно в {@link HttpSsrfGateTest}).
 */
class HttpConnectorWorkerTest {

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> lastRequestHeaders = new ConcurrentHashMap<>();
    private volatile String lastRequestBody;
    private volatile String lastRequestQuery;
    private volatile String lastRequestMethod;

    private final ActivityService activityService = mock(ActivityService.class);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        server.createContext("/", exchange -> {
            lastRequestMethod = exchange.getRequestMethod();
            lastRequestQuery = exchange.getRequestURI().getRawQuery();
            exchange.getRequestHeaders().forEach((k, v) -> lastRequestHeaders.put(k.toLowerCase(), String.join(",", v)));
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            switch (path) {
                case "/ok" -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.getResponseHeaders().add("Set-Cookie", "session=secret; Path=/");
                    exchange.getResponseHeaders().add("X-Custom", "yes");
                    body = "{\"paid\":true}".getBytes(StandardCharsets.UTF_8);
                }
                case "/text" -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/plain");
                    body = "plain-ok".getBytes(StandardCharsets.UTF_8);
                }
                case "/missing" -> {
                    status = 404;
                    body = "nope".getBytes(StandardCharsets.UTF_8);
                }
                case "/fail" -> {
                    status = 503;
                    body = "busy".getBytes(StandardCharsets.UTF_8);
                }
                case "/echo" -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    body = lastRequestBody.getBytes(StandardCharsets.UTF_8);
                }
                case "/huge" -> {
                    body = new byte[2 * 1024 * 1024];
                }
                case "/redirect" -> {
                    status = 302;
                    exchange.getResponseHeaders().add("Location", "/ok");
                    body = new byte[0];
                }
                case "/redirect-evil" -> {
                    status = 302;
                    exchange.getResponseHeaders().add("Location", "http://169.254.169.254/");
                    body = new byte[0];
                }
                default -> {
                    status = 404;
                    body = "unknown".getBytes(StandardCharsets.UTF_8);
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

    private HttpConnectorWorker worker(boolean enabled, String allowedHosts, boolean allowPrivate,
            long maxResponseBytes, int maxRedirects) {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(enabled);
        props.setAllowedHosts(allowedHosts);
        props.setAllowPrivateNetworks(allowPrivate);
        props.setMaxResponseBytes(maxResponseBytes);
        props.setMaxRedirects(maxRedirects);
        return new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);
    }

    private HttpConnectorWorker localWorker() {
        return worker(true, "127.0.0.1", true, 1024 * 1024, 0);
    }

    private static ProcessVariable pv(String name, String value, String type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }

    private static JobDetailModel job(ProcessVariable... vars) {
        JobDetailModel model = new JobDetailModel();
        model.setServiceTaskId(UUID.randomUUID());
        model.setProcessInstanceId(UUID.randomUUID());
        model.setJob("zorrobpm:http");
        Map<String, ProcessVariable> map = new HashMap<>();
        for (ProcessVariable v : vars) {
            map.put(v.getName(), v);
        }
        model.setVariables(map);
        return model;
    }

    private static String varValue(List<ProcessVariable> vars, String name) {
        return vars.stream().filter(v -> name.equals(v.getName())).findFirst()
            .orElseThrow(() -> new AssertionError("no variable '" + name + "' in " + vars)).getValue();
    }

    // --- SUCCESS-путь ---

    @Test
    void get_ok_returnsFlatTriple() {
        List<ProcessVariable> result = localWorker().handleJob(job(pv("http.url", baseUrl + "/ok", "STRING")));

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(varValue(result, "http.body")).isEqualTo("{\"paid\":true}");
        assertThat(result.stream().filter(v -> "http.body".equals(v.getName())).findFirst().orElseThrow().getType())
            .isEqualTo("JSON");
        verify(activityService, never()).throwServiceTaskError(any(), any(), any());
    }

    @Test
    void responseHeaders_availableToMapping_butSetCookieFiltered() {
        List<ProcessVariable> result = localWorker().handleJob(job(pv("http.url", baseUrl + "/ok", "STRING")));

        String headersJson = varValue(result, "http.headers");
        // Решение CTO п.4: Set-Cookie фильтруется по умолчанию.
        assertThat(headersJson).doesNotContain("session=secret").doesNotContain("set-cookie");
        assertThat(headersJson).contains("x-custom");
    }

    @Test
    void method_headers_query_body_postEcho() {
        List<ProcessVariable> result = localWorker().handleJob(job(
            pv("http.url", baseUrl + "/echo", "STRING"),
            pv("http.method", "POST", "STRING"),
            pv("http.headers", "{\"X-Tenant\":\"t1\"}", "JSON"),
            pv("http.queryParameters", "{\"page\":\"2\"}", "JSON"),
            pv("http.body", "{\"a\":1}", "JSON")));

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(varValue(result, "http.body")).isEqualTo("{\"a\":1}");
        assertThat(lastRequestHeaders.get("x-tenant")).isEqualTo("t1");
        assertThat(lastRequestQuery).isEqualTo("page=2");
        assertThat(lastRequestMethod).isEqualTo("POST");
    }

    @Test
    void bearerAuth_fromSecretRef_sentAsHeader() {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.getSecrets().put("svc", "{\"type\":\"bearer\",\"token\":\"tok123\"}");
        HttpConnectorWorker worker =
            new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);

        worker.handleJob(job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.authType", "bearer", "STRING"),
            pv("http.authRef", "svc", "STRING")));

        assertThat(lastRequestHeaders.get("authorization")).isEqualTo("Bearer tok123");
    }

    @Test
    void apiKeyAuth_inQuery_appendedToUrl() {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.getSecrets().put("k", "{\"type\":\"apiKey\",\"name\":\"api_key\",\"value\":\"v1\",\"in\":\"query\"}");
        HttpConnectorWorker worker =
            new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);

        worker.handleJob(job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.authType", "apiKey", "STRING"),
            pv("http.authRef", "k", "STRING")));

        assertThat(lastRequestQuery).isEqualTo("api_key=v1");
    }

    // --- non-2xx строго через BPMN-ошибку ---

    @Test
    void notFound_throwsBpmnError_http404_withVars() {
        JobDetailModel model = job(pv("http.url", baseUrl + "/missing", "STRING"));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(result).isEmpty();
        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()), eq("HTTP_404"), any());
    }

    @Test
    void serviceUnavailable_throwsBpmnError_http503() {
        JobDetailModel model = job(pv("http.url", baseUrl + "/fail", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()), eq("HTTP_503"), any());
    }

    // --- детерминированные ошибки (без пустых ретраев) ---

    @Test
    void disabled_throwsHttpConnectorDisabled() {
        JobDetailModel model = job(pv("http.url", baseUrl + "/ok", "STRING"));

        List<ProcessVariable> result =
            worker(false, "127.0.0.1", true, 1024 * 1024, 0).handleJob(model);

        assertThat(result).isEmpty();
        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_DISABLED), any());
    }

    @Test
    void denyAll_whenAllowlistEmpty() {
        JobDetailModel model = job(pv("http.url", baseUrl + "/ok", "STRING"));

        worker(true, "", true, 1024 * 1024, 0).handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void missingUrl_rejected() {
        JobDetailModel model = job(pv("http.method", "GET", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void badMethod_rejected() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.method", "TRACE", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void getWithBody_rejected() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.method", "GET", "STRING"),
            pv("http.body", "x", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void literalSecret_rejected_failClosed() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.auth", "{\"type\":\"bearer\",\"token\":\"leak\"}", "JSON"));

        localWorker().handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void literalAuthorizationHeader_rejected() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.headers", "{\"Authorization\":\"Bearer leak\"}", "JSON"));

        localWorker().handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void unknownAuthRef_rejected() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.authType", "bearer", "STRING"),
            pv("http.authRef", "nope", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void oauthAuthType_rejected_separateWO() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.authType", "oauth-client-credentials", "STRING"),
            pv("http.authRef", "o", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void timeoutBeyondCap_rejected() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.readTimeout", "9999", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void oversizeBody_rejected_notTruncated() {
        // Решение CTO п.4: oversize → reject с BPMN-ошибкой, не silent-truncate.
        JobDetailModel model = job(pv("http.url", baseUrl + "/huge", "STRING"));

        worker(true, "127.0.0.1", true, 1024, 0).handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void redirect_notFollowed_byDefault() {
        // maxRedirects=0 (дефолт, зеркало followRedirects=false): 302 сверх лимита → BPMN-ошибка.
        JobDetailModel model = job(pv("http.url", baseUrl + "/redirect", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void redirect_followed_whenOptedIn_eachHopValidated() {
        List<ProcessVariable> result =
            worker(true, "127.0.0.1", true, 1024 * 1024, 3)
                .handleJob(job(pv("http.url", baseUrl + "/redirect", "STRING")));

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        verify(activityService, never()).throwServiceTaskError(any(), any(), any());
    }

    @Test
    void redirect_toPrivateHost_rejected_evenWhenOptedIn() {
        // Редирект на 169.254.169.254: хоп обязан пройти гейт (allowlist — только 127.0.0.1).
        JobDetailModel model = job(pv("http.url", baseUrl + "/redirect-evil", "STRING"));

        worker(true, "127.0.0.1", true, 1024 * 1024, 3).handleJob(model);

        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void transientFailure_connectionRefused_goesFailed_notBpmnError() {
        // Ничего не слушает: транзиент → исключение → FAILED-путь (ретраи engine), НЕ BPMN-ошибка.
        JobDetailModel model = job(pv("http.url", "http://127.0.0.1:1/", "STRING"));

        assertThatThrownBy(() -> localWorker().handleJob(model))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("transient");
        verify(activityService, never()).throwServiceTaskError(any(), any(), any());
    }

    @Test
    void malformedUrlWithInnerSpace_deterministicError_noRetries() {
        // Red-team (G-H находка 1): внутренний пробел — URI.create бросает unchecked IAE,
        // обязан уйти в BPMN-ошибку ERR_CONFIG, а не в FAILED-ретраи.
        JobDetailModel model = job(pv("http.url", "http://exa mple.com/", "STRING"));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(result).isEmpty();
        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any());
    }

    @Test
    void timeoutEdgeValues_rejected_deterministically() {
        // Red-team (G-H находка 2): 0/отрицательное/NaN/Infinity — детерминированный reject.
        for (String bad : new String[]{"0", "-5", "NaN", "Infinity"}) {
            JobDetailModel model = job(
                pv("http.url", baseUrl + "/ok", "STRING"),
                pv("http.readTimeout", bad, "STRING"));
            localWorker().handleJob(model);
            verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
                eq(HttpConnectorWorker.ERR_CONFIG), any());
        }
    }

    @Test
    void jobType_isReservedHttp() {
        assertThat(localWorker().getJob()).isEqualTo("zorrobpm:http");
    }
}
