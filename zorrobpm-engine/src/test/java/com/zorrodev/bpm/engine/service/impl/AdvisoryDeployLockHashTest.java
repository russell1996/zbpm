package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.service.AdvisoryDeployLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-AUDIT-5 item 5: the advisory-lock key must use the full 64 bits.
 * {@code key.hashCode()} collapses to 32 bits — different keys collide and get
 * falsely serialized. All assertions go through the public
 * {@link AdvisoryDeployLock#acquireForKey} (captured {@code setLong} value) —
 * no API surface widened for tests.
 */
class AdvisoryDeployLockHashTest {

    /** Runs acquireForKey on PG mocks and returns the bound lock value. */
    private static long lockValue(String key) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        java.sql.Connection metaConn = mock(java.sql.Connection.class);
        java.sql.DatabaseMetaData mockMeta = mock(java.sql.DatabaseMetaData.class);
        when(mockMeta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metaConn.getMetaData()).thenReturn(mockMeta);
        when(dataSource.getConnection()).thenReturn(metaConn);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        java.sql.Connection conn = mock(java.sql.Connection.class);
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        when(conn.prepareStatement(any(String.class))).thenReturn(ps);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
            ConnectionCallback<Void> cb = inv.getArgument(0);
            cb.doInConnection(conn);
            return null;
        });

        new AdvisoryDeployLock(jdbcTemplate, dataSource).acquireForKey(key);

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(ps).setLong(eq(1), captor.capture());
        return captor.getValue();
    }

    @Test
    void lockKey_deterministicPerKey() throws Exception {
        assertThat(lockValue("dmn:discount")).isEqualTo(lockValue("dmn:discount"));
    }

    @Test
    void lockKey_distinctKeysDiffer() throws Exception {
        assertThat(lockValue("dmn:a")).isNotEqualTo(lockValue("dmn:b"));
    }

    @Test
    void lockKey_usesBitsAbove32_notHashCode() throws Exception {
        // Old scheme bound (long) key.hashCode() — upper 32 bits always zero
        // (sign extension aside); the 64-bit scheme must spread beyond that.
        // Looped over several keys: P(all-zero-upper) is negligible either way,
        // and no single hard-coded key can be an unlucky pick.
        boolean spread = false;
        for (String key : new String[]{"dmn:discount", "form:order", "proc-1", "x", "another-key-here"}) {
            long bound = lockValue(key);
            assertThat(bound).isNotEqualTo((long) key.hashCode());
            if ((bound >>> 32) != 0) {
                spread = true;
            }
        }
        assertThat(spread).as("lock key must use bits above 32 (old 32-bit scheme cannot)").isTrue();
    }
}
