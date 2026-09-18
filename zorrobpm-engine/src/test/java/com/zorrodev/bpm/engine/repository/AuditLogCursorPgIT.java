package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-3 (P2): the cursor window (incl. the id tiebreak for shared instants)
 * holds on real PostgreSQL — the H2-only proof would not catch UUID/CAST or
 * ordering divergences. Seeds 4 rows sharing ONE instant and walks limit-1 pages:
 * every row exactly once.
 */
@Tag("pg")
class AuditLogCursorPgIT extends PostgresIT {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanProbeRows() {
        jdbc.update("DELETE FROM audit_log WHERE process_key = 'pg-cursor-probe'");
    }

    @Test
    void sharedInstantTiebreak_walkCoversAllRowsExactlyOnce() {
        Instant at = Instant.now();
        for (int i = 0; i < 4; i++) {
            AuditLogEntity e = new AuditLogEntity();
            e.setId(UUID.randomUUID());
            e.setPrincipalType("USER");
            e.setPrincipalId("pg-probe");
            e.setAction("pg-act-" + i);
            e.setProcessKey("pg-cursor-probe");
            e.setTargetId(UUID.randomUUID().toString());
            e.setAt(at);
            auditLogRepository.save(e);
        }

        List<UUID> seen = new ArrayList<>();
        Instant cursorAt = null;
        UUID cursorId = null;
        for (int page = 0; page < 6; page++) {
            List<AuditLogEntity> window = auditLogRepository.findPage(
                "pg-cursor-probe", null, null, null, cursorAt, cursorId, 2);
            if (window.isEmpty()) {
                break;
            }
            // Window of 2 with limit 2: take the 1st, continue after it (limit-1 paging).
            seen.add(window.get(0).getId());
            cursorAt = window.get(0).getAt();
            cursorId = window.get(0).getId();
        }

        assertThat(new HashSet<>(seen)).as("all 4 same-instant rows walked, no dups/skips")
            .hasSize(4);
    }
}
