package com.zorrodev.bpm.engine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * WO-SCALE-1: single place for DB-dialect-aware advisory-lock acquisition.
 * Extracted from {@code ProcessDefinitionVersioning} (WO-A-03) — the only
 * prod-code location that may contain the advisory-lock SQL literal.
 * Callers pass a namespaced key ({@code "dmn:"}, {@code "form:"}, bare BPMN key)
 * so different domains never collide even if the raw key string does.
 *
 * <p>PG: advisory lock — any exception propagates
 * (fail-closed, caller's transaction rolls back). H2: skipped (function not
 * supported), logged once at debug. Lock is xact-scoped — auto-released on
 * commit/rollback of the caller's transaction, no manual release.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisoryDeployLock {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    private volatile String databaseProduct;

    /**
     * WO-A-03 / WO-SCALE-1: acquire a transaction-scoped advisory lock for the
     * given key. Must be called INSIDE the caller's deploy transaction — the lock
     * lives and dies with that transaction, like the original BPMN path.
     *
     * @param key namespaced deploy key (e.g. {@code "dmn:myDecision"} or
     *            {@code "form:myForm"} or bare BPMN key)
     */
    public void acquireForKey(String key) {
        String product = getDatabaseProduct();
        if ("PostgreSQL".equals(product)) {
            long lockKey = key.hashCode();
            jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) conn -> {
                try (var ps = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    ps.setLong(1, lockKey);
                    ps.execute();
                }
                return null;
            });
            // WO-A-03: no catch — any exception propagates and rolls back the transaction
        } else {
            log.debug("Advisory lock skipped for database product: {}", product);
        }
    }

    private String getDatabaseProduct() {
        String cached = databaseProduct;
        if (cached != null) {
            return cached;
        }
        try (var conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            databaseProduct = product;
            return product;
        } catch (Exception e) {
            log.warn("Failed to detect database product, assuming PostgreSQL", e);
            return "PostgreSQL";
        }
    }
}
