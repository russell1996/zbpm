package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WO-SEC-86, критерий 1+4: два клиента за одним доверенным прокси-пиром получают
 * НЕЗАВИСИМЫЕ per-IP бакеты — на login, data и forgot-password (три разных бакета,
 * один общий механизм {@code RateLimitFilter.getClientIp}).
 *
 * <p>V11: полный Spring-контекст + реальная цепочка фильтров (MockMvc, тот же механизм,
 * что {@code Api4AdminRateLimitIT}/{@code Sec64CsrfHeadersRatelimitIT}); сокет-пир
 * MockMvc — 127.0.0.1, он же объявлен доверенным ({@code trusted-proxies=127.0.0.1}),
 * клиенты различаются только {@code X-Real-IP} — ровно так их различает nginx
 * ({@code proxy_set_header X-Real-IP $remote_addr}).
 *
 * <p>POF-мутация: вернуть старое тело {@code getClientIp} ({@code return remoteAddr}
 * в обеих ветках) — все три теста КРАСНЫЕ: жертва B получает 429 / 3-й data-запрос
 * 429 / 3-е письмо не уходит (сигнатура живой дыры из H-теста аудита).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.capacity=5",
    "zorrobpm.security.rate-limit.window-seconds=3600",
    "zorrobpm.security.rate-limit.account-capacity=100",
    "zorrobpm.security.rate-limit.data-capacity=3",
    "zorrobpm.security.rate-limit.data-window-seconds=3600",
    "zorrobpm.security.rate-limit.reset-ip-capacity=2",
    "zorrobpm.security.rate-limit.reset-ip-window-seconds=3600",
    // Сокет-пир тестов (MockMvc → 127.0.0.1) — «доверенный прокси» этой сюиты.
    "zorrobpm.security.rate-limit.trusted-proxies=127.0.0.1"
})
class Sec86TrustedProxyBucketIT {

    private static final String ATTACKER = "203.0.113.66";
    private static final String VICTIM = "198.51.100.7";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    private com.zorrodev.bpm.engine.service.PasswordResetRateLimiter resetRateLimiter;

    @Autowired
    private com.zorrodev.bpm.engine.mail.StubMailSender mailSender;

    @Autowired
    private com.zorrodev.bpm.engine.repository.UiUserRepository userRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        rateLimitFilter.reset();
        resetRateLimiter.reset();
        mailSender.clear();
    }

    @AfterEach
    void tearDown() {
        // P-8/P-59: малые capacity оставляют отравленные строки бакетов в общей
        // H2-таблице — чистим за собой, как Sec84RegisterForgotPasswordXffBypassIT.
        rateLimitFilter.reset();
        resetRateLimiter.reset();
        mailSender.clear();
    }

    private int login(String username, String password, String realIp) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                .header("X-Real-IP", realIp)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn();
        return result.getResponse().getStatus();
    }

    @Test
    void login_attackerBehindProxy_doesNotBlockVictim() throws Exception {
        // Атакующий: 5 неверных логинов (разные аккаунты — per-account бакет не
        // мешает, горит только IP-бакет ATTACKER). Статус 401 = запрос ПРОШЁЛ
        // фильтр и дошёл до аутентификации (не 429).
        for (int i = 0; i < 5; i++) {
            int status = login("sec86-nosuch-" + UUID.randomUUID().toString().substring(0, 8),
                "WrongPass123!", ATTACKER);
            assertThat(status)
                .as("attacker wrong login %d must reach auth (401), not the limiter (429)", i + 1)
                .isEqualTo(401);
        }
        // Жертва с другого адреса и ПРАВИЛЬНЫМ паролем — входит свободно.
        int victim = login("admin", "admin", VICTIM);
        assertThat(victim)
            .as("victim behind the same proxy peer with correct password must get 200, not 429")
            .isEqualTo(200);
    }

    @Test
    void data_attackerBehindProxy_doesNotBlockVictim() throws Exception {
        String attackerToken = adminToken(ATTACKER);
        String victimToken = adminToken(VICTIM);
        // 3 data-запроса атакующего (capacity=3) — все проходят.
        for (int i = 0; i < 3; i++) {
            int status = mockMvc.perform(get("/dmn")
                    .header("X-Real-IP", ATTACKER)
                    .header("Authorization", "Bearer " + attackerToken))
                .andReturn().getResponse().getStatus();
            assertThat(status)
                .as("attacker data request %d must pass", i + 1)
                .isEqualTo(200);
        }
        // Жертва — свой независимый бакет, тоже проходит.
        int victim = mockMvc.perform(get("/dmn")
                .header("X-Real-IP", VICTIM)
                .header("Authorization", "Bearer " + victimToken))
            .andReturn().getResponse().getStatus();
        assertThat(victim)
            .as("victim data request behind the same proxy peer must get 200, not 429")
            .isEqualTo(200);
    }

    @Test
    void forgotPassword_attackerBehindProxy_doesNotBlockVictim() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String email = "sec86-fp-" + tag + "@x.com";
        com.zorrodev.bpm.engine.entity.UiUserEntity user =
            new com.zorrodev.bpm.engine.entity.UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("sec86fp-" + tag);
        user.setPasswordHash(passwordHasher.hash("MyStr0ng!P@ssw0rd"));
        user.setFullName("Sec86");
        user.setEmail(email);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(java.time.Instant.now());
        user.setUpdatedAt(java.time.Instant.now());
        userRepository.save(user);
        try {
            // Наблюдаемый сигнал — фактически отправленные письма (endpoint
            // enumeration-safe, всегда 200 — статус ничего не доказывает, P-67).
            // Два запроса атакующего (capacity=2): оба проходят, оба шлют письмо.
            for (int i = 0; i < 2; i++) {
                int status = forgot(email, ATTACKER);
                assertThat(status).as("enumeration-safe, always 200").isEqualTo(200);
            }
            assertThat(mailSender.getSent().stream().filter(m -> email.equalsIgnoreCase(m.to())).count())
                .as("two attacker requests within the IP budget each send a reset mail")
                .isEqualTo(2);
            // Жертва с другого адреса: её IP-бакет пуст — письмо уходит.
            assertThat(forgot(email, VICTIM)).isEqualTo(200);
            assertThat(mailSender.getSent().stream().filter(m -> email.equalsIgnoreCase(m.to())).count())
                .as("victim behind the same proxy peer still gets the reset mail (3rd total)")
                .isEqualTo(3);
        } finally {
            userRepository.deleteById(user.getId());
        }
    }

    private String adminToken(String realIp) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        MvcResult result = mockMvc.perform(post("/auth/login")
                .header("X-Real-IP", realIp)
                // WO-SEC-70: JSON-token только по explicit opt-in заголовку.
                .header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn();
        assertThat(result.getResponse().getStatus())
            .as("admin login as %s must succeed", realIp)
            .isEqualTo(200);
        return mapper.readValue(result.getResponse().getContentAsString(),
            com.zorrodev.bpm.contract.dto.AuthResponse.class).getToken();
    }

    private int forgot(String email, String realIp) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/forgot-password")
                .header("X-Real-IP", realIp)
                .content("{\"email\":\"" + email + "\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn();
        return result.getResponse().getStatus();
    }
}
