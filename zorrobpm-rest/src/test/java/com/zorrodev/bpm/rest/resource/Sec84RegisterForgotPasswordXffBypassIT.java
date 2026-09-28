package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-84: per-IP лимит на {@code /auth/register} и {@code /auth/forgot-password}
 * нельзя обойти подделкой {@code X-Forwarded-For} — H-сценарий аудита как постоянный
 * full-context тест (реальный {@code @SpringBootTest(RANDOM_PORT)},
 * {@code forward-headers-strategy=framework}, малая capacity для быстрого прогона).
 *
 * <p>Тот же паттерн, что {@code RateLimitXffFullContextTest} (login): контроль без XFF
 * с того же сокета — N-й запрос отбит; с подделанным XFF после исчерпания лимита —
 * ТОЖЕ отбит. Корень дыры: контроллеры звали {@code getClientIp(request)} ПОСЛЕ
 * {@code ForwardedHeaderFilter}-переписывания; фикс — IP из атрибута, который
 * {@code RateLimitFilter} кладёт ДО переписывания, + регистрация обоих путей в
 * фильтре.
 *
 * <p>POF-мутации (каждая валится ровно своим тестом):
 * <ul>
 *   <li>вернуть {@code rateLimitFilter.getClientIp(request)} в контроллеры —
 *       оба {@code *_xffSpoof_*} КРАСНЫЕ (подделка снова даёт 200);</li>
 *   <li>убрать пути из {@code RateLimitFilterConfig.addUrlPatterns} — оба КРАСНЫЕ
 *       (атрибута нет → register 429-fail-closed по другой причине... нет:
 *       register даёт 429, forgot даёт 200-тишину — ассерты ловят оба);</li>
 *   <li>убрать {@code setAttribute} из фильтра — то же, что выше.</li>
 * </ul>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    // Малые бакеты для быстрого прогона — те же ключи, что прод (keyPrefix
    // register:/reset: + ip:), только capacity ужата.
    "zorrobpm.security.rate-limit.register-ip-capacity=2",
    "zorrobpm.security.rate-limit.register-ip-window-seconds=3600",
    "zorrobpm.security.rate-limit.reset-ip-capacity=2",
    "zorrobpm.security.rate-limit.reset-ip-window-seconds=3600",
    "server.forward-headers-strategy=framework"
})
class Sec84RegisterForgotPasswordXffBypassIT {

    @LocalServerPort
    private int port;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    @Qualifier("registrationRateLimiter")
    private com.zorrodev.bpm.engine.service.PasswordResetRateLimiter registrationRateLimiter;

    @Autowired
    private com.zorrodev.bpm.engine.service.PasswordResetRateLimiter resetRateLimiter;

    @Autowired
    private com.zorrodev.bpm.engine.mail.StubMailSender mailSender;

    @Autowired
    private com.zorrodev.bpm.engine.repository.UiUserRepository userRepository;

    @Autowired
    private com.zorrodev.bpm.engine.security.PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        rateLimitFilter.reset();
        registrationRateLimiter.reset();
        resetRateLimiter.reset();
        mailSender.clear();
    }

    @AfterEach
    void tearDown() {
        // P-8/P-59: малая capacity этого класса (2) оставляет строки бакетов с
        // tokens=0 в ОБЩЕЙ таблице rate_limit_bucket (H2 mem:test делится между
        // классами в одном surefire-процессе) — следующий класс с capacity=20
        // увидел бы отравленный бакет и упал бы 429. Чистим за собой тоже.
        rateLimitFilter.reset();
        mailSender.clear();
    }

    private HttpRequest buildRegisterRequest(String xffValue) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String body = mapper.writeValueAsString(java.util.Map.of(
            "username", "sec84-" + tag,
            "password", "MyStr0ng!P@ssw0rd",
            "fullName", "Sec84",
            "email", "sec84-" + tag + "@x.com"));
        var builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/auth/register"))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (xffValue != null) {
            builder.header("X-Forwarded-For", xffValue);
        }
        return builder.build();
    }

    private HttpRequest buildForgotRequest(String email, String xffValue) {
        String body = "{\"email\":\"" + email + "\"}";
        var builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/auth/forgot-password"))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (xffValue != null) {
            builder.header("X-Forwarded-For", xffValue);
        }
        return builder.build();
    }

    @Test
    void register_xffSpoof_afterExhaustion_stillRejected() throws Exception {
        // Контроль: без XFF, тот же сокет — 3-й запрос отбит (лимит жив).
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> resp = httpClient.send(buildRegisterRequest(null),
                HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode())
                .as("register request %d without XFF should pass", i)
                .isEqualTo(200);
        }
        // Дыра: подделанный XFF после исчерпания лимита — тоже отбит.
        // Register при исчерпанном IP-бакете отвечает 429 + Retry-After
        // (WO-QW-9: RegistrationRateLimitException, не общий EngineException):
        // наблюдаемый сигнал — НЕ-200, в отличие от 200 при обходе.
        HttpResponse<String> spoofed = httpClient.send(buildRegisterRequest("10.9.9.9"),
            HttpResponse.BodyHandlers.ofString());
        assertThat(spoofed.statusCode())
            .as("register with spoofed XFF after exhaustion must still be rejected")
            .isEqualTo(429);
    }

    @Test
    void forgotPassword_xffSpoof_afterExhaustion_sendsNoMoreMail() throws Exception {
        // Наблюдаемый сигнал лимита:фактически отправленные письма (endpoint
        // enumeration-safe — всегда 200, статус ничего не доказывает, P-67).
        // Создаём РЕАЛЬНОГО пользователя, чтобы письма реально уходили.
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String email = "sec84-fp-" + tag + "@x.com";
        com.zorrodev.bpm.engine.entity.UiUserEntity user =
            new com.zorrodev.bpm.engine.entity.UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("sec84fp-" + tag);
        user.setPasswordHash(passwordHasher.hash("MyStr0ng!P@ssw0rd"));
        user.setFullName("Sec84");
        user.setEmail(email);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(java.time.Instant.now());
        user.setUpdatedAt(java.time.Instant.now());
        userRepository.save(user);
        try {
            // Два запроса с РАЗНЫМИ подделанными XFF — делят один настоящий
            // IP-бакет (capacity=2): оба проходят, оба шлют письмо.
            // Per-email бакет capacity=5 — не мешает (один email, 3 запроса).
            for (int i = 0; i < 2; i++) {
                HttpResponse<String> resp = httpClient.send(buildForgotRequest(email, "10.8.8." + (i + 1)),
                    HttpResponse.BodyHandlers.ofString());
                assertThat(resp.statusCode())
                    .as("forgot-password is enumeration-safe, always 200")
                    .isEqualTo(200);
            }
            assertThat(mailSender.getSent().stream().filter(m -> email.equalsIgnoreCase(m.to())).count())
                .as("two requests within the IP budget each send a reset mail")
                .isEqualTo(2);
            // Третий с ещё одним «новым» XFF: если бы бакет ключeвался
            // поддельным IP — ушёл бы в свежий бакет и отправил бы письмо.
            // На настоящем IP бакет пуст — письма больше нет (статус всё равно 200).
            HttpResponse<String> third = httpClient.send(buildForgotRequest(email, "10.8.8.99"),
                HttpResponse.BodyHandlers.ofString());
            assertThat(third.statusCode()).isEqualTo(200);
            assertThat(mailSender.getSent().stream().filter(m -> email.equalsIgnoreCase(m.to())).count())
                .as("spoofed XFF after exhaustion must NOT send another reset mail")
                .isEqualTo(2);
        } finally {
            userRepository.deleteById(user.getId());
        }
    }
}
