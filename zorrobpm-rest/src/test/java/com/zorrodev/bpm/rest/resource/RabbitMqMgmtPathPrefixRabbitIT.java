package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-QW-12 (ДОПОЛНЕНИЕ CTO): провижининг брокерских учёток воркеров против
 * Management API БРОКЕРА С ПРЕФИКСОМ {@code management.path_prefix = /rabbitmq}.
 *
 * <p><b>Почему этот тест вообще нужен.</b> Настройка узловая: она делает ВСЕ пути
 * management-HTTP доступными только под префиксом. Живой прогон на
 * {@code rabbitmq:4.1-management-alpine} с нашим conf.d (без env, чистый контейнер):
 * <pre>
 * GET    /api/overview            (без префикса) -> 404   (не 301: редиректа тут нет)
 * PUT    /api/users/x             (без префикса) -> 404   (аккаунт НЕ создан)
 * DELETE /api/users/x             (без префикса) -> 404
 * GET    /rabbitmq/api/overview   (с префиксом)  -> 200
 * PUT    /rabbitmq/api/users/x    (с префиксом)  -> 201   (аккаунт создан, тег zbpm-managed)
 * PUT    /rabbitmq/api/permissions/%2F/x        -> 201
 * </pre>
 * То есть {@code RABBITMQ_MGMT_BASE_URL} без {@code /rabbitmq} — это не «минус
 * косметика», а 404 на каждом вызове: {@code getBrokerUser} прочитал бы 404 как «нет
 * аккаунта», а {@code PUT /api/users} ответил бы 404 → {@code brokerUnavailable} →
 * fail-closed 503 на выдаче и отзыве брокерских учёток. Это и есть блокер, который
 * закрывает этот тест: он гоняет НАСТОЯЩИЙ путь (REST → {@code RabbitMqProvisioningService}
 * → Management API живого брокера), а не переписанную в теле теста копию запроса.
 *
 * <p><b>Доказательство, что пароль доехал, а не «запись нашлась».</b> Успешный HTTP
 * 200 сам по себе доказывает мало (P-67): тест дополнительно
 * <ol>
 *   <li>читает аккаунт напрямую у брокера и проверяет метку {@code zbpm-managed}
 *       (значит, запись создана Именно нашим кодом, а не кем-то ещё);</li>
 *   <li>открывает НАСТОЯЩЕЕ AMQP-соединение выданными кредами — брокер принимает логин
 *       только если hash, который мы ему отдали, он посчитал сам. Не-HTTP-доказательство:
 *       Management API мог бы вернуть 201 и не записать ничего.</li>
 * </ol>
 *
 * <p>Обратная сторона (fail-closed при потерянном префиксе) — отдельным классом
 * {@link RabbitMqMgmtPathPrefixMissingRabbitIT}: тот же стек с ТОЙ ЖЕ продовой
 * полосой, но base-url без префикса.
 *
 * <p>Прогон (P-23: env, не -D; тот же развод, что у соседних rabbit-IT):
 * <pre>
 * RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT=5679 RABBITMQ_USER=zorrodev \
 * RABBITMQ_PASSWORD=zorrodev RABBITMQ_MGMT_BASE_URL=http://127.0.0.1:15679/rabbitmq \
 * mvn ... -Dgroups=rabbit -Dzbpm.excludedGroups=
 * </pre>
 * Брокер для этого прогона поднимает {@code ci/run-rabbit-tests.sh}: он монтирует тот
 * же {@code ci/rabbitmq/conf.d/30-management-path-prefix.conf}, что и прод-брокер.
 */
@Tag("rabbit")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitMqMgmtPathPrefixRabbitIT {

    /** Префикс из конфига брокера; та же константа живёт в RABBITMQ_MGMT_BASE_URL. */
    private static final String MGMT_PATH_PREFIX = "/rabbitmq";

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", () -> cfg("RABBITMQ_HOST", "localhost"));
        registry.add("spring.rabbitmq.port", () -> Integer.parseInt(cfg("RABBITMQ_PORT", "5672")));
        registry.add("spring.rabbitmq.username", () -> cfg("RABBITMQ_USER", "zorrodev"));
        registry.add("spring.rabbitmq.password", () -> cfg("RABBITMQ_PASSWORD", "zorrodev"));
        // Продоверка в контексте — ровно то, что задаёт compose (WO-QW-12 ДОПОЛНЕНИЕ).
        registry.add("zorrobpm.rabbitmq.management.base-url", RabbitMqMgmtPathPrefixRabbitIT::mgmtBase);
    }

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        return v != null ? v : dflt;
    }

    /** Base URL из окружения — это и есть продоверка из compose. */
    static String mgmtBase() {
        String base = cfg("RABBITMQ_MGMT_BASE_URL", "http://localhost:15672");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    /** Тот же base-url с отброшенным префиксом — ровно то, что было бы в compose без него. */
    static String mgmtBaseWithoutPrefix() {
        String base = mgmtBase();
        return base.endsWith(MGMT_PATH_PREFIX)
            ? base.substring(0, base.length() - MGMT_PATH_PREFIX.length())
            : base;
    }

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient mgmt = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private String createdLogin;

    @AfterEach
    void dropBrokerAccount() throws Exception {
        // Стенд один на весь rabbit-сьют: оставлять брокер-юзера нельзя — следующий
        // прогон увидит чужой аккаунт (и login-коллизия).
        if (createdLogin != null) {
            mgmtCall("DELETE", "/api/users/" + createdLogin, null);
            createdLogin = null;
        }
    }

    private String adminCreds() {
        return cfg("RABBITMQ_USER", "zorrodev") + ":" + cfg("RABBITMQ_PASSWORD", "zorrodev");
    }

    private HttpResponse<String> mgmtCall(String method, String path, String jsonBody)
        throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(mgmtBase() + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString(adminCreds().getBytes(StandardCharsets.UTF_8)))
            .method(method, jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return mgmt.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ==================================================== критерий ДОПОЛНЕНИЯ (2)

    @Test
    void prefixedProvisioning_succeeds_accountReallyExistsAndPasswordWorksOverAmqp()
        throws Exception {
        UUID userId = createSystemUser();
        String login = userRepository.findById(userId).orElseThrow().getUsername();
        createdLogin = login;

        String superAdminToken = loginAndGetToken("admin", "admin");
        MvcResult provisioned = mockMvc.perform(
                post("/admin/users/" + userId + "/rabbitmq-password")
                    .header("Authorization", "Bearer " + superAdminToken))
            .andReturn();
        assertThat(provisioned.getResponse().getStatus())
            .as("provision under the prefixed base-url failed: "
                + provisioned.getResponse().getContentAsString())
            .isEqualTo(200);
        String password = mapper.readTree(provisioned.getResponse().getContentAsString())
            .get("password").asText();

        // (1) Аккаунт существует У БРОКЕРА и помечен как наш. Не «ответ 200», а тело
        //     ответа management API: чужая запись с тем же логином не прошла бы мимо
        //     метки, которую ставит только upsertBrokerUser.
        HttpResponse<String> onBroker = mgmtCall("GET", "/api/users/" + login, null);
        assertThat(onBroker.statusCode())
            .as("the provisioned account must exist on the broker, body=" + onBroker.body())
            .isEqualTo(200);
        assertThat(onBroker.body())
            .as("account must carry the ZBPM managed tag (proof it came from our PUT)")
            .contains("zbpm-managed");

        // (2) Настоящий AMQP-логин выданными кредами: брокер посчитал наш password_hash
        //     сам. HTTP-статус этого не доказывает — Management API мог бы ответить 201
        //     и не записать ничего.
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(cfg("RABBITMQ_HOST", "localhost"));
        factory.setPort(Integer.parseInt(cfg("RABBITMQ_PORT", "5672")));
        factory.setUsername(login);
        factory.setPassword(password);
        factory.setConnectionTimeout(10_000);
        try (Connection conn = factory.newConnection("qw12-prefix-proof")) {
            assertThat(conn.isOpen())
                .as("the broker must accept the provisioned credentials over real AMQP")
                .isTrue();
        }

        // (3) Флаг в БД взведён — провижининг прошёл целиком, а не наполовину.
        assertThat(userRepository.findById(userId).orElseThrow().isRabbitmqProvisioned())
            .as("DB flag must be set once the broker accepted the account").isTrue();
    }

    /**
     * Стенд действительно префиксный. Без этой проверки позитивный тест выше прошёл бы и
     * на брокере БЕЗ префикса — то есть ничего бы не доказывал про {@code /rabbitmq}.
     * Намеренно НЕ assume: если стенд не префиксный, это расхождение с compose надо
     * видеть как падение с текстом, а не как молча пропущенный тест (V4).
     */
    @Test
    void standBroker_reallyIsPrefixScoped() throws Exception {
        assertThat(httpGet(mgmtBase() + "/api/overview"))
            .as("prefixed management path must answer on this broker (env=%s)", mgmtBase())
            .isEqualTo(200);
        assertThat(httpGet(mgmtBaseWithoutPrefix() + "/api/overview"))
            .as("prefix-less management path must NOT answer 200 on a prefix-scoped broker "
                + "(env=%s) — otherwise the test above proves nothing about the prefix",
                mgmtBase())
            .isEqualTo(404);
    }

    /** Management GET admin-кредами: только код (телу тут нечего проверять). */
    private int httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString(adminCreds().getBytes(StandardCharsets.UTF_8)))
            .GET()
            .build();
        return mgmt.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class)
            .getToken();
    }

    private UUID createSystemUser() {
        String login = ("qw12pfx" + UUID.randomUUID().toString().substring(0, 8))
            .replace("-", "").toLowerCase();
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(login);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(login);
        user.setRole("USER");
        user.setActive(true);
        user.setUserType("SYSTEM");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }
}