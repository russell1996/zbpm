package com.zorrodev.bpm.httpconnector;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WO-QW-11 (CR-11): секрет из {@code authRef} (query-параметр) не должен попадать
 * в логи и переменные процесса при кривом URL / редиректах / заголовках /
 * транспортных ошибках. Реальный локальный HTTP-сервер, перехват
 * {@code http.error} через mock {@code ActivityService}, перехват логов через
 * logback {@code ListAppender}.
 */
class HttpConnectorSecretLeakTest {

    /** Синтетический секрет с символами, меняющимися при URL-кодировании. */
    private static final String QUERY_CANARY = "K3y w1th %enc& specials=+";
    private static final String ENCODED_CANARY =
        URLEncoder.encode(QUERY_CANARY, StandardCharsets.UTF_8);
    private static final String REDIRECT_CANARY = "REFLECTED-99-canary";
    private static final String HEADER_CANARY = "tok-canary-LEAK";

    private HttpServer server;
    private String baseUrl;
    private volatile String lastRequestMethod;

    private final ActivityService activityService = mock(ActivityService.class);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        server.createContext("/", exchange -> {
            lastRequestMethod = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            if ("/redirect-bad-location".equals(path)) {
                // Кривой Location: ведущий пробел (strip съест) + внутренний пробел
                // (URI.resolve бросит IAE с текстом Location в сообщении).
                status = 302;
                exchange.getResponseHeaders().add("Location",
                    " /evil path?api_key=" + REDIRECT_CANARY);
                body = new byte[0];
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                body = "{\"paid\":true}".getBytes(StandardCharsets.UTF_8);
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

    private HttpConnectorWorker worker(int maxRedirects) {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.setMaxRedirects(maxRedirects);
        props.getSecrets().put("qk",
            "{\"type\":\"apiKey\",\"name\":\"api_key\",\"value\":\"" + QUERY_CANARY + "\",\"in\":\"query\"}");
        props.getSecrets().put("bk",
            // Java "\\n" → JSON "\n"-escape → реальный LF в токене после парсинга
            // (сырой LF в JSON-тексте — невалидный JSON и уведёт в другую ветку).
            "{\"type\":\"bearer\",\"token\":\"line1\\n" + HEADER_CANARY + "\"}");
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

    /** Перехват appender'а на логгере; caller обязан detach в finally. */
    private static ListAppender<ILoggingEvent> attachAppender(Class<?> loggedClass) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggedClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void assertNoCanary(List<ILoggingEvent> events, String... canaries) {
        for (ILoggingEvent event : events) {
            for (String canary : canaries) {
                assertThat(event.getFormattedMessage())
                    .as("лог не должен содержать секрет (%s)", canary)
                    .doesNotContain(canary);
            }
        }
    }

    // --- Критерий 1: кривой URL с секретом в query ---

    @Test
    @SuppressWarnings("unchecked")
    void malformedUrlWithQuerySecret_leaksNothingToLogOrVars() {
        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);
        try {
            JobDetailModel model = job(
                pv("http.url", baseUrl + "/bad path", "STRING"),
                pv("http.authType", "apiKey", "STRING"),
                pv("http.authRef", "qk", "STRING"));

            List<ProcessVariable> result = worker(0).handleJob(model);

            assertThat(result).isEmpty();
            org.mockito.ArgumentCaptor<List> captor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
                eq(HttpConnectorWorker.ERR_CONFIG), captor.capture());
            List<com.zorrodev.bpm.contract.model.ProcessVariable> vars = captor.getValue();
            String httpError = vars.stream().filter(v -> "http.error".equals(v.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("no http.error in " + vars)).getValue();
            // Ни сырой, ни URL-кодированный секрет (URI.create цитирует полный вход).
            assertThat(httpError).doesNotContain(QUERY_CANARY).doesNotContain(ENCODED_CANARY);
            // Base-first (фикс 1): секрет вообще не склеивался — в сообщении нет
            // даже замаскированного остатка; санитайзер тут не при чём.
            assertThat(httpError).doesNotContain("api_key=***");
            assertNoCanary(appender.list, QUERY_CANARY, ENCODED_CANARY);
            // Кривой URL не должен вызывать исходящего запроса вообще.
            assertThat(lastRequestMethod).as("ни одного запроса сервер не видел").isNull();
        } finally {
            ((Logger) LoggerFactory.getLogger(HttpConnectorWorker.class)).detachAppender(appender);
        }
    }

    @Test
    void ssrfGateDebugLog_hidesQuerySecret() {
        Logger gateLogger = (Logger) LoggerFactory.getLogger(HttpSsrfGate.class);
        Level saved = gateLogger.getLevel();
        gateLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = attachAppender(HttpSsrfGate.class);
        try {
            List<ProcessVariable> result = worker(0).handleJob(job(
                pv("http.url", baseUrl + "/ok", "STRING"),
                pv("http.authType", "apiKey", "STRING"),
                pv("http.authRef", "qk", "STRING")));

            assertThat(result).isNotEmpty();
            verify(activityService, never()).throwServiceTaskError(any(), any(), any());
            assertNoCanary(appender.list, QUERY_CANARY, ENCODED_CANARY);
        } finally {
            gateLogger.detachAppender(appender);
            gateLogger.setLevel(saved);
        }
    }

    // --- Критерий 2: редиректы / заголовки / транспорт ---

    @Test
    @SuppressWarnings("unchecked")
    void malformedRedirectLocation_noSecretInDiag() {
        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);
        try {
            JobDetailModel model = job(
                pv("http.url", baseUrl + "/redirect-bad-location", "STRING"),
                pv("http.authType", "apiKey", "STRING"),
                pv("http.authRef", "qk", "STRING"));

            List<ProcessVariable> result = worker(3).handleJob(model);

            assertThat(result).isEmpty();
            org.mockito.ArgumentCaptor<List> captor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
                eq(HttpConnectorWorker.ERR_CONFIG), captor.capture());
            List<com.zorrodev.bpm.contract.model.ProcessVariable> vars = captor.getValue();
            String httpError = vars.stream().filter(v -> "http.error".equals(v.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("no http.error in " + vars)).getValue();
            assertThat(httpError).doesNotContain(REDIRECT_CANARY);
            assertNoCanary(appender.list, REDIRECT_CANARY);
        } finally {
            ((Logger) LoggerFactory.getLogger(HttpConnectorWorker.class)).detachAppender(appender);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void illegalHeaderCharsFromSecret_noSecretInDiag() {
        // Секрет с \n: JDK-валидация заголовка бросает IAE с цитатой значения.
        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);
        try {
            JobDetailModel model = job(
                pv("http.url", baseUrl + "/ok", "STRING"),
                pv("http.authType", "bearer", "STRING"),
                pv("http.authRef", "bk", "STRING"));

            List<ProcessVariable> result = worker(0).handleJob(model);

            assertThat(result).isEmpty();
            org.mockito.ArgumentCaptor<List> captor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
                eq(HttpConnectorWorker.ERR_CONFIG), captor.capture());
            List<com.zorrodev.bpm.contract.model.ProcessVariable> vars = captor.getValue();
            String httpError = vars.stream().filter(v -> "http.error".equals(v.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("no http.error in " + vars)).getValue();
            assertThat(httpError).doesNotContain(HEADER_CANARY);
            assertNoCanary(appender.list, HEADER_CANARY);
        } finally {
            ((Logger) LoggerFactory.getLogger(HttpConnectorWorker.class)).detachAppender(appender);
        }
    }

    @Test
    void transportFailure_noSecretInDiag_transientSemanticsKept() {
        // Мёртвый порт: транзиент (FAILED/ретраи, НЕ BPMN-ошибка), секрет нигде.
        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);
        try {
            JobDetailModel model = job(
                pv("http.url", "http://127.0.0.1:1/unreachable", "STRING"),
                pv("http.authType", "apiKey", "STRING"),
                pv("http.authRef", "qk", "STRING"));

            assertThatThrownBy(() -> worker(0).handleJob(model))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(QUERY_CANARY)
                .hasMessageNotContaining(ENCODED_CANARY);
            verify(activityService, never()).throwServiceTaskError(any(), any(), any());
            assertNoCanary(appender.list, QUERY_CANARY, ENCODED_CANARY);
        } finally {
            ((Logger) LoggerFactory.getLogger(HttpConnectorWorker.class)).detachAppender(appender);
        }
    }

    // --- Санитайзер: таблица враждебных входов (критерии 1–2, unit-уровень) ---

    @Test
    void sanitizeDiag_redactsSecretsAndBoundsSize() {
        // Сырой и кодированный секрет из URI.create-цитаты.
        assertThat(HttpConnectorWorker.sanitizeDiag(
            "Illegal character in path at index 26: http://h/bad path?api_key=" + QUERY_CANARY))
            .doesNotContain(QUERY_CANARY, ENCODED_CANARY)
            .contains("api_key=***");
        assertThat(HttpConnectorWorker.sanitizeDiag(
            "Illegal character in path at index 26: http://h/bad path?api_key=" + ENCODED_CANARY))
            .doesNotContain(QUERY_CANARY, ENCODED_CANARY)
            .contains("api_key=***");
        // Отражённый секрет из кривого Location.
        assertThat(HttpConnectorWorker.sanitizeDiag(
            "Illegal character in path at index 5: /evil path?api_key=" + REDIRECT_CANARY))
            .doesNotContain(REDIRECT_CANARY)
            .contains("api_key=***");
        // Цитата значения заголовка из JDK (включая \n внутри кавычек).
        assertThat(HttpConnectorWorker.sanitizeDiag(
            "invalid header value: \"Bearer line1\n" + HEADER_CANARY + "\""))
            .doesNotContain(HEADER_CANARY)
            .contains("Bearer ***");
        assertThat(HttpConnectorWorker.sanitizeDiag("Basic dXNlcjpwYXNz wires down"))
            .doesNotContain("dXNlcjpwYXNz")
            .contains("Basic ***");
        // Userinfo в URL.
        assertThat(HttpConnectorWorker.sanitizeDiag("only http/https (got scheme 'http://admin:s3cret@h/x')"))
            .doesNotContain("s3cret")
            .contains("://***@");
        // Ограничение размера — санитизация идёт ДО усечения.
        String huge = "x".repeat(10_000) + "?api_key=" + QUERY_CANARY + "y".repeat(10_000);
        String redacted = HttpConnectorWorker.sanitizeDiag(huge);
        assertThat(redacted).doesNotContain(QUERY_CANARY, ENCODED_CANARY);
        assertThat(redacted.length())
            .isLessThanOrEqualTo(HttpConnectorWorker.MAX_DIAG_CHARS + 20);
        // null-безопасность.
        assertThat(HttpConnectorWorker.sanitizeDiag(null)).isEmpty();
    }

    // --- Критерий 3: коннектор выключен по умолчанию ---

    @Test
    void connectorDisabledByDefault_noSecretsNeeded() {
        assertThat(new HttpConnectorProperties().isEnabled()).isFalse();
        JobDetailModel model = job(pv("http.url", baseUrl + "/ok", "STRING"));

        HttpConnectorProperties props = new HttpConnectorProperties();
        HttpConnectorWorker w =
            new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);
        List<ProcessVariable> result = w.handleJob(model);

        assertThat(result).isEmpty();
        verify(activityService).throwServiceTaskError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_DISABLED), any());
    }
}
