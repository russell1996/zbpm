package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-OPS-18 criterion 2 (readiness/liveness split) + criterion 3 (health-gate intact).
 *
 * Full Spring context + real filter chain (MockMvc, {@code @AutoConfigureMockMvc}) —
 * no hand-built filter stand-ins (V11). The probes properties below mirror
 * {@code application-prod.yml} key-for-key (same Boot 4.0 keys); the prod file itself
 * is pinned by {@link #prodYaml_carriesSameKeys} so the two cannot drift apart.
 *
 * Test-env fact this relies on (same as every other rest IT): no RabbitMQ broker is
 * listening on {@code localhost:11002} during {@code mvn verify}, so the real
 * {@code RabbitHealthIndicator} reports DOWN through the real auto-configuration.
 *
 * Boot 4 semantic, proven by live probes (diagnostic runs 2026-09-24, not assumed):
 * with the prod group include the readiness path answers 503 DOWN naming the dead
 * broker, while liveness answers 200 UP — the split. The probes-only default (no
 * group.* at all) answered `{"status":"UP"}` 200 on the same path despite the same
 * dead broker, so the include is the behavioural delta criterion 2 needs.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.endpoint.health.probes.enabled=true",
        // Same include as application-prod.yml (pinned by prodYaml_carriesSameKeys below).
        "management.endpoint.health.group.readiness.include=readinessState,db,rabbit,diskSpace",
        // show-details is presentation-only (does not change group MEMBERSHIP):
        // let the test name the failing component (P-67). Prod keeps the default
        // (hidden) — same posture as the aggregate endpoint (CTO decision).
        "management.endpoint.health.group.readiness.show-details=always",
        "management.health.livenessstate.enabled=true",
        "management.health.readinessstate.enabled=true"
})
@AutoConfigureMockMvc
class HealthProbesGroupsIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void liveness_withoutToken_isUpDespiteDeadRabbit() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        // Liveness = "process alive", must NOT include the dead Rabbit indicator.
        assertThat(body).contains("\"status\":\"UP\"");
        assertThat(body).doesNotContain("rabbit");
    }

    @Test
    void readiness_withoutToken_usesCustomGroupDefinition() throws Exception {
        // pins the Boot 4 semantic: the user-defined
        // `management.endpoint.health.group.readiness.include=readinessState,db,rabbit,
        // diskSpace` (same as application-prod.yml) pulls the dead broker INTO the
        // readiness path — 503 DOWN naming rabbit. POF on THIS behaviour (not just the
        // file shape): temporarily narrowing the annotation include to
        // `readinessState` alone flips this path to `{"status":"UP"}` 200 despite the
        // same dead broker (verified 2026-09-24 — the include is the behavioural
        // delta). Delete the include from the prod yml and THIS test goes RED
        // (prodYaml_carriesSameKeys pins the file side of the same invariant).
        // (CLI `-D...include=readinessState` does NOT reproduce this: failsafe forks a
        // JVM without that system property — tried live, the fork ignores it.)
        MvcResult result = mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"status\":\"DOWN\"");
        assertThat(body).contains("rabbit");
    }

    @Test
    void aggregateHealth_shapePreservedForDeployGate() throws Exception {
        // Criterion 3: the deploy health-gate (.gitlab-ci.yml greps '"status":"UP"' on
        // the AGGREGATE path) keeps working — probes only ADD sub-paths and make the
        // aggregate stricter, they do not remove or reshape it. In this broker-less
        // test env the honest aggregate answer is DOWN/503 (stricter = working):
        // the dead broker is really visible HERE (concrete component, P-67).
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"status\":\"DOWN\"");
        assertThat(body).contains("rabbit");
        assertThat(body).contains("liveness");
        assertThat(body).contains("readiness");
    }

    @Test
    @SuppressWarnings("unchecked")
    void prodYaml_carriesSameKeys() throws Exception {
        // The behavioural tests above prove what these exact keys DO on this Boot
        // version; this test proves application-prod.yml (the real prod artifact,
        // never loaded by the test profile) actually CARRIES them — no drift.
        // Read from the source tree directly: resolving it via the classpath would
        // pick up the stale test-classes shadow copy first (P-59 neighbour).
        java.nio.file.Path prodYaml =
                java.nio.file.Paths.get("src/main/resources/application-prod.yml");
        assertThat(java.nio.file.Files.exists(prodYaml))
                .as("prod yml must exist at " + prodYaml.toAbsolutePath()).isTrue();
        Map<String, Object> yaml;
        try (InputStream in = java.nio.file.Files.newInputStream(prodYaml)) {
            yaml = new Yaml().load(in);
        }
        Map<String, Object> management = (Map<String, Object>) yaml.get("management");
        assertThat(management).as("prod yml must have a management section").isNotNull();

        Map<String, Object> endpoints = (Map<String, Object>) management.get("endpoints");
        Map<String, Object> web = (Map<String, Object>) endpoints.get("web");
        Map<String, Object> exposure = (Map<String, Object>) web.get("exposure");
        assertThat(String.valueOf(exposure.get("include"))).contains("health");

        Map<String, Object> endpoint = (Map<String, Object>) management.get("endpoint");
        Map<String, Object> healthEndpoint = (Map<String, Object>) endpoint.get("health");
        Map<String, Object> probes = (Map<String, Object>) healthEndpoint.get("probes");
        assertThat(probes.get("enabled")).isEqualTo(true);
        // Boot 4 readiness group is readinessState-only by default — the explicit
        // include is what makes readiness depend on db/rabbit (criterion 2).
        Map<String, Object> group = (Map<String, Object>) healthEndpoint.get("group");
        assertThat(group).as("prod yml must define the readiness group").isNotNull();
        Map<String, Object> readinessGroup = (Map<String, Object>) group.get("readiness");
        String include = String.valueOf(readinessGroup.get("include"));
        assertThat(include).contains("readinessState");
        assertThat(include).contains("rabbit");
        assertThat(include).contains("db");

        Map<String, Object> health = (Map<String, Object>) management.get("health");
        Map<String, Object> livenessState = (Map<String, Object>) health.get("livenessstate");
        Map<String, Object> readinessState = (Map<String, Object>) health.get("readinessstate");
        assertThat(livenessState.get("enabled")).isEqualTo(true);
        assertThat(readinessState.get("enabled")).isEqualTo(true);
    }
}
