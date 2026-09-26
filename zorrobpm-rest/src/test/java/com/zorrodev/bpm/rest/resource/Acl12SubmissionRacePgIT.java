package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.contract.dto.SubmitProcessSubmissionDTO;
import com.zorrodev.bpm.engine.entity.ProcessSubmissionEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessSubmissionRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ACL-12 criterion 2 on REAL PostgreSQL (H2 has no partial unique index — see
 * [[pg-vs-h2-divergence]]). Several (6, as on the stand) parallel /process-submissions calls
 * with the SAME process key must produce exactly one 201 and 409s for the rest
 * (PENDING_SUBMISSION_EXISTS, never a 500), and exactly one PENDING row in the DB. On the
 * pre-fix code multiple threads pass the existence check and all insert (six rows on the
 * stand) — the partial unique index uk_process_submission__pending_key is what makes the
 * invariant hold under concurrency. 6 threads x 5 rounds make the pre-fix failure
 * (almost) deterministic: with no index the chance that none of the 5 rounds ever overlaps
 * two existence checks before the first insert is negligible.
 *
 * Also asserts (criterion 3, PG half) that the partial predicate does NOT block a new PENDING
 * row while an APPROVED row for the same key exists — a full unique index would.
 *
 * Run: see governance/runbooks/pg-it-run.md (docker compose -f ci/docker-compose.pg.yml ...).
 */
@Tag("pg")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class Acl12SubmissionRacePgIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessSubmissionRepository submissionRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<UUID> createdSubmissionIds = new ArrayList<>();

    private String userToken;
    private UUID userId;
    private String adminToken;

    // Same env-first resolution as RefreshTokenRacePgIT (PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD).
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

    @BeforeAll
    void setUp() throws Exception {
        userId = createOrUpdateUser("acl12pg-user", "PG User", "pg12@test.com", "USER");
        userToken = login("acl12pg-user");
        createOrUpdateUser("acl12pg-admin", "PG Admin", "pgadmin12@test.com", "SUPER_ADMIN");
        adminToken = login("acl12pg-admin");
    }

    @AfterEach
    void tearDown() {
        submissionRepository.deleteAllById(createdSubmissionIds);
        createdSubmissionIds.clear();
    }

    // --- Criterion 2 (POF mutation #2): race on the REAL partial unique index ---

    private static final int RACE_THREADS = 6;
    private static final int RACE_ROUNDS = 5;

    @Test
    void criterion2_parallelSubmitsSameKey_oneWinsOthers409_never500() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            String key = uniqueKey("race");
            List<MvcResult> results = runParallelSubmits(key, RACE_THREADS);

            assertThat(results).hasSize(RACE_THREADS);
            long ok = results.stream()
                .filter(r -> r.getResponse().getStatus() == 201).count();
            long conflict = results.stream()
                .filter(r -> r.getResponse().getStatus() == 409).count();
            long serverError = results.stream()
                .filter(r -> r.getResponse().getStatus() >= 500).count();

            assertThat(ok)
                .as("PG race round #%d: exactly one submit must win", round)
                .isEqualTo(1);
            assertThat(conflict)
                .as("PG race round #%d: all losers must get 409, not 500", round)
                .isEqualTo(RACE_THREADS - 1);
            assertThat(serverError)
                .as("PG race round #%d: never a 500", round)
                .isZero();

            // every loser gets the user-facing 409 (PENDING_SUBMISSION_EXISTS), not a 500
            for (MvcResult r : results) {
                if (r.getResponse().getStatus() == 409) {
                    JsonNode error = mapper.readTree(r.getResponse().getContentAsString());
                    assertThat(error.get("code").asText())
                        .as("PG race round #%d: loser must get the stable code, body: %s",
                            round, r.getResponse().getContentAsString())
                        .isEqualTo("PENDING_SUBMISSION_EXISTS");
                }
            }

            // exactly one row in the DB
            long rows = submissionRepository.findAll().stream()
                .filter(s -> s.getProcessKey().equals(key)).count();
            assertEquals(1, rows,
                "PG race round #%d: exactly one submission row must survive".formatted(round));

            // hygiene: register the winner for tearDown so repeated local runs do not leak
            results.stream()
                .filter(r -> r.getResponse().getStatus() == 201)
                .map(r -> {
                    try {
                        return mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(UUID::fromString)
                .forEach(createdSubmissionIds::add);
        }
    }

    // --- Criterion 2, part b (POF mutation #2, DETERMINISTIC): the DB itself must refuse ---
    // --- a second PENDING row for the same key (partial unique index).               ---
    // The application-level check (criterion 1) does not participate here: two direct
    // inserts through the real production repository. Without the index the second insert
    // succeeds and the test goes RED; with the index it throws — regardless of thread
    // scheduling (unlike the probabilistic race above).

    @Test
    void criterion2b_databaseGuarantee_secondPendingInsertOfSameKeyRejected() {
        String key = uniqueKey("dbguarantee");

        ProcessSubmissionEntity first = new ProcessSubmissionEntity();
        first.setId(UUID.randomUUID());
        first.setBpmn(bpmnFor(key));
        first.setProcessKey(key);
        first.setName("db-guarantee-1");
        first.setSubmittedBy(userId);
        first.setSubmittedAt(Instant.now());
        first.setStatus("PENDING");
        submissionRepository.save(first);
        createdSubmissionIds.add(first.getId());

        ProcessSubmissionEntity second = new ProcessSubmissionEntity();
        second.setId(UUID.randomUUID());
        second.setBpmn(bpmnFor(key));
        second.setProcessKey(key);
        second.setName("db-guarantee-2");
        second.setSubmittedBy(userId);
        second.setSubmittedAt(Instant.now());
        second.setStatus("PENDING");

        assertThatThrownBy(() -> submissionRepository.save(second))
            .as("partial unique index uk_process_submission__pending_key must reject "
                + "a second PENDING row for the same key")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Criterion 3, PG half: the partial predicate must NOT block non-PENDING rows ---

    @Test
    void criterion3_approvedKeyDoesNotBlockNewPendingRow() throws Exception {
        String key = uniqueKey("app");
        ProcessSubmissionDTO submitted = submit(userToken, bpmnFor(key));
        mockMvc.perform(post("/process-submissions/" + submitted.getId() + "/approve")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        // A PENDING row for the same key while an APPROVED row exists: the partial index
        // (WHERE status='PENDING') must ALLOW this — only a second PENDING is forbidden.
        ProcessSubmissionEntity direct = new ProcessSubmissionEntity();
        direct.setId(UUID.randomUUID());
        direct.setBpmn(bpmnFor(key));
        direct.setProcessKey(key);
        direct.setName("direct");
        direct.setSubmittedBy(userId);
        direct.setSubmittedAt(Instant.now());
        direct.setStatus("PENDING");
        submissionRepository.save(direct);
        createdSubmissionIds.add(direct.getId());

        assertEquals("PENDING",
            submissionRepository.findById(direct.getId()).orElseThrow().getStatus());
    }

    // === Helpers ===

    /** Fires N REAL parallel submit calls with the same process key. */
    private List<MvcResult> runParallelSubmits(String key, int threads) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<MvcResult> results = new CopyOnWriteArrayList<>();

        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await(5, TimeUnit.SECONDS);
                    results.add(submitRaw(userToken, bpmnFor(key)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }, "pg-submit-race-" + t);
            thread.start();
        }

        go.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS))
            .as("all racing threads must finish")
            .isTrue();
        return results;
    }

    private ProcessSubmissionDTO submit(String token, String bpmnXml) throws Exception {
        MvcResult result = submitRaw(token, bpmnXml);
        // WO-API-1: create → 201 + Location (было 200 до 15.09 — см. коммит 7a254a27).
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        ProcessSubmissionDTO dto = mapper.readValue(result.getResponse().getContentAsString(),
            ProcessSubmissionDTO.class);
        createdSubmissionIds.add(dto.getId());
        return dto;
    }

    private MvcResult submitRaw(String token, String bpmnXml) throws Exception {
        SubmitProcessSubmissionDTO dto = new SubmitProcessSubmissionDTO();
        dto.setBpmn(bpmnXml);
        return mockMvc.perform(post("/process-submissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
            .andReturn();
    }

    private UUID createOrUpdateUser(String username, String fullName, String email, String role) {
        UiUserEntity existing = userRepository.findByUsername(username).orElse(null);
        if (existing != null) return existing.getId();

        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(fullName);
        user.setEmail(email);
        user.setRole(role);
        user.setActive(true);
        user.setForcePasswordChange(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    private String login(String username) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword("pass");
        String result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(result).get("token").asText();
    }

    private String bpmnFor(String key) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\""
            + " xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\""
            + " xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\""
            + " id=\"Definitions_1\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
            + "  <bpmn:process id=\"" + key + "\" name=\"Test Process\" isExecutable=\"true\">"
            + "    <bpmn:startEvent id=\"startEvent\">"
            + "      <bpmn:outgoing>flow1</bpmn:outgoing>"
            + "    </bpmn:startEvent>"
            + "    <bpmn:endEvent id=\"endEvent\">"
            + "      <bpmn:incoming>flow1</bpmn:incoming>"
            + "    </bpmn:endEvent>"
            + "    <bpmn:sequenceFlow id=\"flow1\" sourceRef=\"startEvent\" targetRef=\"endEvent\" />"
            + "  </bpmn:process>"
            + "  <bpmndi:BPMNDiagram id=\"BPMNDiagram_1\">"
            + "    <bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"" + key + "\">"
            + "      <bpmndi:BPMNShape id=\"StartEvent_1_di\" bpmnElement=\"startEvent\">"
            + "        <dc:Bounds x=\"162\" y=\"82\" width=\"36\" height=\"36\" />"
            + "      </bpmndi:BPMNShape>"
            + "      <bpmndi:BPMNShape id=\"Event_1_di\" bpmnElement=\"endEvent\">"
            + "        <dc:Bounds x=\"432\" y=\"82\" width=\"36\" height=\"36\" />"
            + "      </bpmndi:BPMNShape>"
            + "      <bpmndi:BPMNEdge id=\"Flow_1_di\" bpmnElement=\"flow1\">"
            + "        <di:waypoint x=\"198\" y=\"100\" />"
            + "        <di:waypoint x=\"432\" y=\"100\" />"
            + "      </bpmndi:BPMNEdge>"
            + "    </bpmndi:BPMNPlane>"
            + "  </bpmndi:BPMNDiagram>"
            + "</bpmn:definitions>";
    }

    private String uniqueKey(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}