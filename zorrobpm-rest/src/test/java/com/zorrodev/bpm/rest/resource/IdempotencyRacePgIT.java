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
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-REL-21 (race criterion): N concurrent {@code POST /process-instances} with one
 * {@code Idempotency-Key} create exactly ONE instance — the advisory lock serializes
 * same-key requests, losers replay the winner without executing. Deterministic by
 * construction (not by timing): with the fix every timing yields 1 instance; without
 * the filter every timing yields N (POF: disable the registration → N instances).
 *
 * <p>PG-only: advisory locks are skipped on H2, so this runs in the pg group.
 * Excluded from the default H2 verify via rest failsafe excludedGroups.
 */
@Tag("pg")
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class IdempotencyRacePgIT {

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
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");
        String procKey = "idemrace-" + UUID.randomUUID().toString().substring(0, 8);
        deployProcess(procKey);
        raceProcKey = procKey;
    }

    private String raceProcKey;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void concurrentSameKey_createsSingleInstance() throws Exception {
        int threads = 8;
        String key = UUID.randomUUID().toString();
        String body = "{\"processDefinitionKey\":\"" + raceProcKey + "\",\"variables\":[]}";

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    go.await(30, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/process-instances")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", key)
                            .content(body)
                            .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isCreated())
                        .andReturn();
                } catch (Throwable t) {
                    errors.add(t);
                    return null;
                }
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).as("all racers finished").isTrue();
        assertThat(errors).as("no racer failed: %s", errors).isEmpty();

        List<String> bodies = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            String content = f.get().getResponse().getContentAsString();
            bodies.add(content);
            ids.add(mapper.readTree(content).get("id").asText());
        }
        assertThat(new HashSet<>(bodies))
            .as("all racers got the identical replayed response")
            .hasSize(1);
        assertThat(new HashSet<>(ids))
            .as("exactly one instance created for one key")
            .hasSize(1);
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
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
