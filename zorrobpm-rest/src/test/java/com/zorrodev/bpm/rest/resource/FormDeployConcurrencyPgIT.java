package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * WO-SCALE-1: concurrent form deployment test on real PostgreSQL.
 * Calls the REAL FormOperations.deployForm() from two parallel threads.
 * Both DTOs share the same form key but carry different schemas — both enter
 * version creation, and the advisory lock
 * ({@code AdvisoryDeployLock.acquireForKey("form:" + key)}) must serialize them
 * to produce sequential versions (1 and 2), not duplicate version 1.
 *
 * Mirror of {@code ProcessDefinitionPgIT.concurrentDeploy_sameKey_differentVersions_noViolation},
 * one-to-one in structure. Only the authz boundary ({@link FormAccessSupport})
 * is mocked (SUPER_ADMIN) — the versioning path under test is fully real,
 * same pattern as {@code DeployFormTransactionIT}.
 *
 * POF (G-N): commenting out acquireForKey in FormOperationsImpl.deployForm → RED
 * (unique violation on form_key+version); restoring → GREEN. H2 proves nothing
 * here: the H2 branch of the lock is a silent no-op.
 */
@Tag("pg")
@ActiveProfiles("test")
@SpringBootTest
public class FormDeployConcurrencyPgIT {

    @Autowired private FormOperations formOperations;
    @Autowired private FormRepository formRepository;
    @MockitoBean private FormAccessSupport formAccessSupport;

    private static final String FORM_KEY = "scale1RaceForm";

    // Same env-first resolution as Acl12SubmissionRacePgIT (PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD).
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

    @BeforeEach
    void setUp() {
        Principal admin = new Principal.UserPrincipal(UUID.randomUUID(), "pg-admin", "SUPER_ADMIN");
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        // Clean up existing versions
        deleteAllVersions();
    }

    @Test
    void concurrentDeploy_sameFormKey_differentVersions_noViolation() throws Exception {
        CountDownLatch readyGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Thread 1: deploy schema V1 via REAL operations
        Future<?> f1 = pool.submit(() -> {
            try {
                readyGate.await();
                formOperations.deployForm(dto("{\"components\":[]}"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Thread 2: deploy schema V2 (same form key, different content) via REAL operations
        Future<?> f2 = pool.submit(() -> {
            try {
                readyGate.await();
                formOperations.deployForm(dto("{\"components\":[{\"type\":\"textfield\",\"key\":\"a\"}]}"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        readyGate.countDown();

        f1.get();
        f2.get();
        pool.shutdown();

        // Verify: two versions exist, sequential (1 and 2)
        List<FormEntity> rows = formRepository.findAll().stream()
            .filter(f -> FORM_KEY.equals(f.getFormKey()))
            .sorted(Comparator.comparingInt(FormEntity::getVersion))
            .toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getVersion()).isEqualTo(1);
        assertThat(rows.get(1).getVersion()).isEqualTo(2);

        // Cleanup
        deleteAllVersions();
    }

    private static DeployFormDTO dto(String schema) {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(FORM_KEY);
        dto.setKind("FORM_JS");
        dto.setSchema(schema);
        return dto;
    }

    private void deleteAllVersions() {
        List<UUID> ids = formRepository.findAll().stream()
            .filter(f -> FORM_KEY.equals(f.getFormKey()))
            .map(FormEntity::getId)
            .toList();
        formRepository.deleteAllById(ids);
    }
}
