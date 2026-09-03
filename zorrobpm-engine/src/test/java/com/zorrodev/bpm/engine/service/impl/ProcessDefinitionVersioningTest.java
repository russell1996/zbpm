package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-5b — unit tests for {@link ProcessDefinitionVersioning} (WO-A-03 cluster).
 * The fail-closed test moved here verbatim from {@code ProcessDefinitionServiceImplTest}
 * (it tests exactly this logic); happy paths added for both lock branches.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessDefinitionVersioningTest {

    @Mock
    private ProcessDefinitionRepository processDefinitionRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private DataSource dataSource;

    private ProcessDefinitionVersioning versioning;

    @BeforeEach
    void setUp() {
        // TransactionTemplate.execute runs the callback directly in unit tests
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        versioning = new ProcessDefinitionVersioning(
            processDefinitionRepository,
            jdbcTemplate,
            transactionTemplate,
            dataSource
        );
    }

    private static void pgProduct(DataSource dataSource) throws Exception {
        java.sql.Connection mockConn = org.mockito.Mockito.mock(java.sql.Connection.class);
        java.sql.DatabaseMetaData mockMeta = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        org.mockito.Mockito.when(mockMeta.getDatabaseProductName()).thenReturn("PostgreSQL");
        org.mockito.Mockito.when(mockConn.getMetaData()).thenReturn(mockMeta);
        when(dataSource.getConnection()).thenReturn(mockConn);
    }

    private static void h2Product(DataSource dataSource) throws Exception {
        java.sql.Connection mockConn = org.mockito.Mockito.mock(java.sql.Connection.class);
        java.sql.DatabaseMetaData mockMeta = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        org.mockito.Mockito.when(mockMeta.getDatabaseProductName()).thenReturn("H2");
        org.mockito.Mockito.when(mockConn.getMetaData()).thenReturn(mockMeta);
        when(dataSource.getConnection()).thenReturn(mockConn);
    }

    /**
     * POF (G-P/G-N): On PostgreSQL, if advisory lock fails (e.g. SQLException),
     * the transaction must ROLLBACK — version is NOT created.
     * RED (before fix): broad catch swallows exception, version created without lock.
     * GREEN (after fix): exception propagates, save() never called.
     * (Moved verbatim from ProcessDefinitionServiceImplTest — body unchanged.)
     */
    @Test
    void advisoryLockFailure_propagatesAndNoVersionCreated() throws Exception {
        // Simulate PostgreSQL: dataSource returns a connection whose meta says "PostgreSQL"
        pgProduct(dataSource);

        // Advisory lock will fail — simulate by making execute throw
        when(jdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class)))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("lock failed", new java.sql.SQLException("lock timeout")));

        String key = "test-key";
        UUID id = UUID.randomUUID();

        try {
            versioning.createNewVersionWithAdvisoryLock(key, "Test", "sha", id, null);
            org.assertj.core.api.Assertions.fail("Should have thrown due to lock failure");
        } catch (Exception e) {
            // Expected: exception propagates, version NOT created
            verify(processDefinitionRepository, never()).save(any(ProcessDefinitionEntity.class));
        }
    }

    @Test
    void pgLockSuccess_createsActiveVersionWithNextNumber() throws Exception {
        pgProduct(dataSource);
        UUID id = UUID.randomUUID();
        when(processDefinitionRepository.findMaxByKey("k")).thenReturn(Optional.of(2));
        when(processDefinitionRepository.save(any(ProcessDefinitionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        ProcessDefinitionEntity entity =
            versioning.createNewVersionWithAdvisoryLock("k", "n", "sha", id, "sfk");

        assertThat(entity.getVersion()).isEqualTo(3);
        assertThat(entity.getDeploymentState()).isEqualTo(ProcessDefinitionEntity.STATE_ACTIVE);
        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getStartFormKey()).isEqualTo("sfk");
        // Lock was actually attempted on PG
        verify(jdbcTemplate).execute(any(org.springframework.jdbc.core.ConnectionCallback.class));
        verify(processDefinitionRepository).save(any(ProcessDefinitionEntity.class));
    }

    @Test
    void h2Product_lockSkippedStillCreatesVersion() throws Exception {
        h2Product(dataSource);
        UUID id = UUID.randomUUID();
        when(processDefinitionRepository.findMaxByKey("k")).thenReturn(Optional.empty());
        when(processDefinitionRepository.save(any(ProcessDefinitionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        ProcessDefinitionEntity entity =
            versioning.createNewVersionWithAdvisoryLock("k", "n", "sha", id, null);

        assertThat(entity.getVersion()).isEqualTo(1);
        assertThat(entity.getDeploymentState()).isEqualTo(ProcessDefinitionEntity.STATE_ACTIVE);
        // H2: lock function not supported — skipped, never attempted
        verify(jdbcTemplate, never()).execute(any(org.springframework.jdbc.core.ConnectionCallback.class));
    }

    @Test
    void createNewVersionEntity_returnsUnsavedEntityWithNextNumber() throws Exception {
        h2Product(dataSource);
        UUID id = UUID.randomUUID();
        when(processDefinitionRepository.findMaxByKey("k")).thenReturn(Optional.of(4));

        ProcessDefinitionEntity entity =
            versioning.createNewVersionEntity("k", "n", "sha", id, "sfk");

        // Built but NOT saved — the caller persists it inside the deployment transaction
        verify(processDefinitionRepository, never()).save(any(ProcessDefinitionEntity.class));
        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getKey()).isEqualTo("k");
        assertThat(entity.getName()).isEqualTo("n");
        assertThat(entity.getVersion()).isEqualTo(5);
        assertThat(entity.getSha256()).isEqualTo("sha");
        assertThat(entity.getStartFormKey()).isEqualTo("sfk");
        assertThat(entity.getCreatedAt()).isNotNull();
    }
}
