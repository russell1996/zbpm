package com.zorrodev.bpm.engine.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-SCALE-1 — unit tests for {@link AdvisoryDeployLock}, the single home of the
 * PG/H2 advisory-lock branch extracted from {@code ProcessDefinitionVersioning}
 * (WO-A-03). Contract: PG executes the lock and any failure propagates
 * fail-closed (no catch); H2 skips without touching JDBC.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdvisoryDeployLockTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DataSource dataSource;

    private static void product(DataSource dataSource, String name) throws Exception {
        java.sql.Connection mockConn = org.mockito.Mockito.mock(java.sql.Connection.class);
        java.sql.DatabaseMetaData mockMeta = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        org.mockito.Mockito.when(mockMeta.getDatabaseProductName()).thenReturn(name);
        org.mockito.Mockito.when(mockConn.getMetaData()).thenReturn(mockMeta);
        when(dataSource.getConnection()).thenReturn(mockConn);
    }

    @Test
    void pgProduct_executesLock() throws Exception {
        product(dataSource, "PostgreSQL");
        AdvisoryDeployLock lock = new AdvisoryDeployLock(jdbcTemplate, dataSource);

        lock.acquireForKey("dmn:discount");

        verify(jdbcTemplate).execute(any(ConnectionCallback.class));
    }

    @Test
    void pgLockFailure_propagatesFailClosed() throws Exception {
        product(dataSource, "PostgreSQL");
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("lock failed",
                new java.sql.SQLException("lock timeout")));
        AdvisoryDeployLock lock = new AdvisoryDeployLock(jdbcTemplate, dataSource);

        // WO-A-03: no catch — any exception propagates and rolls back the transaction
        assertThatThrownBy(() -> lock.acquireForKey("form:myForm"))
            .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }

    @Test
    void h2Product_skipsLock() throws Exception {
        product(dataSource, "H2");
        AdvisoryDeployLock lock = new AdvisoryDeployLock(jdbcTemplate, dataSource);

        lock.acquireForKey("dmn:discount");

        verify(jdbcTemplate, never()).execute(any(ConnectionCallback.class));
    }
}
