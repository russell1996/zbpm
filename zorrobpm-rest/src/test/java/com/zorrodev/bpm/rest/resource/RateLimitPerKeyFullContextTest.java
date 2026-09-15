package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-INT-4 criterion 8: the data-endpoint rate-limit quota is counted PER API KEY,
 * not per client address. Two integration BFFs (two keys of one system account)
 * calling from the same address must not throttle each other — and must not exhaust
 * the shared per-IP quota of anonymous traffic.
 *
 * Full-context (V11): real filter chain, real HTTP, forward-headers strategy.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.capacity=5",
    "zorrobpm.security.rate-limit.data-capacity=5",
    "zorrobpm.security.rate-limit.window-seconds=3600",
    "zorrobpm.security.rate-limit.account-capacity=10",
    "server.forward-headers-strategy=framework"
})
class RateLimitPerKeyFullContextTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    private UiUserRepository uiUserRepository;

    @Autowired
    private ProcessRepository processRepository;

    @Autowired
    private ProcessMemberRepository processMemberRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private String adminToken;
    private String processKey;
    private UUID processId;

    @BeforeAll
    void setUp() throws Exception {
        adminToken = loginAdmin();

        String bpmn = Files.readString(
            Paths.get("src/test/files/assignee-task.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        HttpResponse<String> deployResp = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/process-definitions"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(addDto)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(deployResp.statusCode()).isEqualTo(201);
        processKey = mapper.readTree(deployResp.body()).get("key").asText();
        ProcessEntity process = processRepository.findByDefinitionKey(processKey).orElseThrow();
        processId = process.getId();
    }

    @BeforeEach
    void resetBuckets() {
        rateLimitFilter.reset();
    }

    @Test
    void criterion10_twoKeysFromOneAddress_haveSeparateQuotas() throws Exception {
        UUID systemId = createSystemUser("ratelimsys1");

        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(processId);
        pm.setUserId(systemId);
        pm.setRole("OWNER");
        pm.setAddedBy(systemId);
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);

        String keyA = issueKey(systemId);
        String keyB = issueAdditionalKey(systemId);
        setGrantsFull(systemId, processKey);

        // keyA fills ITS OWN quota: 5 requests from the same address all pass
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> r = httpClient.send(buildDataRequest(keyA),
                HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as("keyA request %d", i + 1).isEqualTo(200);
        }

        // keyB from the SAME address is unaffected: its own quota is intact
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> r = httpClient.send(buildDataRequest(keyB),
                HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as("keyB request %d", i + 1).isEqualTo(200);
        }

        // keyA has exhausted its own quota → 429 (per-key, not per-IP)
        HttpResponse<String> r = httpClient.send(buildDataRequest(keyA),
            HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(429);

        // Anonymous traffic from the same address keeps its own IP quota untouched
        // (401 from auth, NOT 429 — the keys never consumed the IP bucket)
        HttpResponse<String> anon = httpClient.send(buildDataRequest(null),
            HttpResponse.BodyHandlers.ofString());
        assertThat(anon.statusCode()).isNotEqualTo(429);
    }

    private String loginAdmin() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/auth/login"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(dto)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return mapper.readValue(resp.body(), AuthResponse.class).getToken();
    }

    private UUID createSystemUser(String username) throws Exception {
        String body = "{\"username\":\"" + username
            + "\",\"fullName\":\"" + username
            + "\",\"email\":\"" + username + "@zorrodev.test"
            + "\",\"role\":\"SUPER_ADMIN\""
            + ",\"active\":true"
            + ",\"password\":\"MyStr0ng!P@ssw0rd\""
            + ",\"userType\":\"SYSTEM\"}";
        HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/users"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 409) {
            return uiUserRepository.findByUsername(username).orElseThrow().getId();
        }
        assertThat(resp.statusCode()).isEqualTo(201);
        return UUID.fromString(mapper.readTree(resp.body()).get("id").asText());
    }

    private String issueKey(UUID userId) throws Exception {
        HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/admin/users/" + userId + "/api-key"))
                .header("Authorization", "Bearer " + adminToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);
        return mapper.readTree(resp.body()).get("key").asText();
    }

    private String issueAdditionalKey(UUID userId) throws Exception {
        HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/admin/users/" + userId + "/api-keys"))
                .header("Authorization", "Bearer " + adminToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);
        return mapper.readTree(resp.body()).get("key").asText();
    }

    private void setGrantsFull(UUID userId, String key) throws Exception {
        HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/admin/users/" + userId + "/api-key/grants"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"grants\":[{\"processKey\":\"" + key + "\",\"full\":true}]}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
    }

    private HttpRequest buildDataRequest(String bearerToken) {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/user-tasks"))
            .header("X-Forwarded-For", "203.0.113.7")
            .GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return builder.build();
    }
}