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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WO-C8-58, шаг 1 — ОБРАТНАЯ СОВМЕСТИМОСТЬ воркеров коннектора.
 *
 * <p>Движок (шаг 2 этой WO) переводит target с точкой во вложенный контекст
 * ({@code http.url} → {@code http: {url: …}}), как это делает живой Zeebe 8.5
 * (замер: {@code docs/analysis/c8-oracle/feel-iomapping-static-nested.js}, пробы
 * {@code tgt-nested*}). Значит в теле задания вход может прийти в ДВУХ формах:
 * плоский ключ {@code http.url} (контракт, зафиксированный в прод-корпусе: у
 * {@code connNative v1} и {@code connCamunda v1} в живой таблице {@code variables}
 * лежат плоские {@code http.*}) и вложенный путь {@code http} → {@code url}.
 *
 * <p>Оба воркера обязаны читать ОБЕ формы. Приоритет — вложенная форма
 * (её пишет движок после перехода), плоский ключ — fallback; fallback
 * ПОФАЙЛОВЫЙ: если вложенный контекст есть, но нужного ключа в нём нет, берётся
 * плоский ключ.
 *
 * <p>Стенд — настоящий локальный {@link HttpServer} и настоящий
 * {@code java.net.http.HttpClient} внутри прода (как в {@link HttpConnectorWorkerTest}):
 * ассерты на то, что сервер РЕАЛЬНО увидел, а не на «вызвалось что-то» (P-67).
 */
class HttpConnectorNestedInputCompatibilityTest {

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> lastRequestHeaders = new ConcurrentHashMap<>();
    private volatile String lastRequestBody;
    private volatile String lastRequestMethod;

    private final ActivityService activityService = mock(ActivityService.class);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            lastRequestMethod = exchange.getRequestMethod();
            exchange.getRequestHeaders().forEach((k, v) -> lastRequestHeaders.put(k.toLowerCase(), String.join(",", v)));
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            if ("/missing".equals(path)) {
                status = 404;
                body = "nope".getBytes(StandardCharsets.UTF_8);
            } else {
                body = "{\"paid\":true}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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

    private HttpConnectorWorker localWorker() {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.setMaxResponseBytes(1024 * 1024);
        props.setMaxRedirects(0);
        return new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);
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

    private static ProcessVariable varByName(List<ProcessVariable> vars, String name) {
        return vars.stream().filter(v -> name.equals(v.getName())).findFirst()
            .orElseThrow(() -> new AssertionError("no variable '" + name + "' in " + vars));
    }

    // ── наш диалект (zorrobpm:http) ─────────────────────────────────────────────

    /**
     * Вложенная форма: движок после перехода пишет входы коннектора как ОДИН
     * вложенный контекст {@code http}. Воркер обязан прочитать url/method/headers/body
     * из него.
     */
    @Test
    void nestedHttpContext_isReadByTheWorker() {
        String nested = "{\"url\":\"" + baseUrl + "\",\"method\":\"POST\","
            + "\"headers\":{\"X-Tenant\":\"t1\"},\"body\":{\"a\":1}}";

        List<ProcessVariable> result = localWorker().handleJob(job(pv("http", nested, "JSON")));

        assertThat(varByName(result, "http.status").getValue()).isEqualTo("200");
        assertThat(lastRequestMethod).isEqualTo("POST");
        assertThat(lastRequestHeaders.get("x-tenant")).isEqualTo("t1");
        assertThat(lastRequestBody).isEqualTo("{\"a\":1}");
        verify(activityService, never()).throwServiceTaskError(any(), any(), any());
    }

    /**
     * СОХРАНЁННОЕ СОСТОЯНИЕ ЖИВЫХ ИНСТАНСОВ: плоские ключи {@code http.*} (прод-корпус
     * {@code connNative v1}) продолжают работать без миграции данных.
     */
    @Test
    void flatHttpKeys_savedLiveInstanceState_stillWork() {
        List<ProcessVariable> result = localWorker().handleJob(job(
            pv("http.url", baseUrl, "STRING"),
            pv("http.method", "POST", "STRING"),
            pv("http.headers", "{\"X-Tenant\":\"t7\"}", "JSON"),
            pv("http.body", "{\"b\":2}", "JSON")));

        assertThat(varByName(result, "http.status").getValue()).isEqualTo("200");
        assertThat(lastRequestMethod).isEqualTo("POST");
        assertThat(lastRequestHeaders.get("x-tenant")).isEqualTo("t7");
        assertThat(lastRequestBody).isEqualTo("{\"b\":2}");
    }

    /**
     * СМЕШАННАЯ форма: часть входов пришла вложенной (новый движок), часть — плоской
     * (сохранённое состояние / чужой маппинг). Fallback ПОФАЙЛОВЫЙ: url берётся из
     * вложенного контекста, body — из плоского ключа.
     */
    @Test
    void mixedFlatAndNestedInputs_areMergedPerKey() {
        List<ProcessVariable> result = localWorker().handleJob(job(
            pv("http", "{\"url\":\"" + baseUrl + "\"}", "JSON"),
            pv("http.method", "POST", "STRING"),
            pv("http.body", "{\"c\":3}", "JSON")));

        assertThat(varByName(result, "http.status").getValue()).isEqualTo("200");
        assertThat(lastRequestMethod).isEqualTo("POST");
        assertThat(lastRequestBody).isEqualTo("{\"c\":3}");
    }

    /**
     * Вложенная форма ПРИОРИТЕТНЕЕ плоской для одного и того же ключа: плоский
     * {@code http.url} — это сохранённое состояние, вложенный — то, что записал
     * текущий запуск движка.
     */
    @Test
    void nestedFormWinsOverFlatKeyForTheSameInput() {
        List<ProcessVariable> result = localWorker().handleJob(job(
            pv("http", "{\"url\":\"" + baseUrl + "\"}", "JSON"),
            pv("http.url", "http://127.0.0.1:1/never", "STRING")));

        assertThat(varByName(result, "http.status").getValue()).isEqualTo("200");
    }

    /**
     * Инлайн-секрет во ВЛОЖЕННОМ контексте {@code http} отвергается так же, как
     * плоский {@code http.token}: иначе после перехода на вложенные target'ы правило
     * «секрет не живёт в модели процесса» обходилось бы простой сменой формы записи.
     */
    @Test
    void nestedHttpContextWithSecretLiteral_isRejected() {
        JobDetailModel model = job(pv("http",
            "{\"url\":\"" + baseUrl + "\",\"token\":\"s3cr3t\"}", "JSON"));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(result).isEmpty();
        verify(activityService).throwServiceTaskError(any(), eq("HTTP_CONNECTOR_CONFIG"), any());
        // ни одного байта в сокет: отказ ДО обращения
        assertThat(lastRequestMethod).isNull();
    }

    /**
     * Ошибочная ветка: вложенный контекст `http` — ЗЕРКАЛО плоских переменных
     * ошибки, значит в нём есть и `error` (иначе «=http.error» не разрешился бы,
     * хотя плоский ключ `http.error` опубликован).
     */
    @Test
    void errorBranch_publishesNestedHttpContextWithErrorLeaf() {
        List<ProcessVariable> result = localWorker().handleJob(job(
            pv("http.url", baseUrl + "/missing", "STRING")));

        ArgumentCaptor<List<com.zorrodev.bpm.contract.model.ProcessVariable>> captor =
            ArgumentCaptor.forClass(List.class);
        verify(activityService).throwServiceTaskError(any(), eq("HTTP_404"), captor.capture());
        com.zorrodev.bpm.contract.model.ProcessVariable nested = captor.getValue().stream()
            .filter(v -> "http".equals(v.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no nested http context in the error variables"));
        assertThat(nested.getValue())
            .contains("\"status\":404")
            .contains("\"error\":\"HTTP 404\"");
        // плоские переменные ошибки на месте — контракт не сломан
        assertThat(result).isEmpty();
        assertThat(captor.getValue().stream().map(v -> v.getName()))
            .contains("http.status", "http.headers", "http.body", "http.error");
    }

    /**
     * Вложенная форма таймаутов: {@code http.connectionTimeout}/{@code http.readTimeout}
     * приходят числами внутри контекста.
     */
    @Test
    void nestedHttpContext_carriesTimeouts() {
        List<ProcessVariable> result = localWorker().handleJob(job(
            pv("http", "{\"url\":\"" + baseUrl + "\",\"connectionTimeout\":7,\"readTimeout\":9}", "JSON")));

        assertThat(varByName(result, "http.status").getValue()).isEqualTo("200");
    }

    /**
     * Шаг 2b: completion-переменные коннектора публикуются ВЛОЖЕННЫМ контекстом
     * {@code http} — тогда {@code =http.status} в output io-mapping начинает
     * разрешаться (сегодня он даёт null: FEEL ищет контекст {@code http}, а ключ плоский).
     * Плоская тройка остаётся (контракт не ломаем) — в этом и состоит совместимость.
     */
    @Test
    void completion_isPublishedAsNestedHttpContext() {
        List<ProcessVariable> result = localWorker().handleJob(job(pv("http.url", baseUrl, "STRING")));

        ProcessVariable nested = varByName(result, "http");
        assertThat(nested.getType()).isEqualTo("JSON");
        assertThat(nested.getValue())
            .contains("\"status\":200")
            .contains("\"body\":{\"paid\":true}");
        // плоская тройка на месте — контракт воркера не изменился
        assertThat(varByName(result, "http.status").getValue()).isEqualTo("200");
        assertThat(varByName(result, "http.body").getValue()).isEqualTo("{\"paid\":true}");
    }

    // ── диалект Camunda (io.camunda:http-json:1) ───────────────────────────────

    private CamundaHttpJsonWorker camundaWorker() {
        return new CamundaHttpJsonWorker(localWorker(), activityService, camundaProperties());
    }

    private HttpConnectorProperties camundaProperties() {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.setMaxResponseBytes(1024 * 1024);
        props.setMaxRedirects(0);
        return props;
    }

    /**
     * Вложенная форма для ПОСЛЕДУЮЩЕГО элемента: {@code authentication.type} —
     * единственный точечный target в шаблоне Camunda ({@code connCamunda v1} на проде),
     * значит после перехода он приходит как вложенный контекст
     * {@code authentication: {type: …}}.
     *
     * <p>Ассерт — на ПЕРЕВОД (тело задания получает {@code http.authType}), а не на
     * «200 без авторизации»: без перевода запрос с {@code noAuth} и без перевода
     * выглядит одинаково.
     */
    @Test
    void nestedCamundaAuthenticationContext_isTranslatedToAuthType() {
        JobDetailModel model = job(
            pv("url", baseUrl, "STRING"),
            pv("method", "GET", "STRING"),
            pv("authentication", "{\"type\":\"noAuth\"}", "JSON"));

        JobDetailModel translated = CamundaHttpJsonWorker.translate(model);

        assertThat(translated.getVariables().get("http.authType").getValue()).isEqualTo("none");

        List<ProcessVariable> result = camundaWorker().handleJob(model);
        assertThat(varByName(result, "http.status").getValue()).isEqualTo("200");
        verify(activityService, never()).throwServiceTaskError(any(), any(), any());
    }

    /**
     * Плоская форма {@code authentication.type} (сохранённое состояние живых
     * инстансов {@code connCamunda v1}) продолжает работать.
     */
    @Test
    void flatCamundaAuthenticationType_stillWorks() {
        JobDetailModel model = job(
            pv("url", baseUrl, "STRING"),
            pv("method", "GET", "STRING"),
            pv("authentication.type", "noAuth", "STRING"));

        List<ProcessVariable> result = camundaWorker().handleJob(model);

        assertThat(varByName(result, "http.status").getValue()).isEqualTo("200");
    }

    /**
     * Инлайн-секрет во вложенном контексте {@code authentication} отвергается так же,
     * как плоский {@code authentication.token}: иначе после перехода на вложенные
     * target'ы правило «секрет не живёт в модели» обходилось бы формой записи.
     *
     * <p>Ассерт — на ПРИЧИНУ отказа (текст в переменных ошибки), а не только на код
     * ошибки: с отключённой проверкой тот же код отказа дал бы ДРУГОЙ текст
     * («http.authType 'bearer' requires http.authRef») — тест обязан различать их,
     * иначе он зелёный по другой причине (P-67).
     */
    @Test
    void nestedCamundaAuthenticationContextWithSecret_isRejected() {
        JobDetailModel model = job(
            pv("url", baseUrl, "STRING"),
            pv("authentication", "{\"type\":\"bearer\",\"token\":\"s3cr3t\"}", "JSON"));

        List<ProcessVariable> result = camundaWorker().handleJob(model);

        assertThat(result).isEmpty();
        ArgumentCaptor<List<com.zorrodev.bpm.contract.model.ProcessVariable>> captor =
            ArgumentCaptor.forClass(List.class);
        verify(activityService).throwServiceTaskError(any(), eq("HTTP_CONNECTOR_CONFIG"), captor.capture());
        String message = captor.getValue().get(0).getValue();
        assertThat(message)
            .as("the refusal must name the offending input")
            .contains("authentication.token")
            .contains("inline secrets");
        // ни одного байта в сокет: отказ ДО обращения
        assertThat(lastRequestMethod).isNull();
    }

    /**
     * Вложенный контекст {@code http} НЕ должен подменять собственные входы
     * Camunda-элемента: переведённые плоские {@code http.*} — приоритетные, иначе
     * чужой контекст из процесса (например, completion предыдущего коннектора) уехал бы
     * в запрос как заголовки.
     */
    @Test
    void nestedHttpContextFromProcess_doesNotOverrideCamundaOwnInputs() {
        JobDetailModel model = job(
            pv("url", baseUrl, "STRING"),
            pv("headers", "{\"X-Tenant\":\"own\"}", "JSON"),
            pv("http", "{\"headers\":{\"X-Tenant\":\"foreign\"},\"url\":\"http://127.0.0.1:1/never\"}", "JSON"));

        List<ProcessVariable> result = camundaWorker().handleJob(model);

        assertThat(varByName(result, "http.status").getValue()).isEqualTo("200");
        assertThat(lastRequestHeaders.get("x-tenant")).isEqualTo("own");
    }
}
