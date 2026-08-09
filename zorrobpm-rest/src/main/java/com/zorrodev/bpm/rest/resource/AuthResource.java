package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.AuthContract;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.entity.RefreshTokenEntity;
import com.zorrodev.bpm.engine.repository.RefreshTokenRepository;
import com.zorrodev.bpm.engine.security.TokenService;
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

@RestController
@RequiredArgsConstructor
public class AuthResource implements AuthContract {

    private final UiUserService userService;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final TokenService tokenService;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${zorrobpm.security.cookie-secure:true}")
    private boolean cookieSecure;

    @Value("${zorrobpm.security.refresh-ttl-days:7}")
    private long refreshTtlDays;

    @Value("${zorrobpm.security.jwt-ttl-minutes:30}")
    private long jwtTtlMinutes;

    @Override
    public AuthResponse login(@RequestBody LoginDTO dto) {
        AuthResponse authResponse = userService.login(dto)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        // Set httpOnly cookie for access token
        Cookie cookie = new Cookie("zbpm_token", authResponse.getToken());
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge((int) (jwtTtlMinutes * 60)); // S15: sync with JWT expiry
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);

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

            // Set refresh token httpOnly cookie
            Cookie refreshCookie = new Cookie("refresh_token", refreshToken);
            refreshCookie.setHttpOnly(true);
            refreshCookie.setSecure(cookieSecure);
            refreshCookie.setPath("/");
            refreshCookie.setMaxAge((int) (refreshTtlDays * 24 * 60 * 60));
            refreshCookie.setAttribute("SameSite", "Strict");
            response.addCookie(refreshCookie);
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

        // Get user info
        var user = userService.getById(found.getUserId());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }

        // Issue new access token
        String newAccessToken = tokenService.issue(user.getId(), user.getUsername(), user.getRole());

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

        // Set new refresh cookie
        Cookie refreshCookie = new Cookie("refresh_token", newRefreshToken);
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(cookieSecure);
        refreshCookie.setPath("/");
        refreshCookie.setMaxAge((int) (refreshTtlDays * 24 * 60 * 60));
        refreshCookie.setAttribute("SameSite", "Strict");
        response.addCookie(refreshCookie);

        AuthResponse authResponse = new AuthResponse();
        authResponse.setToken(newAccessToken);
        return authResponse;
    }

    @Override
    @Transactional
    public void logout() {
        // WO-SEC-18 L6: use access token OR refresh token as identity source
        UUID userId = null;

        // Try access token first
        TokenService.Claims claims = (TokenService.Claims) request.getAttribute("authClaims");
        if (claims != null) {
            userId = claims.userId();
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
            refreshTokenRepository.revokeAllByUserId(userId);
        }

        // S4: Clear access cookie (zbpm_token)
        Cookie clearAccess = new Cookie("zbpm_token", "");
        clearAccess.setPath("/");
        clearAccess.setMaxAge(0);
        clearAccess.setHttpOnly(true);
        response.addCookie(clearAccess);

        // Clear refresh cookie
        Cookie clearRefresh = new Cookie("refresh_token", "");
        clearRefresh.setPath("/");
        clearRefresh.setMaxAge(0);
        clearRefresh.setHttpOnly(true);
        response.addCookie(clearRefresh);
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
}
