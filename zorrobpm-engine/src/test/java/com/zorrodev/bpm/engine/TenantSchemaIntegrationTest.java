package com.zorrodev.bpm.engine;

import com.zorrodev.bpm.engine.entity.*;
import com.zorrodev.bpm.engine.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-MT-1 + WO-INT-4: Schema validation tests.
 *  #1: Migration ran — core tables exist (entities queryable)
 *  #1b: WO-INT-4 criterion 11 — service_account tables were dropped by migration 076
 *  #3: UNIQUE(process_id, user_id) enforced
 *  #4: api_key.key_hash unique enforced
 *  #5: ApiKeyRepository CRUD work
 *
 * NOTE: Backfill verification (process rows, admin SUPER_ADMIN, OWNER memberships)
 * requires production data. This is verified by the migration SQL output in mimo-to-cto.md.
 */
@ActiveProfiles("test")
@SpringBootTest
class TenantSchemaIntegrationTest {

    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private UiUserRepository uiUserRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // --- #1: Migration ran — core tables exist (entities queryable without error) ---

    @Test
    void migrationCreatedAllTables() {
        // WO-AUDIT-4: findAll() never returns null, so the old isNotNull() asserts
        // proved nothing — assert table existence directly via information_schema
        // (same idiom as #1b below).
        Integer present = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE UPPER(table_name) IN "
                + "('PROCESS', 'PROCESS_MEMBER', 'API_KEY', 'UI_USERS')",
            Integer.class);
        assertThat(present).isEqualTo(4);
    }

    // --- #1b (WO-INT-4 criterion 11): service_account tables are gone ---

    @Test
    void serviceAccountTablesDropped() {
        Integer serviceAccounts = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'service_account'",
            Integer.class);
        Integer serviceAccountPermissions = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'service_account_permission'",
            Integer.class);
        assertThat(serviceAccounts).isZero();
        assertThat(serviceAccountPermissions).isZero();
    }

    // --- #3: UNIQUE(process_id, user_id) enforced ---

    @Test
    void duplicateProcessMember_throwsConstraintViolation() {
        UUID processId = UUID.randomUUID();
        UUID userId = uiUserRepository.findByUsername("admin").orElseThrow().getId();

        ProcessEntity process = new ProcessEntity();
        process.setId(processId);
        process.setDefinitionKey("test-dup-member-key");
        process.setName("Test Dup Member");
        process.setCreatedAt(Instant.now());
        processRepository.save(process);

        // Insert via JDBC to bypass Hibernate merge
        jdbcTemplate.update(
            "INSERT INTO process_member (process_id, user_id, role, added_at) VALUES (?, ?, 'OWNER', CURRENT_TIMESTAMP)",
            processId, userId);

        // Duplicate via JDBC → must throw
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO process_member (process_id, user_id, role, added_at) VALUES (?, ?, 'DESIGNER', CURRENT_TIMESTAMP)",
            processId, userId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        // Cleanup
        jdbcTemplate.update("DELETE FROM process_member WHERE process_id = ?", processId);
        processRepository.delete(process);
    }

    // --- #4: api_key.key_hash unique enforced ---

    @Test
    void duplicateKeyHash_throwsConstraintViolation() {
        ApiKeyEntity key1 = new ApiKeyEntity();
        key1.setId(UUID.randomUUID());
        key1.setOwnerUserId(uiUserRepository.findByUsername("admin").orElseThrow().getId());
        key1.setKeyHash("unique-hash-value");
        key1.setPrefix("zbpm_sk_test1");
        key1.setCreatedAt(Instant.now());
        apiKeyRepository.save(key1);

        ApiKeyEntity key2 = new ApiKeyEntity();
        key2.setId(UUID.randomUUID());
        key2.setOwnerUserId(uiUserRepository.findByUsername("admin").orElseThrow().getId());
        key2.setKeyHash("unique-hash-value"); // duplicate
        key2.setPrefix("zbpm_sk_test2");
        key2.setCreatedAt(Instant.now());

        assertThatThrownBy(() -> apiKeyRepository.saveAndFlush(key2))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Cleanup
        apiKeyRepository.delete(key1);
    }

    // --- #5: ApiKeyRepository CRUD work ---

    @Test
    void repositoriesCrudWork() {
        ApiKeyEntity key = new ApiKeyEntity();
        key.setId(UUID.randomUUID());
        key.setOwnerUserId(uiUserRepository.findByUsername("admin").orElseThrow().getId());
        key.setKeyHash(UUID.randomUUID().toString());
        key.setPrefix("zbpm_sk_crud");
        key.setCreatedAt(Instant.now());
        apiKeyRepository.save(key);

        // Read
        assertThat(apiKeyRepository.findById(key.getId())).isPresent();

        // Delete
        apiKeyRepository.deleteById(key.getId());
        assertThat(apiKeyRepository.findById(key.getId())).isEmpty();
    }
}