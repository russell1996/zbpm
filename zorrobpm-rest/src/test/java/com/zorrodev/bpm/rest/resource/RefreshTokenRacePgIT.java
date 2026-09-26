package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-55: refresh-token rotation race on REAL PostgreSQL (not H2 — see
 * [[pg-vs-h2-divergence]]). Two parallel /auth/refresh calls with the same token must
 * produce exactly one 200 + one 401. On the pre-fix code both threads pass the plain
 * SELECT and both issue successors (double-spend).
 *
 * Run locally (PostgresIT-compatible env, default 127.0.0.1:55432 per ci/run-pg-tests.sh):
 * <pre>
 * docker compose -f ci/docker-compose.pg.yml -p zbpm-pgci up -d
 * docker run --rm --network host -v "${PWD}:/build" -w /build -v "zbpm_m2:/root/.m2" \
 *   -e PG_HOST=127.0.0.1 -e PG_PORT=55432 -e PG_DB=zorrobpm-db -e PG_USER=zorrodev \
 *   -e PG_PASSWORD=zorrodev maven:3.9.9-eclipse-temurin-21 mvn -B -ntp verify \
 *   -pl zorrobpm-rest -am -Dgroups=pg -Dzbpm.excludedGroups= -Dsurefire.failIfNoSpecifiedTests=false
 * docker compose -f ci/docker-compose.pg.yml -p zbpm-pgci down -v
 * </pre>
 */
@Tag("pg")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RefreshTokenRacePgIT {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // Same env-first resolution as engine PostgresIT (PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD).
    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        return v != null ? v : dflt;
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String host = cfg("PG_HOST", "127.0.0.1");
        String port = cfg("PG_PORT", "55432");
        String db = cfg("PG_DB", "zorrobpm-db");
        String user = cfg("PG_USER", "zorrodev");
        String pass = cfg("PG_PASSWORD", "zorrodev");

        registry.add("spring.datasource.url",
            () -> "jdbc:postgresql://" + host + ":" + port + "/" + db + "?sslmode=disable");
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pass);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.liquibase.enabled", () -> "true");
    }

    private LoginDTO validLogin() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        return dto;
    }

    private String extractCookieFromHeaders(Collection<String> setCookieHeaders, String cookieName) {
        for (String header : setCookieHeaders) {
            if (header.contains(cookieName + "=")) {
                return header.split(cookieName + "=")[1].split(";")[0];
            }
        }
        return null;
    }

    private record LoginResult(String accessToken, String refreshToken) {}

    private LoginResult loginWithRefreshToken() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(validLogin()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse auth = mapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);
        Collection<String> headers = loginResult.getResponse().getHeaders("Set-Cookie");
        String refreshToken = extractCookieFromHeaders(headers, "refresh_token");
        return new LoginResult(auth.getToken(), refreshToken);
    }

    /** Fires two REAL parallel /auth/refresh calls with the same token. */
    private List<MvcResult> runTwoParallelRefreshes(String refreshToken) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<MvcResult> results = new CopyOnWriteArrayList<>();

        for (int t = 0; t < 2; t++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await(5, TimeUnit.SECONDS);
                    MvcResult r = mockMvc.perform(post("/auth/refresh")
                                    .cookie(new Cookie("refresh_token", refreshToken)))
                            .andReturn();
                    results.add(r);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }, "pg-refresh-race-" + t);
            thread.start();
        }

        go.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS))
                .as("both racing threads must finish")
                .isTrue();
        return results;
    }

    // --- Criterion #1 (POF) on real PostgreSQL: exactly one 200, one 401 ---

    @Test
    void twoParallelRefreshes_sameToken_exactlyOneWins() throws Exception {
        for (int i = 0; i < 3; i++) {
            LoginResult login = loginWithRefreshToken();
            assertThat(login.refreshToken()).isNotNull();

            List<MvcResult> results = runTwoParallelRefreshes(login.refreshToken());
            assertThat(results).hasSize(2);

            List<Integer> statuses = results.stream()
                    .map(r -> r.getResponse().getStatus())
                    .sorted()
                    .toList();
            assertThat(statuses)
                    .as("PG race #%d: exactly one refresh must win, the other must be rejected", i)
                    .containsExactly(200, 401);
        }
    }

    // --- Criterion #2 on real PostgreSQL: winner's successor keeps working ---

    @Test
    void winningSuccessorToken_stillWorksForNextRefresh() throws Exception {
        LoginResult login = loginWithRefreshToken();
        assertThat(login.refreshToken()).isNotNull();

        List<MvcResult> results = runTwoParallelRefreshes(login.refreshToken());
        MvcResult winner = results.stream()
                .filter(r -> r.getResponse().getStatus() == 200)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 200 winner in the race"));

        String successor = extractCookieFromHeaders(winner.getResponse().getHeaders("Set-Cookie"), "refresh_token");
        assertThat(successor).isNotNull().isNotBlank();

        mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("refresh_token", successor)))
                .andExpect(status().isOk());
    }
}
