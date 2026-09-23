package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-72 (N03): refresh-cookie не отправлялась на {@code /api/auth/refresh} из-за
 * Path-мисматча — бэкенд ставил одиночный {@code Path=/auth/refresh}, а SPA за
 * префиксом {@code /api} ходит на {@code /api/auth/refresh}; nginx режет префикс
 * ПОСЛЕ того, как браузер уже выбрал cookie по своему запросному пути
 * (RFC 6265 §5.1.4). Фикс: dual Set-Cookie — {@code /auth/refresh} +
 * {@code /api/auth/refresh} с одним значением; logout гасит оба.
 *
 * <p>MockMvc бьёт напрямую в backend-пути (эквивалент запроса ПОСЛЕ strip'а префикса
 * в nginx/vite-proxy): браузерную cookie-jar-логику воспроизводит тест 1 через
 * дословный RFC 6265 §5.1.4 path-match по фактически выставленным Set-Cookie —
 * браузерный выбор эмулируется кодом, а не реальным браузером (см. отчёт).
 *
 * <p>Каждый тест использует СВОЕГО пользователя (P-8: без мутации seeded-admin,
 * без межтестовых связей).
 *
 * <p>POF: убери второй Set-Cookie в {@code AuthResource} (только
 * {@code Path=/auth/refresh}) — ВСЕ ТРИ теста идут RED: тест 1 (нет Path под
 * {@code /api/...}, path-match доказывает: браузер бы куку не прислал), тест 2
 * (ротация обязана перевыпустить оба Path) и тест 3 (logout обязан погасить оба
 * Path). RED приходится ровно на различающие ассерты (P-67: конкретные
 * Path-значения через {@code containsExactlyInAnyOrder}, не «кука вообще есть»).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Sec72RefreshCookiePathIT {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final String PASS = "Sec72Passw0rd!secure";

    private UUID createUser(String prefix) {
        UUID id = UUID.randomUUID();
        UiUserEntity u = new UiUserEntity();
        u.setId(id);
        u.setUsername(prefix + "-" + id.toString().substring(0, 8));
        u.setPasswordHash(passwordHasher.hash(PASS));
        u.setFullName(prefix + " User");
        u.setEmail(prefix + "-" + id.toString().substring(0, 8) + "@example.com");
        u.setRole("USER");
        u.setUserType("HUMAN");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
        return id;
    }

    private String usernameOf(UUID id) {
        return userRepository.findById(id).orElseThrow().getUsername();
    }

    private MvcResult login(String username) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(PASS);
        return mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** Один Set-Cookie, разобранный на имя/значение/Path. */
    private record ParsedCookie(String name, String value, String path) {}

    private static List<ParsedCookie> parseSetCookies(Collection<String> headers) {
        List<ParsedCookie> out = new ArrayList<>();
        for (String h : headers) {
            String[] parts = h.split(";");
            String[] nv = parts[0].trim().split("=", 2);
            if (nv.length != 2) continue;
            String path = null;
            for (int i = 1; i < parts.length; i++) {
                String attr = parts[i].trim();
                if (attr.regionMatches(true, 0, "Path=", 0, 5)) {
                    path = attr.substring(5);
                }
            }
            out.add(new ParsedCookie(nv[0].trim(), nv[1].trim(), path));
        }
        return out;
    }

    private static List<ParsedCookie> refreshCookies(MvcResult r) {
        List<ParsedCookie> all = parseSetCookies(r.getResponse().getHeaders("Set-Cookie"));
        return all.stream().filter(c -> c.name().equals("refresh_token")).toList();
    }

    /**
     * RFC 6265 §5.1.4 дословно: request-path path-matches cookie-path, если они
     * равны, либо cookie-path — префикс request-path И (cookie-path кончается на
     * "/" ИЛИ следующий символ request-path после префикса — "/").
     */
    static boolean pathMatches(String requestPath, String cookiePath) {
        if (requestPath.equals(cookiePath)) return true;
        if (requestPath.startsWith(cookiePath)) {
            if (cookiePath.endsWith("/")) return true;
            if (requestPath.length() > cookiePath.length()
                    && requestPath.charAt(cookiePath.length()) == '/') return true;
        }
        return false;
    }

    // --- Тест 1 (ядро WO): login выставляет ОБА Path, браузерный выбор закрыт
    //     для обоих deployment-режимов; __Host-cookie не тронута ---

    @Test
    void login_emitsRefreshCookie_forBothDeploymentPaths_browserWouldSendOnBoth() throws Exception {
        UUID id = createUser("sec72-dual");
        MvcResult login = login(usernameOf(id));

        List<ParsedCookie> refresh = refreshCookies(login);
        assertThat(refresh).as("login must emit refresh_token cookies").hasSize(2);
        assertThat(refresh.stream().map(ParsedCookie::path).toList())
                .as("refresh_token must cover the backend path AND the /api-prefixed external path")
                .containsExactlyInAnyOrder(
                        "/auth/refresh",
                        "/api/auth/refresh");
        assertThat(refresh.stream().map(ParsedCookie::value).distinct().toList())
                .as("both Path-variants must carry the SAME refresh value (no divergence possible)")
                .hasSize(1);
        assertThat(refresh.get(0).value()).isNotBlank();

        // Браузерный выбор (RFC 6265 §5.1.4) по фактически выставленным Path:
        // режим с /api-префиксом (прод-nginx, dev vite-proxy) ...
        assertThat(pathMatches("/api/auth/refresh", "/api/auth/refresh"))
                .as("browser requesting /api/auth/refresh WOULD send the api-prefix cookie")
                .isTrue();
        assertThat(pathMatches("/api/auth/refresh", "/auth/refresh"))
                .as("old single Path=/auth/refresh would NOT be sent on /api/auth/refresh — "
                        + "this is exactly the N03 mechanism the fix removes")
                .isFalse();
        // ... и режим с API в корне (VITE_API_URL=/) ...
        assertThat(pathMatches("/auth/refresh", "/auth/refresh"))
                .as("browser requesting /auth/refresh WOULD send the backend-path cookie")
                .isTrue();

        // Запрет WO п.2: __Host-access-cookie не тронута — Path=/ как была.
        Cookie access = login.getResponse().getCookie("__Host-zbpm_token");
        assertThat(access)
                .as("__Host access cookie must still be set")
                .isNotNull();
        assertThat(access.getPath())
                .as("WO-SEC-72 must NOT change the __Host access-cookie Path (stays /)")
                .isEqualTo("/");
    }

    // --- Тест 2: полный цикл интерцептора (refreshInterceptor.ts:94-98):
    //     исходный запрос 401 (нет/истёк access) → POST /auth/refresh → повтор
    //     исходного запроса 200. MockMvc бьёт в backend-путь — эквивалент запроса
    //     после strip'а префикса; кука та самая, что браузер прислал бы с /api-пути
    //     благодаря тесту 1. ---

    @Test
    void refreshCycle_original401_thenRefresh_thenRetryOriginal200() throws Exception {
        UUID id = createUser("sec72-cycle");
        MvcResult login = login(usernameOf(id));
        String refreshToken = login.getResponse().getCookie("refresh_token").getValue();
        assertThat(refreshToken).isNotBlank();

        // Исходный запрос без access-cookie → 401 (истёк/нет — интерцептор
        // различает только статус; refresh стартует именно отсюда).
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());

        // POST /auth/refresh с refresh-cookie → 200 + ротация обоих кук.
        MvcResult refresh = mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie newAccess = refresh.getResponse().getCookie("__Host-zbpm_token");
        assertThat(newAccess)
                .as("refresh must rotate the httpOnly access cookie")
                .isNotNull();
        assertThat(newAccess.getValue()).isNotBlank();

        List<ParsedCookie> rotated = refreshCookies(refresh);
        assertThat(rotated.stream().map(ParsedCookie::path).toList())
                .as("rotation must re-emit BOTH refresh paths (else the next cycle loses one mode)")
                .containsExactlyInAnyOrder(
                        "/auth/refresh",
                        "/api/auth/refresh");

        // Повтор исходного запроса с новой access-cookie → 200.
        mockMvc.perform(get("/auth/me")
                        .cookie(new Cookie("__Host-zbpm_token", newAccess.getValue())))
                .andExpect(status().isOk());
    }

    // --- Тест 3: logout гасит ОБА Path (clear обязан совпадать с set по имени
    //     И Path, иначе браузер молча оставляет выживший вариант) ---

    @Test
    void logout_clearsBothRefreshCookiePaths() throws Exception {
        UUID id = createUser("sec72-logout");
        MvcResult login = login(usernameOf(id));
        String accessToken = login.getResponse().getCookie("__Host-zbpm_token").getValue();

        // WO-SEC-64: cookie-auth POST без Origin режется CsrfFilter (403) —
        // SPA всегда шлёт Origin, тест тоже (own origin MockMvc — http://localhost).
        MvcResult logout = mockMvc.perform(post("/auth/logout")
                        .header("Origin", "http://localhost")
                        .cookie(new Cookie("__Host-zbpm_token", accessToken)))
                .andExpect(status().isOk())
                .andReturn();

        List<ParsedCookie> cleared = refreshCookies(logout);
        assertThat(cleared.stream().map(ParsedCookie::path).toList())
                .as("logout must clear BOTH refresh paths")
                .containsExactlyInAnyOrder(
                        "/auth/refresh",
                        "/api/auth/refresh");

        List<String> rawHeaders = new ArrayList<>(logout.getResponse().getHeaders("Set-Cookie"));
        long zeroMaxAgeRefreshClears = rawHeaders.stream()
                .filter(h -> h.startsWith("refresh_token="))
                .filter(h -> h.contains("Max-Age=0"))
                .count();
        assertThat(zeroMaxAgeRefreshClears)
                .as("both refresh clears must carry Max-Age=0")
                .isEqualTo(2);
    }
}
