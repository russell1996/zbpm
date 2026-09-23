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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-70: explicit opt-in for the JSON copy of the access token.
 *
 * <p>{@code POST /auth/login} and {@code POST /auth/refresh} always set the
 * httpOnly access cookie; the {@code token} field in the JSON body is only
 * echoed when the caller sends {@code X-Auth-Transport: bearer}. Every test
 * uses its OWN dedicated user (P-8: no seeded-admin mutation, no cross-test
 * coupling).
 *
 * <p>POF: remove the strip/guard in {@code AuthResource} (always echo the
 * token) — the without-header tests go RED (token unexpectedly present) while
 * the with-header tests stay GREEN, proving the tests distinguish both paths.
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

    private MvcResult doLogin(String username, boolean withOptInHeader) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(PASS);
        var req = post("/auth/login")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON);
        if (withOptInHeader) {
            req = req.header(AuthResource.AUTH_TRANSPORT_HEADER, AuthResource.AUTH_TRANSPORT_BEARER);
        }
        return mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult doRefresh(String refreshToken, boolean withOptInHeader) throws Exception {
        var req = post("/auth/refresh")
                .cookie(new Cookie("refresh_token", refreshToken));
        if (withOptInHeader) {
            req = req.header(AuthResource.AUTH_TRANSPORT_HEADER, AuthResource.AUTH_TRANSPORT_BEARER);
        }
        return mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn();
    }

    // --- login WITHOUT the header: cookie present, JSON token null ---

    @Test
    void login_withoutOptInHeader_jsonTokenNull_cookiePresent() throws Exception {
        UUID id = createUser("sec70-nohdr");
        MvcResult login = doLogin(usernameOf(id), false);

        Cookie accessCookie = login.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie)
                .as("login without opt-in must still set the httpOnly access cookie")
                .isNotNull();
        assertThat(accessCookie.getValue()).isNotBlank();

        JsonNode body = mapper.readTree(login.getResponse().getContentAsString());
        assertThat(body.has("token"))
                .as("token field stays in the contract (null, not removed)")
                .isTrue();
        assertThat(body.get("token").isNull())
                .as("login without X-Auth-Transport: bearer must NOT echo the token in JSON")
                .isTrue();

        // the cookie alone authenticates — cookie-only SPA flow intact
        mockMvc.perform(get("/auth/me")
                        .cookie(new Cookie("__Host-zbpm_token", accessCookie.getValue())))
                .andExpect(status().isOk());
    }

    // --- login WITH the header: JSON token present, byte-identical to cookie ---

    @Test
    void login_withOptInHeader_jsonTokenPresent_byteIdenticalToCookie() throws Exception {
        UUID id = createUser("sec70-hdr");
        MvcResult login = doLogin(usernameOf(id), true);

        Cookie accessCookie = login.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie).isNotNull();

        JsonNode body = mapper.readTree(login.getResponse().getContentAsString());
        String jsonToken = body.get("token").asText();
        assertThat(jsonToken)
                .as("login with X-Auth-Transport: bearer must echo the token in JSON")
                .isNotBlank();
        assertThat(jsonToken)
                .as("opt-in JSON token must be byte-identical to the cookie (old behavior)")
                .isEqualTo(accessCookie.getValue());
    }

    // --- refresh WITHOUT the header: rotated cookie present, JSON token null ---

    @Test
    void refresh_withoutOptInHeader_jsonTokenNull_cookieRotated() throws Exception {
        UUID id = createUser("sec70-refnohdr");
        MvcResult login = doLogin(usernameOf(id), false);
        String refreshToken = login.getResponse().getCookie("refresh_token").getValue();
        assertThat(refreshToken).isNotBlank();

        MvcResult refresh = doRefresh(refreshToken, false);

        Cookie accessCookie = refresh.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie)
                .as("refresh without opt-in must still rotate the httpOnly access cookie")
                .isNotNull();
        assertThat(accessCookie.getValue()).isNotBlank();

        JsonNode body = mapper.readTree(refresh.getResponse().getContentAsString());
        assertThat(body.has("token")).isTrue();
        assertThat(body.get("token").isNull())
                .as("refresh without X-Auth-Transport: bearer must NOT echo the token in JSON")
                .isTrue();
    }

    // --- refresh WITH the header: JSON token present, byte-identical to cookie ---

    @Test
    void refresh_withOptInHeader_jsonTokenPresent_byteIdenticalToCookie() throws Exception {
        UUID id = createUser("sec70-refhdr");
        MvcResult login = doLogin(usernameOf(id), false);
        String refreshToken = login.getResponse().getCookie("refresh_token").getValue();
        assertThat(refreshToken).isNotBlank();

        MvcResult refresh = doRefresh(refreshToken, true);

        Cookie accessCookie = refresh.getResponse().getCookie("__Host-zbpm_token");
        assertThat(accessCookie).isNotNull();

        JsonNode body = mapper.readTree(refresh.getResponse().getContentAsString());
        String jsonToken = body.get("token").asText();
        assertThat(jsonToken)
                .as("refresh with X-Auth-Transport: bearer must echo the token in JSON")
                .isNotBlank();
        assertThat(jsonToken)
                .as("opt-in JSON token must be byte-identical to the cookie (old behavior)")
                .isEqualTo(accessCookie.getValue());
    }
}
