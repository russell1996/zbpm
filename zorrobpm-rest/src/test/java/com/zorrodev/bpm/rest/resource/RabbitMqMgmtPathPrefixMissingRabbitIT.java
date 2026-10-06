package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WO-QW-12 (ДОПОЛНЕНИЕ CTO, требование 2 — вторая половина): <b>без префикса
 * провижининга нет</b>. Ровно тот же стенд и та же боевая полоса, что у
 * {@link RabbitMqMgmtPathPrefixRabbitIT}, но {@code zorrobpm.rabbitmq.management.base-url}
 * берётся с отброшенным {@code /rabbitmq} — то есть «забыли дописать префикс в compose»,
 * а это вполне реальная ошибка выката.
 *
 * <p>Что тут принципиально: URL собирает <b>тот же код</b>, что и в проде, меняется
 * ТОЛЬКО значение настройки. Отдельный «ручной» клиент в теле теста был бы копией
 * продового запроса (G-N) и доказал бы не то, а то, что автор теста умеет писать curl.
 *
 * <p>Ассерты — на конкретные значения, различающие «правильно» и «сломано» (P-67):
 * <ul>
 *   <li>ответ эндпоинта 503 (fail-closed), а не 200 и не 400;</li>
 *   <li>брокер-аккаунт <b>не создан</b> — читаем его у брокера по ПРАВИЛЬНОМУ
 *       (префиксованному) URL и получаем 404, а не «ответ пустой»;</li>
 *   <li>DB-флаг {@code rabbitmqProvisioned} остался false — «не наполовину».</li>
 * </ul>
 * Наивная проверка «503 — и всё» была бы слабой: она не отличает «брокер создал аккаунт,
 * а потом упало» от «ничего не создалось», а это разные аварии.
 */
@Tag("rabbit")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitMqMgmtPathPrefixMissingRabbitIT {

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", () -> cfg("RABBITMQ_HOST", "localhost"));
        registry.add("spring.rabbitmq.port", () -> Integer.parseInt(cfg("RABBITMQ_PORT", "5672")));
        registry.add("spring.rabbitmq.username", () -> cfg("RABBITMQ_USER", "zorrodev"));
        registry.add("spring.rabbitmq.password", () -> cfg("RABBITMQ_PASSWORD", "zorrodev"));
        // Единственное отличие от позитивного IT: префикс выброшен из base-url.
        registry.add("zorrobpm.rabbitmq.management.base-url",
            RabbitMqMgmtPathPrefixRabbitIT::mgmtBaseWithoutPrefix);
    }

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        return v != null ? v : dflt;
    }

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient mgmt = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @Test
    void provisioningWithoutPrefix_failsClosed_andCreatesNoBrokerAccount() throws Exception {
        String login = ("qw12nopf" + UUID.randomUUID().toString().substring(0, 8))
            .replace("-", "").toLowerCase();
        UUID userId = createSystemUser(login);

        // Предусловие теста, а не его предмет: стенд должен быть префиксным, иначе
        // «без префикса» здесь не значит ничего. Падение с текстом, а не assume —
        // иначе расхождение стенда с compose было бы тихо (V4).
        assertThat(statusOf(mgmtCall("GET", "/api/overview", null)))
            .as("stand broker must be prefix-scoped for this test to mean anything (env=%s)",
                RabbitMqMgmtPathPrefixRabbitIT.mgmtBase())
            .isEqualTo(200);

        String superAdminToken = loginAndGetToken("admin", "admin");
        MvcResult provisioned = mockMvc.perform(
                post("/admin/users/" + userId + "/rabbitmq-password")
                    .header("Authorization", "Bearer " + superAdminToken))
            .andReturn();

        assertThat(provisioned.getResponse().getStatus())
            .as("without the management path prefix the broker answers 404, and the "
                + "fail-closed contract is 503 — not 200 and not a half-written user")
            .isEqualTo(503);

        // Читаем состояние брокера по ПРАВИЛЬНОМУ URL: доказательство «ничего не
        // создалось», а не «ответ был неуспешным».
        HttpResponse<String> onBroker = mgmtCall("GET", "/api/users/" + login, null);
        assertThat(onBroker.statusCode())
            .as("no broker account may exist after a failed provisioning, body=" + onBroker.body())
            .isEqualTo(404);

        assertThat(userRepository.findById(userId).orElseThrow().isRabbitmqProvisioned())
            .as("DB flag must stay false when the broker refused the provisioning")
            .isFalse();
    }

    private int statusOf(HttpResponse<String> r) {
        return r.statusCode();
    }

    /**
     * Management-вызов по ПРЕФИКСОВАННОМУ base-url (наблюдатель за состоянием брокера).
     * Это не копия продового запроса на провижининг: продовый путь уже отработал выше
     * через REST-эндпоинт; здесь тест просто смотрит на брокер, чтобы проверить, что
     * тот не получил ничего.
     */
    private HttpResponse<String> mgmtCall(String method, String path, String jsonBody)
        throws Exception {
        String creds = cfg("RABBITMQ_USER", "zorrodev") + ":" + cfg("RABBITMQ_PASSWORD", "zorrodev");
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(RabbitMqMgmtPathPrefixRabbitIT.mgmtBase() + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString(creds.getBytes(StandardCharsets.UTF_8)))
            .method(method, jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        return mgmt.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class)
            .getToken();
    }

    private UUID createSystemUser(String login) {
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