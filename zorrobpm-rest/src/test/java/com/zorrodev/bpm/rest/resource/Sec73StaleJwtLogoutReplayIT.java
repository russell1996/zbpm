package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-73 (N05): отозванный access JWT остаётся пригоден для повторного
 * account-wide logout.
 *
 * <p>Механизм: logout-ветка {@code JwtAuthFilter} ставит {@code authClaims} по
 * криптографической валидности БЕЗ live-проверки active/version/role, а
 * {@code AuthResource.logout} по этим claims бампит {@code tokenVersion} и
 * отзывает refresh-сессии. Копия ещё неистёкшего JWT после законного отзыва
 * обычным endpoint'ам уже не проходит (version-check), но повторный
 * {@code POST /auth/logout} тем же токеном заново отзывает НОВЫЕ сессии.
 *
 * <p>P-67: мутация, которая обязана валить этот тест — убрать live-гейт в
 * {@code AuthResource.logout} (всегда {@code userId = claims.userId()} без
 * сверки с {@code securityState}). Тогда replay бампит версию ещё раз и v2
 * умирает: ассерты на КОНКРЕТНЫЕ значения ({@code versionAfterReplay ==
 * versionBeforeReplay}, {@code /auth/me} v2 → 200) краснеют, а не «что-то
 * вообще вызвалось». Каждый тест использует СВОЕГО пользователя (P-8).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Sec73StaleJwtLogoutReplayIT {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final String PASS = "Sec73Passw0rd!secure";

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

    /**
     * Login как небраузерный клиент (БЕЗ {@code Sec-Fetch-Site} — WO-SEC-70:
     * отсутствие заголовка = JSON token отдаётся). Возвращает access token
     * из JSON-тела.
     */
    private String loginToken(String username) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(PASS);
        MvcResult r = mockMvc.perform(post("/auth/login")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        String token = mapper.readValue(r.getResponse().getContentAsString(), AuthResponse.class).getToken();
        assertThat(token).as("login must issue a JSON access token (non-browser client)").isNotBlank();
        return token;
    }

    private int tokenVersionOf(UUID id) {
        return userRepository.findById(id).orElseThrow().getTokenVersion();
    }

    // ==================== Критерий 1: replay stale v1 — безопасный no-op ====================

    @Test
    void replayLogoutWithStaleToken_doesNotInvalidateNewSession() throws Exception {
        UUID id = createUser("sec73-replay");
        String username = usernameOf(id);

        // v1 — токен до отзыва.
        String v1 = loginToken(username);
        int versionAfterLogin = tokenVersionOf(id);

        // Законный logout токеном v1 — бампит версию ровно на 1.
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + v1))
            .andExpect(status().isOk());
        int versionAfterLogout = tokenVersionOf(id);
        assertThat(versionAfterLogout)
            .as("legitimate logout must bump token_version exactly once")
            .isEqualTo(versionAfterLogin + 1);

        // v1 мёртв для обычных endpoint'ов.
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + v1))
            .andExpect(status().isUnauthorized());

        // Новая валидная сессия v2.
        String v2 = loginToken(username);
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + v2))
            .andExpect(status().isOk());
        int versionBeforeReplay = tokenVersionOf(id);

        // REPLAY: тот же отозванный v1, БЕЗ cookies (чистый stolen-token replay —
        // в запросе нет живого credential, только stale Bearer).
        MvcResult replay = mockMvc.perform(
                post("/auth/logout").header("Authorization", "Bearer " + v1))
            .andExpect(status().isOk())
            .andReturn();

        // Безопасный no-op: версия НЕ выросла заново ...
        int versionAfterReplay = tokenVersionOf(id);
        assertThat(versionAfterReplay)
            .as("stale-token logout replay must NOT bump token_version again (no-op, was %d before replay)",
                versionBeforeReplay)
            .isEqualTo(versionBeforeReplay);

        // ... новая сессия v2 жива ...
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + v2))
            .andExpect(status().isOk());

        // ... а сам replay-endpoint остался 200 + cookie-clear (no-op, не 401:
        // SPA-разлогин stale-вкладки не должен ломаться).
        assertThat(replay.getResponse().getHeaders("Set-Cookie"))
            .as("stale replay must still clear cookies (safe no-op, not a rejection)")
            .anySatisfy(h -> assertThat(h).contains("Max-Age=0"));
    }
}
