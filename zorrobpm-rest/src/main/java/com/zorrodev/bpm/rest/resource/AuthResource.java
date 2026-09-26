package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.AuthContract;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.entity.RefreshTokenEntity;
import com.zorrodev.bpm.engine.repository.RefreshTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.security.UiUserLookupService;
import com.zorrodev.bpm.engine.service.UiUserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import jakarta.validation.Valid;

@RestController
@RequiredArgsConstructor
public class AuthResource implements AuthContract {

    private final UiUserService userService;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final TokenService tokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    /** WO-SEC-63 (F01): live user-state projection (active/role/tokenVersion) for refresh. */
    private final UiUserLookupService userLookupService;
    /** WO-SEC-63: token_version bump on logout (invalidates all outstanding access tokens). */
    private final UiUserRepository uiUserRepository;
    /** WO-SEC-67 (F13): close live SSE streams on logout (credential behind them is dead). */
    private final SseEventStreamService sseEventStreamService;

    @Value("${zorrobpm.security.cookie-secure:true}")
    private boolean cookieSecure;

    @Value("${zorrobpm.security.refresh-ttl-days:7}")
    private long refreshTtlDays;

    @Value("${zorrobpm.security.jwt-ttl-minutes:30}")
    private long jwtTtlMinutes;

    /**
     * WO-SEC-70 (раунд 3 — исправление решения CTO после G-H red-team пасса):
     * гейт JSON-копии access token по Fetch Metadata, а не по кастомному заголовку.
     * {@code POST /auth/login} и {@code POST /auth/refresh} всегда ставят
     * httpOnly access cookie; поле {@code token} в JSON-теле отдаётся только когда
     * запрос пришёл НЕ из same-origin браузерного контекста.
     *
     * <p>Почему не {@code X-Auth-Transport} (раунд 2, отозван): кастомный заголовок
     * ставит любой JS на странице, включая XSS-пейлоад — он сам добавил бы его к
     * своему запросу на {@code /auth/refresh} (httpOnly refresh-cookie браузер
     * приложил бы автоматически) и получил бы {@code token} в JSON. «SPA сам не
     * шлёт заголовок» не равно «XSS не может его выставить» — гейт не держал ту
     * атаку, ради которой заведена задача.
     *
     * <p>{@code Sec-Fetch-Site} вычисляет и подставляет сам браузер (Fetch Metadata
     * Request Headers) — JS не может ни установить, ни переопределить это имя
     * (forbidden header name). Запрос со страницы SPA к своему же бэкенду —
     * {@code same-origin} и у легитимного кода, и у XSS на той же странице:
     * различить их нельзя и не нужно, оба получают одинаковый безопасный ответ
     * без token. Небраузерные клиенты (curl, SDK, server-to-server) этот заголовок
     * вообще не шлют — получают token как раньше, ничего настраивать не надо.
     * Совпадение — exact match, детерминировано, тестируется тривиально.
     */
    public static final String SEC_FETCH_SITE_HEADER = "Sec-Fetch-Site";
    public static final String SEC_FETCH_SITE_SAME_ORIGIN = "same-origin";

    /**
     * WO-SEC-72 (N03): refresh-cookie отдаётся ДВУМЯ Set-Cookie с одним значением —
     * backend-path {@code /auth/refresh} (deployment с API в корне, {@code VITE_API_URL=/},
     * прямые вызовы к бэкенду) и external-path {@code /api/auth/refresh} (SPA за
     * префиксом {@code /api}: nginx {@code location /api/} и vite dev-proxy режут
     * префикс ПОСЛЕ того, как браузер уже выбрал cookie по своему запросному пути,
     * RFC 6265 §5.1.4 — одиночный {@code Path=/auth/refresh} на запросе
     * {@code /api/auth/refresh} браузер не присылает, сессия не обновлялась).
     *
     * <p>Почему не один из двух примеров WO: {@code proxy_cookie_path} в nginx чинит
     * только прод-nginx (dev vite-proxy и внешний edge-proxy остались бы сломанными,
     * у каждого свой конфиг вне этого репо), а «настройка external auth path» требует
     * угадать топологию деплоя конфигом — неверное значение молча ломает refresh.
     * Два Path работают в обоих режимах одновременно без единого конфига; сужение
     * WO-SEC-64 сохранено (оба Path узкие, никакого {@code Path=/}), значения всегда
     * одинаковые — расхождения быть не может, logout чистит оба.
     *
     * <p>Префикс {@code /api} захардкожен сознательно: он уже захардкожен в трёх местах
     * (фронт {@code api.ts} дефолт {@code /api}, {@code vite.config.ts} proxy,
     * {@code nginx.conf} {@code location /api/}) — четвёртое не вводит новой магии.
     */
    public static final String REFRESH_COOKIE_BACKEND_PATH = "/auth/refresh";
    public static final String REFRESH_COOKIE_API_PREFIX_PATH = "/api/auth/refresh";

    @Override
    public AuthResponse login(@Valid @RequestBody LoginDTO dto) {
        AuthResponse authResponse = userService.login(dto)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        // Set httpOnly cookie for access token
        // WO-SEC-63 (F02): single cookie-helper shared with refresh() — the SPA relies on the
        // access COOKIE, not the JSON, so both places must produce byte-identical cookie flags.
        addAccessCookie(authResponse.getToken());

        // Generate and store refresh token
        TokenService.Claims claims = tokenService.verify(authResponse.getToken());
        if (claims != null) {
            String refreshToken = tokenService.generateRefreshToken();
            RefreshTokenEntity entity = new RefreshTokenEntity();
            entity.setId(UUID.randomUUID());
            entity.setUserId(claims.userId());
            entity.setTokenHash(tokenService.hashToken(refreshToken));
            entity.setExpiresAt(Instant.now().plus(refreshTtlDays, ChronoUnit.DAYS));
            entity.setRevoked(false);
            entity.setCreatedAt(Instant.now());
            refreshTokenRepository.save(entity);

            // Set refresh token httpOnly cookie (WO-SEC-72: dual Path, see addRefreshCookie)
            addRefreshCookie(refreshToken, (int) (refreshTtlDays * 24 * 60 * 60));
        }

        // WO-SEC-70 (раунд 3): стираем JSON-копию для same-origin браузерных
        // запросов. Всё выше (access cookie, выпуск refresh) уже использовало
        // настоящий токен — меняется только JSON-эхо, никогда cookie.
        if (!jsonTokenRequested()) {
            authResponse.setToken(null);
        }

        return authResponse;
    }

    @Override
    public UiUser me() {
        TokenService.Claims claims = (TokenService.Claims) request.getAttribute("authClaims");
        if (claims == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        return userService.getById(claims.userId());
    }

    @Override
    public void verify(String requireRole) {
        // WO-OBS-4: nginx auth_request target. The filter already rejected missing/invalid
        // tokens with 401 (same carve-out as me()); here we only translate valid JWT claims
        // into identity headers. Empty body — auth_request ignores it.
        // requireRole: hard role gate for paths nginx cannot evaluate itself (Prometheus
        // has no RBAC; a location-level `if` on the auth_request_set variable fires before
        // the subrequest runs and would 403 everyone). 403 here = valid session, wrong role.
        TokenService.Claims claims = (TokenService.Claims) request.getAttribute("authClaims");
        if (claims == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        if (requireRole != null && !requireRole.equals(claims.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role not authorized");
        }
        response.setHeader("X-Auth-User", claims.username());
        response.setHeader("X-Auth-Role", claims.role());
    }

    @Override
    @Transactional
    public AuthResponse refresh() {
        String refreshTokenValue = extractCookie("refresh_token");
        if (refreshTokenValue == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh token");
        }

        String tokenHash = tokenService.hashToken(refreshTokenValue);

        // S10: reuse-detection — if the token exists but is already revoked
        var anyToken = refreshTokenRepository.findByTokenHash(tokenHash);
        if (anyToken.isPresent() && anyToken.get().isRevoked()) {
            // WO-SEC-18 L7: grace window — if revoked within last 5s, treat as retry (not theft)
            boolean revokedRecently = anyToken.get().getRevokedAt() != null
                && Instant.now().isBefore(anyToken.get().getRevokedAt().plusSeconds(5));
            if (!revokedRecently) {
                // Genuine theft: revoke ALL tokens for this user
                refreshTokenRepository.revokeAllByUserId(anyToken.get().getUserId());
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token revoked — theft detected");
            }
            // Retry within grace window: fall through to find a valid token
        }

        RefreshTokenEntity found = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .orElse(null);

        if (found == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }

        // WO-SEC-63 (F01): refresh must stop working for deactivated users — the API-key path
        // already checks active on every request; the JWT path now does the same here AND in
        // JwtAuthFilter. securityState() replaces the old getById: one PK lookup that also
        // carries the current role + tokenVersion (a role change bumps version and forces a
        // fresh token below with the CURRENT role — a demoted user keeps no stale privileges).
        var state = userLookupService.securityState(found.getUserId());
        if (state.isEmpty() || !state.get().active()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found or deactivated");
        }

        // Issue new access token — carries the CURRENT token_version (WO-SEC-63), so a token
        // issued after logout/password-change is always fresh; pre-change tokens are now invalid.
        String newAccessToken = tokenService.issue(
            state.get().userId(), state.get().username(), state.get().role(), state.get().tokenVersion());

        // WO-SEC-55: atomically claim the old refresh token BEFORE issuing a successor.
        // A single UPDATE wins exactly one of N concurrent rotations; the losers see 0 rows
        // and are rejected (no double-spend). Reuse-detection above (S10/WO-SEC-18 L7) still
        // handles replays of already-revoked tokens.
        int claimed = refreshTokenRepository.markRevokedByTokenHash(tokenHash, Instant.now());
        if (claimed != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token already rotated");
        }

        String newRefreshToken = tokenService.generateRefreshToken();
        RefreshTokenEntity newEntity = new RefreshTokenEntity();
        newEntity.setId(UUID.randomUUID());
        newEntity.setUserId(found.getUserId());
        newEntity.setTokenHash(tokenService.hashToken(newRefreshToken));
        newEntity.setExpiresAt(Instant.now().plus(refreshTtlDays, ChronoUnit.DAYS));
        newEntity.setRevoked(false);
        newEntity.setCreatedAt(Instant.now());
        refreshTokenRepository.save(newEntity);

        // Set new refresh cookie (WO-SEC-72: dual Path, see addRefreshCookie)
        addRefreshCookie(newRefreshToken, (int) (refreshTtlDays * 24 * 60 * 60));

        // WO-SEC-63 (F02): refresh MUST also set the access cookie, exactly like login. The SPA
        // never reads the JSON access-token (HttpOnly cookie is the only transport it uses), so
        // without this the session would die at the next 30-minute boundary despite a successful
        // refresh. Same helper = same HttpOnly/Secure/SameSite/Path/TTL as login.
        addAccessCookie(newAccessToken);

        AuthResponse authResponse = new AuthResponse();
        // WO-SEC-70 (раунд 3): тот же Sec-Fetch-Site гейт, что в login() —
        // access cookie выше ставится всегда, JSON-копия только вне same-origin.
        if (jsonTokenRequested()) {
            authResponse.setToken(newAccessToken);
        }
        return authResponse;
    }

    @Override
    @Transactional
    public void logout() {
        // WO-SEC-18 L6: use access token OR refresh token as identity source
        UUID userId = null;

        // Try access token first.
        // WO-SEC-73 (N05): только LIVE-токен даёт право менять состояние.
        // Logout-ветка JwtAuthFilter ставит authClaims по криптографической
        // валидности БЕЗ live-проверки active/version/role (endpoint обязан
        // остаться 200 + cookie-clear и для stale-токена — иначе разлогин
        // stale-вкладки SPA сломается). Здесь сверяем claims с ТЕКУЩИМ
        // security state — дословно то же условие живости, что фильтр
        // применяет на обычных путях (active + version + role). Stale-токен
        // даёт "нет identity из этого источника", а не текущего юзера:
        // replay v1 после login v2 — безопасный no-op, версия не бампится,
        // сессии v2 не отзываются. Refresh-fallback ниже gated сам по себе
        // (только не-отозванная строка — предсуществующее поведение L6, без
        // expiresAt/active-сверки, в этом WO не меняется): запрос с ЖИВЫМ
        // refresh — живой credential, logout легитимен и при stale Bearer.
        TokenService.Claims claims = (TokenService.Claims) request.getAttribute("authClaims");
        if (claims != null) {
            var state = userLookupService.securityState(claims.userId());
            if (state.isPresent() && state.get().active()
                && state.get().tokenVersion() == claims.tokenVersion()
                && state.get().role().equals(claims.role())) {
                userId = claims.userId();
            }
        }

        // If access token expired/unavailable, derive identity from refresh token
        if (userId == null) {
            String refreshTokenValue = extractCookie("refresh_token");
            if (refreshTokenValue != null) {
                String tokenHash = tokenService.hashToken(refreshTokenValue);
                var refreshToken = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash);
                if (refreshToken.isPresent()) {
                    userId = refreshToken.get().getUserId();
                }
            }
        }

        // Revoke all refresh tokens for identified user
        if (userId != null) {
            // WO-SEC-63: bump the access-token version FIRST — any copied/outstanding access
            // token for this user becomes invalid on the very next JwtAuthFilter check, even if
            // it was already swiped from the browser. Same transaction as the refresh revoke.
            uiUserRepository.incrementTokenVersion(userId);
            refreshTokenRepository.revokeAllByUserId(userId);
            // WO-SEC-67 (F13): the bumped version kills the credential behind every
            // open SSE stream of this user — close them now, not on next event.
            // Best-effort AFTER the commit-critical writes, never before them.
            sseEventStreamService.invalidateStreams();
        }

        // S4: Clear access cookie (both __Host- and legacy names — a clear must
        // match the cookie's name AND Path, otherwise the browser keeps it)
        for (String name : new String[]{"__Host-zbpm_token", "zbpm_token"}) {
            Cookie clearAccess = new Cookie(name, "");
            clearAccess.setPath("/");
            clearAccess.setMaxAge(0);
            clearAccess.setHttpOnly(true);
            if (cookieSecure) {
                // __Host- requires Secure; legacy clear keeps the old flag behavior
                clearAccess.setSecure(true);
            }
            clearAccess.setAttribute("SameSite", "Strict");
            response.addCookie(clearAccess);
        }

        // Clear refresh cookie (WO-SEC-72: clear-paths must match BOTH set-paths,
        // otherwise the browser keeps the surviving one)
        clearRefreshCookies();
    }

    /**
     * WO-SEC-72 (N03): ставит refresh-cookie двумя Set-Cookie с ОДИНАКОВЫМ значением —
     * {@code Path=/auth/refresh} первым (порядок сохранён: существующие тесты читают
     * первую куку по имени) и {@code Path=/api/auth/refresh} вторым.
     *
     * <p>Флаги побайтово те же, что были у одиночной куки WO-SEC-64
     * (HttpOnly/Secure/SameSite=Strict/TTL) — меняется только Path-дубль.
     * {@code __Host-}-cookie здесь НЕ трогается (запрет WO, п.2 задачи).
     */
    private void addRefreshCookie(String refreshToken, int maxAgeSeconds) {
        for (String path : new String[]{REFRESH_COOKIE_BACKEND_PATH, REFRESH_COOKIE_API_PREFIX_PATH}) {
            Cookie refreshCookie = new Cookie("refresh_token", refreshToken);
            refreshCookie.setHttpOnly(true);
            refreshCookie.setSecure(cookieSecure);
            refreshCookie.setPath(path);
            refreshCookie.setMaxAge(maxAgeSeconds);
            refreshCookie.setAttribute("SameSite", "Strict");
            response.addCookie(refreshCookie);
        }
    }

    /**
     * WO-SEC-72 (N03): гасит ОБА Path из {@link #addRefreshCookie} (Max-Age=0).
     * Clear обязан совпадать с set по имени И Path — иначе браузер молча оставляет куку.
     */
    private void clearRefreshCookies() {
        for (String path : new String[]{REFRESH_COOKIE_BACKEND_PATH, REFRESH_COOKIE_API_PREFIX_PATH}) {
            Cookie clearRefresh = new Cookie("refresh_token", "");
            clearRefresh.setPath(path);
            clearRefresh.setMaxAge(0);
            clearRefresh.setHttpOnly(true);
            clearRefresh.setSecure(cookieSecure);
            clearRefresh.setAttribute("SameSite", "Strict");
            response.addCookie(clearRefresh);
        }
    }

    private String extractCookie(String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * WO-SEC-70 (раунд 3): отдавать ли JSON-копию access token. Читает инжектированный
     * request — сигнатуры контракта не меняются.
     *
     * @return true если заголовка {@code Sec-Fetch-Site} нет вообще (небраузерный
     *         клиент) или он отличен от {@code same-origin} ({@code cross-site} /
     *         {@code same-site} / {@code none} — все получают token); false только
     *         для {@code same-origin} — легитимный SPA и XSS на нём получают
     *         одинаковый ответ без token
     */
    private boolean jsonTokenRequested() {
        String site = request.getHeader(SEC_FETCH_SITE_HEADER);
        return site == null || !SEC_FETCH_SITE_SAME_ORIGIN.equals(site);
    }

    /**
     * WO-SEC-63 (F02): single place that sets the HttpOnly access cookie.
     * login() and refresh() must produce byte-identical attributes — the SPA only ever sends
     * this cookie, so a JSON-only refresh would silently log the user out at the TTL boundary.
     *
     * <p>WO-SEC-64 (S-3): {@code __Host-} prefix — the browser then enforces Secure +
     * Path=/ + no-Domain on its side (defense in depth over SameSite=Strict).
     * The legacy {@code zbpm_token} name is cleared on logout but no longer set:
     * {@code JwtAuthFilter} still accepts it as fallback during rotation.
     */
    private void addAccessCookie(String token) {
        Cookie cookie = new Cookie("__Host-zbpm_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) (jwtTtlMinutes * 60)); // sync with JWT expiry
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }
}
