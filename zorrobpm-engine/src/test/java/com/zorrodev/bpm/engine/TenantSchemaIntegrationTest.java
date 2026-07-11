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
 * WO-MT-1: Schema validation tests.
 *  #1: Migration ran — all 3 tables exist (entities queryable)
 *  #3: UNIQUE(process_id, user_id) enforced
 *  #4: key_hash unique enforced
 *  #5: Repositories CRUD work
 *
 * NOTE: Backfill verification (process rows, admin SUPER_ADMIN, OWNER memberships)
 * requires production data. This is verified by the migration SQL output in mimo-to-cto.md.
 */
@ActiveProfiles("test")
@SpringBootTest
class TenantSchemaIntegrationTest {

    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ServiceAccountRepository serviceAccountRepository;
    @Autowired private ServiceAccountPermissionRepository saPermissionRepository;
    @Autowired private UiUserRepository uiUserRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // --- #1: Migration ran — tables exist (entities queryable without error) ---

    @Test
    void migrationCreatedAllTables() {
        // If the tables didn't exist, these would throw ExceptionInInitializerError or similar
        assertThat(processRepository.findAll()).isNotNull();
        assertThat(processMemberRepository.findAll()).isNotNull();
        assertThat(serviceAccountRepository.findAll()).isNotNull();
        assertThat(saPermissionRepository.findAll()).isNotNull();
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

    // --- #4: key_hash unique enforced ---

    @Test
    void duplicateKeyHash_throwsConstraintViolation() {
        UUID processId = UUID.randomUUID();

        ProcessEntity process = new ProcessEntity();
        process.setId(processId);
        process.setDefinitionKey("test-dup-hash-key");
        process.setName("Test Dup Hash");
        process.setCreatedAt(Instant.now());
        processRepository.save(process);

        ServiceAccountEntity sa1 = new ServiceAccountEntity();
        sa1.setId(UUID.randomUUID());
        sa1.setProcessId(processId);
        sa1.setName("sa-one");
        sa1.setKeyHash("unique-hash-value");
        sa1.setPrefix("zbpm_sk_test1");
        sa1.setCreatedAt(Instant.now());
        serviceAccountRepository.save(sa1);

        ServiceAccountEntity sa2 = new ServiceAccountEntity();
        sa2.setId(UUID.randomUUID());
        sa2.setProcessId(processId);
        sa2.setName("sa-two");
        sa2.setKeyHash("unique-hash-value"); // duplicate
        sa2.setPrefix("zbpm_sk_test2");
        sa2.setCreatedAt(Instant.now());

        assertThatThrownBy(() -> serviceAccountRepository.saveAndFlush(sa2))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Cleanup
        serviceAccountRepository.delete(sa1);
        processRepository.delete(process);
    }

    // --- #5: Repositories CRUD work ---

    @Test
    void repositoriesCrudWork() {
        UUID processId = UUID.randomUUID();

        ProcessEntity process = new ProcessEntity();
        process.setId(processId);
        process.setDefinitionKey("test-crud-key");
        process.setName("Test CRUD");
        process.setCreatedAt(Instant.now());
        processRepository.save(process);

        ServiceAccountEntity sa = new ServiceAccountEntity();
        sa.setId(UUID.randomUUID());
        sa.setProcessId(processId);
        sa.setName("crud-test-sa");
        sa.setKeyHash(UUID.randomUUID().toString());
        sa.setPrefix("zbpm_sk_crud");
        sa.setCreatedAt(Instant.now());
        serviceAccountRepository.save(sa);

        ServiceAccountPermissionEntity perm = new ServiceAccountPermissionEntity();
        perm.setServiceAccountId(sa.getId());
        perm.setPermission("START");
        saPermissionRepository.save(perm);

        // Read
        assertThat(serviceAccountRepository.findById(sa.getId())).isPresent();
        assertThat(saPermissionRepository.findByServiceAccountId(sa.getId())).hasSize(1);

        // Delete
        saPermissionRepository.deleteById(new ServiceAccountPermissionId(sa.getId(), "START"));
        serviceAccountRepository.deleteById(sa.getId());
        assertThat(serviceAccountRepository.findById(sa.getId())).isEmpty();
        processRepository.delete(process);
    }
}
