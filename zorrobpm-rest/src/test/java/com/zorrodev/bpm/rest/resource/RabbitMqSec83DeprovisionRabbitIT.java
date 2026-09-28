package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-83 (NEW4-04/NEW4-05): отзыв брокер-доступа при деактивации +
 * защита от перезаписи чужого broker-аккаунта — против РЕАЛЬНОГО брокера
 * ({@code rabbitmq:4.1-management-alpine}, тот же паттерн, что
 * {@code RabbitMqPerSystemCredentialsRabbitIT}: настоящее AMQP-подключение
 * новыми кредами, а не чтение permissions через Management API).
 *
 * <p>P-67: каждый негативный ассерт — на КОНКРЕТНОМ значении, отличающемся
 * между «правильно» и «сломано» (AMQP-auth отклонён / GET-тело байт-в-байт),
 * не «буфер непуст».
 *
 * <p>Прогон (P-23: env, не -D):
 * <pre>
 * RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT=5679 RABBITMQ_USER=zorrodev \
 * RABBITMQ_PASSWORD=zorrodev RABBITMQ_MGMT_BASE_URL=http://127.0.0.1:15679 \
 * mvn ... -Dgroups=rabbit -Dzbpm.excludedGroups=
 * </pre>
 */
@Tag("rabbit")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitMqSec83DeprovisionRabbitIT {

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", () -> cfg("RABBITMQ_HOST", "localhost"));
        registry.add("spring.rabbitmq.port", () -> Integer.parseInt(cfg("RABBITMQ_PORT", "5672")));
        registry.add("spring.rabbitmq.username", () -> cfg("RABBITMQ_USER", "zorrodev"));
        registry.add("spring.rabbitmq.password", () -> cfg("RABBITMQ_PASSWORD", "zorrodev"));
        registry.add("zorrobpm.rabbitmq.management.base-url",
            () -> cfg("RABBITMQ_MGMT_BASE_URL", "http://localhost:15672"));
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

    private String amqpHost() {
        return cfg("RABBITMQ_HOST", "localhost");
    }

    private int amqpPort() {
        return Integer.parseInt(cfg("RABBITMQ_PORT", "5672"));
    }

    private String mgmtBase() {
        String base = cfg("RABBITMQ_MGMT_BASE_URL", "http://localhost:15672");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private String adminCreds() {
        return cfg("RABBITMQ_USER", "zorrodev") + ":" + cfg("RABBITMQ_PASSWORD", "zorrodev");
    }

    /** Fresh AMQP-коннект указанными кредами — каждое доказательство на новом. */
    private Connection connect(String login, String password) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(amqpHost());
        factory.setPort(amqpPort());
        factory.setUsername(login);
        factory.setPassword(password);
        factory.setConnectionTimeout(10_000);
        return factory.newConnection("sec83-proof");
    }

    /** Прямой Management-вызов admin-кредами (чужие аккаунты, GET до/после). */
    private HttpResponse<String> mgmtCall(String method, String path, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(mgmtBase() + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString(adminCreds().getBytes(StandardCharsets.UTF_8)));
        switch (method) {
            case "PUT" -> b.header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            case "DELETE" -> b.DELETE();
            default -> b.GET();
        }
        return mgmt.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ==================== NEW4-04: деактивация отзывает AMQP ====================

    @Test
    void criterion1_deactivation_revokesAmqpAccess() throws Exception {
        String token = loginAndGetToken("admin", "admin");
        String uniq = UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String login = ("sec83d" + uniq).toLowerCase();
        UUID userId = createSystemUser(login);
        String password = provisionPassword(userId, token);
        // До деактивации — коннект жив.
        try (Connection conn = connect(login, password)) {
            assertThat(conn.isOpen()).isTrue();
        }

        mockMvc.perform(put("/users/" + userId)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"active\":false}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // После — ТЕ ЖЕ креды отклонены на AMQP-handshake (не 403 на очередь,
        // а отказ аутентификации: аккаунта больше нет).
        boolean rejected;
        try (Connection ignored = connect(login, password)) {
            rejected = false;
        } catch (Exception e) {
            rejected = true;
        }
        assertThat(rejected)
            .as("AMQP connect with the revoked credentials must be rejected after deactivation")
            .isTrue();
        assertThat(userRepository.findById(userId).orElseThrow().isRabbitmqProvisioned())
            .as("DB flag must be reset — no orphan state for future syncs")
            .isFalse();
    }

    // ==================== NEW4-05: чужой аккаунт не перезаписывается ====================

    @Test
    void criterion3_foreignBrokerAccount_provisionRefused_untouched() throws Exception {
        String token = loginAndGetToken("admin", "admin");
        String uniq = UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String login = ("sec83f" + uniq).toLowerCase();

        // Чужой аккаунт — создан НАПРЯМУЮ через Management API (не через ZBPM):
        // тег monitoring, свой хэш. POF-мутант «без GET-guard» перезаписал бы оба.
        String foreignHash = Base64.getEncoder()
            .encodeToString(("foreign-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> created = mgmtCall("PUT", "/api/users/" + login,
            "{\"password_hash\":\"" + foreignHash + "\",\"tags\":\"monitoring\"}");
        assertThat(created.statusCode()).isIn(200, 201, 204);
        String before = mgmtCall("GET", "/api/users/" + login, null).body();
        assertThat(before).contains("\"monitoring\"");

        UUID userId = createSystemUser(login);
        MvcResult refused = mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(refused.getResponse().getStatus())
            .as("provisioning over a foreign broker account must be refused, not overwrite")
            .isEqualTo(409);

        String after = mgmtCall("GET", "/api/users/" + login, null).body();
        assertThat(after)
            .as("foreign password_hash and tags must be byte-identical before/after the refused provision")
            .isEqualTo(before);

        mgmtCall("DELETE", "/api/users/" + login, null);
    }

    // ==================== NEW4-05: ротация своего работает ====================

    @Test
    void criterion4_ownAccount_rotationStillWorks() throws Exception {
        String token = loginAndGetToken("admin", "admin");
        String uniq = UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String login = ("sec83r" + uniq).toLowerCase();
        UUID userId = createSystemUser(login);
        String password = provisionPassword(userId, token);

        String tags = mgmtCall("GET", "/api/users/" + login, null).body();
        assertThat(tags).contains("zbpm-managed");

        String rotated = provisionPassword(userId, token);
        assertThat(rotated).isNotEqualTo(password);
        // Старый мёртв, новый жив — скоуп не сужался guard'ом.
        boolean oldRejected;
        try (Connection ignored = connect(login, password)) {
            oldRejected = false;
        } catch (Exception e) {
            oldRejected = true;
        }
        assertThat(oldRejected).as("old password must be dead after rotation").isTrue();
        try (Connection conn = connect(login, rotated)) {
            assertThat(conn.isOpen()).isTrue();
        }
    }

    // ==================== NEW4-05: grandfather без маркера, но с флагом ====================

    @Test
    void grandfather_unmarkedOwnAccount_rotatesAndGainsMarker() throws Exception {
        String token = loginAndGetToken("admin", "admin");
        String uniq = UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String login = ("sec83g" + uniq).toLowerCase();

        // Аккаунт эпохи WO-INT-9: создан ZBPM (флаг true), но теги пустые.
        String oldHash = Base64.getEncoder()
            .encodeToString(("grandfather-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> created = mgmtCall("PUT", "/api/users/" + login,
            "{\"password_hash\":\"" + oldHash + "\",\"tags\":\"\"}");
        assertThat(created.statusCode()).isIn(200, 201, 204);
        UUID userId = createSystemUser(login);
        UiUserEntity e = userRepository.findById(userId).orElseThrow();
        e.setRabbitmqProvisioned(true);
        userRepository.save(e);
        // Реальный до-маркерный аккаунт имел permissions (их синкало членство
        // при provision). Прямой PUT выше прав не выдаёт — симулируем их тем
        // же Management API, иначе AMQP-hanshake упадёт 530 NOT_ALLOWED не по
        // причине пароля, а по отсутствию прав (поймано живым прогоном).
        HttpResponse<String> perms = mgmtCall("PUT", "/api/permissions/%2F/" + login,
            "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}");
        assertThat(perms.statusCode()).isIn(200, 201, 204);

        MvcResult rotated = mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String newPassword = mapper.readTree(rotated.getResponse().getContentAsString())
            .get("password").asText();

        String tags = mgmtCall("GET", "/api/users/" + login, null).body();
        assertThat(tags)
            .as("rotation of a grandfathered account heals the marker")
            .contains("zbpm-managed");
        try (Connection conn = connect(login, newPassword)) {
            assertThat(conn.isOpen()).isTrue();
        }

        mgmtCall("DELETE", "/api/users/" + login, null);
    }

    // ==================== Fixture ====================

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
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

    private String provisionPassword(UUID userId, String token) throws Exception {
        MvcResult provisioned = mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(provisioned.getResponse().getStatus())
            .as("provision failed: " + provisioned.getResponse().getContentAsString())
            .isEqualTo(200);
        return mapper.readTree(provisioned.getResponse().getContentAsString())
            .get("password").asText();
    }
}
