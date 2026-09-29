package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-70 (раунд 3): гейт JSON-копии access token по Fetch Metadata.
 *
 * <p>{@code POST /auth/login} и {@code POST /auth/refresh} всегда ставят httpOnly
 * access cookie; поле {@code token} в JSON-теле отдаётся только когда запрос пришёл
 * НЕ из same-origin браузерного контекста: заголовка {@code Sec-Fetch-Site} нет
 * вообще (небраузерный клиент — curl, SDK, server-to-server) или он отличен от
 * {@code same-origin} ({@code cross-site} — внешний фронт на другом origin).
 * Запрос с {@code Sec-Fetch-Site: same-origin} (легитимный SPA и XSS на нём —
 * браузер ставит заголовок сам, JS его не переопределяет) получает cookie-only
 * ответ: оба получают ОДИНАКОВЫЙ безопасный ответ без token.
 *
 * <p>Каждый тест использует СВОЕГО пользователя (P-8: без мутации seeded-admin,
 * без межтестовых связей).
 *
 * <p>POF: убери гейт в {@code AuthResource} (всегда эхо token) — same-origin тесты
 * идут RED (token неожиданно присутствует), остальные остаются GREEN: тесты
 * различают оба пути, а не «что-то вообще вызвалось» (P-67: ассерты на конкретные
 * значения {@code isNull()} / {@code isEqualTo(cookie)}, не на непустоту).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Sec70JsonTokenOptInIT {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final String PASS = "Sec70Passw0rd!secure";

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

    private MockHttpServletRequestBuilder loginRequest(String username) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(PASS);
        return post("/auth/login")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder refreshRequest(String refreshToken) {
        return post("/auth/refresh")
                .cookie(new Cookie("refresh_token", refreshToken));
    }

    // --- login БЕЗ Sec-Fetch-Site (небраузерный клиент): JSON token есть ---

    @Test
    void login_noSecFetchSiteHeader_jsonTokenPresent_byteIdenticalToCookie() throws Exception {
        UUID id = createUser("sec70-nosfs");
        MvcResult login = mockMvc.perform(loginRequest(usernameOf(id)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie accessCookie = login.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie).isNotNull();

        JsonNode body = mapper.readTree(login.getResponse().getContentAsString());
        String jsonToken = body.get("token").asText();
        assertThat(jsonToken)
                .as("login without Sec-Fetch-Site (non-browser client) must echo the token in JSON")
                .isNotBlank();
        assertThat(jsonToken)
                .as("JSON token must be byte-identical to the cookie (old behavior)")
                .isEqualTo(accessCookie.getValue());
    }

    // --- login С Sec-Fetch-Site: same-origin (SPA и XSS на нём): JSON token НЕТ ---

    @Test
    void login_sameOrigin_jsonTokenNull_cookiePresent() throws Exception {
        UUID id = createUser("sec70-sameorigin");
        MvcResult login = mockMvc.perform(loginRequest(usernameOf(id))
                        .header(AuthResource.SEC_FETCH_SITE_HEADER,
                                AuthResource.SEC_FETCH_SITE_SAME_ORIGIN))
                .andExpect(status().isOk())
                .andReturn();

        Cookie accessCookie = login.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie)
                .as("login with Sec-Fetch-Site: same-origin must still set the httpOnly access cookie")
                .isNotNull();
        assertThat(accessCookie.getValue()).isNotBlank();

        JsonNode body = mapper.readTree(login.getResponse().getContentAsString());
        assertThat(body.has("token"))
                .as("token field stays in the contract (null, not removed)")
                .isTrue();
        assertThat(body.get("token").isNull())
                .as("login with Sec-Fetch-Site: same-origin must NOT echo the token in JSON")
                .isTrue();

        // the cookie alone authenticates — cookie-only SPA flow intact
        mockMvc.perform(get("/auth/me")
                        .cookie(new Cookie("__Host-zbpm_token", accessCookie.getValue())))
                .andExpect(status().isOk());
    }

    // --- login С Sec-Fetch-Site: cross-site (внешний фронт): JSON token есть ---

    @Test
    void login_crossSite_jsonTokenPresent_byteIdenticalToCookie() throws Exception {
        UUID id = createUser("sec70-crosssite");
        MvcResult login = mockMvc.perform(loginRequest(usernameOf(id))
                        .header(AuthResource.SEC_FETCH_SITE_HEADER, "cross-site"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie accessCookie = login.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie).isNotNull();

        JsonNode body = mapper.readTree(login.getResponse().getContentAsString());
        String jsonToken = body.get("token").asText();
        assertThat(jsonToken)
                .as("login with Sec-Fetch-Site: cross-site (external frontend) must echo the token in JSON")
                .isNotBlank();
        assertThat(jsonToken)
                .as("JSON token must be byte-identical to the cookie (old behavior)")
                .isEqualTo(accessCookie.getValue());
    }

    // --- ATTACK-сценарий: same-origin + поддельный X-Auth-Transport (XSS пишет
    //     кастомные заголовки сам) — всё равно НЕТ token. Именно эта мутация
    //     (OR по кастомному заголовку поверх Sec-Fetch-Site) переоткрыла бы дыру
    //     раунда 2: браузер всё равно штампует same-origin, и гейт держит. ---

    @Test
    void login_sameOrigin_plusForgedCustomHeader_stillNoJsonToken() throws Exception {
        UUID id = createUser("sec70-forged");
        MvcResult login = mockMvc.perform(loginRequest(usernameOf(id))
                        .header(AuthResource.SEC_FETCH_SITE_HEADER,
                                AuthResource.SEC_FETCH_SITE_SAME_ORIGIN)
                        .header("X-Auth-Transport", "bearer"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie accessCookie = login.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie)
                .as("forged-header attack must still set the httpOnly access cookie")
                .isNotNull();

        JsonNode body = mapper.readTree(login.getResponse().getContentAsString());
        assertThat(body.get("token").isNull())
                .as("Sec-Fetch-Site: same-origin + forged X-Auth-Transport must still NOT echo the token — "
                        + "a custom header settable by page JS must never override the browser-computed gate")
                .isTrue();
    }

    // --- refresh БЕЗ Sec-Fetch-Site: ротация cookie + JSON token есть ---

    @Test
    void refresh_noSecFetchSiteHeader_jsonTokenPresent_cookieRotated() throws Exception {
        UUID id = createUser("sec70-refnosfs");
        MvcResult login = mockMvc.perform(loginRequest(usernameOf(id)))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = login.getResponse().getCookie("refresh_token").getValue();
        assertThat(refreshToken).isNotBlank();

        MvcResult refresh = mockMvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isOk())
                .andReturn();

        Cookie accessCookie = refresh.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie)
                .as("refresh without Sec-Fetch-Site must still rotate the httpOnly access cookie")
                .isNotNull();
        assertThat(accessCookie.getValue()).isNotBlank();

        JsonNode body = mapper.readTree(refresh.getResponse().getContentAsString());
        String jsonToken = body.get("token").asText();
        assertThat(jsonToken)
                .as("refresh without Sec-Fetch-Site (non-browser client) must echo the token in JSON")
                .isNotBlank();
        assertThat(jsonToken)
                .as("JSON token must be byte-identical to the cookie (old behavior)")
                .isEqualTo(accessCookie.getValue());
    }

    // --- refresh С Sec-Fetch-Site: same-origin: ротация cookie, JSON token НЕТ ---

    @Test
    void refresh_sameOrigin_jsonTokenNull_cookieRotated() throws Exception {
        UUID id = createUser("sec70-refsameorigin");
        MvcResult login = mockMvc.perform(loginRequest(usernameOf(id)))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = login.getResponse().getCookie("refresh_token").getValue();
        assertThat(refreshToken).isNotBlank();

        MvcResult refresh = mockMvc.perform(refreshRequest(refreshToken)
                        .header(AuthResource.SEC_FETCH_SITE_HEADER,
                                AuthResource.SEC_FETCH_SITE_SAME_ORIGIN))
                .andExpect(status().isOk())
                .andReturn();

        Cookie accessCookie = refresh.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie)
                .as("refresh with Sec-Fetch-Site: same-origin must still rotate the httpOnly access cookie")
                .isNotNull();
        assertThat(accessCookie.getValue()).isNotBlank();

        JsonNode body = mapper.readTree(refresh.getResponse().getContentAsString());
        assertThat(body.has("token")).isTrue();
        assertThat(body.get("token").isNull())
                .as("refresh with Sec-Fetch-Site: same-origin must NOT echo the token in JSON")
                .isTrue();
    }

    // --- refresh С Sec-Fetch-Site: cross-site: JSON token есть ---

    @Test
    void refresh_crossSite_jsonTokenPresent_byteIdenticalToCookie() throws Exception {
        UUID id = createUser("sec70-refcrosssite");
        MvcResult login = mockMvc.perform(loginRequest(usernameOf(id)))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = login.getResponse().getCookie("refresh_token").getValue();
        assertThat(refreshToken).isNotBlank();

        MvcResult refresh = mockMvc.perform(refreshRequest(refreshToken)
                        .header(AuthResource.SEC_FETCH_SITE_HEADER, "cross-site"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie accessCookie = refresh.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie).isNotNull();

        JsonNode body = mapper.readTree(refresh.getResponse().getContentAsString());
        String jsonToken = body.get("token").asText();
        assertThat(jsonToken)
                .as("refresh with Sec-Fetch-Site: cross-site must echo the token in JSON")
                .isNotBlank();
        assertThat(jsonToken)
                .as("JSON token must be byte-identical to the cookie (old behavior)")
                .isEqualTo(accessCookie.getValue());
    }
}
