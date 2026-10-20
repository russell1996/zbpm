package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-AUDIT-14 (F-16) on REAL PostgreSQL: the idempotent replay must be the SAME
 * answer as the original one — including the {@code Location} header.
 *
 * <p>Why a PG class and not only the H2 one: the F-16 fix is a DATABASE change
 * (a new column {@code idempotency_record.response_headers}, changeset
 * {@code 20261020-122}). On H2 the column exists only because H2 is fed by the
 * same Liquibase changelog, but the production DB is PostgreSQL 16, and the
 * value that is persisted there is what the replay restores. H2-green is not
 * prod-green (V11, P-17) — so the whole chain (JwtAuthFilter → IdempotencyFilter
 * with its transaction + advisory lock → controller → the row in PostgreSQL →
 * the replayed response) is exercised here against postgres:16.
 *
 * <p>What is pinned:
 * <ul>
 *   <li>the replay answers 201 with the SAME {@code Location} and the same body;</li>
 *   <li>the row in PostgreSQL really carries the {@code Location} in the new
 *       column (read with raw SQL, not through the entity — the value must be in
 *       the DATABASE, not only in the JPA cache);</li>
 *   <li>nothing outside the allowlist ({@code Location}, {@code ETag}) is
 *       persisted — a session cookie must never reach the idempotency table.</li>
 * </ul>
 */
@Tag("pg")
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class IdempotencyReplayHeadersPgIT {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
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
        registry.add("spring.hikari.connection-timeout", () -> "60000");
    }

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        return v != null ? v : dflt;
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");
    }

    @Test
    void replay_sameKey_returnsTheSameLocationHeader_onPostgres() throws Exception {
        String procKey = "audit14-pg-loc-" + UUID.randomUUID().toString().substring(0, 8);
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        String firstLocation = first.getResponse().getHeader("Location");
        assertThat(firstLocation)
            .as("the original answer carries the Location of the created instance")
            .isNotNull()
            .startsWith("/process-instances/");

        MvcResult replay = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        assertThat(replay.getResponse().getHeader("Location"))
            .as("the replay must answer with the SAME Location — otherwise the retry "
                + "is a different answer than the original")
            .isEqualTo(firstLocation);
        assertThat(replay.getResponse().getContentAsString())
            .as("the body is the same answer too")
            .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void persistedRow_inPostgres_carriesTheLocation_andNoCookie() throws Exception {
        String procKey = "audit14-pg-row-" + UUID.randomUUID().toString().substring(0, 8);
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        String location = first.getResponse().getHeader("Location");

        // Raw SQL: the value must be IN THE DATABASE, not only in the JPA cache.
        List<String> stored = jdbcTemplate.queryForList(
            "SELECT response_headers FROM idempotency_record "
                + "WHERE idem_key = ? AND endpoint = '/process-instances'",
            String.class, key);
        assertThat(stored)
            .as("the idempotency row must exist in PostgreSQL")
            .hasSize(1);
        String headersJson = stored.get(0);
        assertThat(headersJson)
            .as("the Location must be persisted in the new column on PostgreSQL")
            .isNotNull()
            .contains(location);

        JsonNode headers = mapper.readTree(headersJson);
        List<String> names = new ArrayList<>();
        headers.fieldNames().forEachRemaining(names::add);
        assertThat(names)
            .as("only the allowlist may be persisted — a session cookie must never "
                + "reach the idempotency table")
            .allMatch(name -> "Location".equals(name) || "ETag".equals(name));
        assertThat(headersJson.toLowerCase())
            .as("no cookie may be stored")
            .doesNotContain("set-cookie")
            .doesNotContain("cookie");
    }

    // ==================== helpers (proven shapes) ====================

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

    private void deployProcess(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));
        bpmn = bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                   .replace("name=\"Process 1\"", "name=\"" + key + "\"")
                   .replace("process id=\"process1\"", "process id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }
}
