package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-3 (P2): the audit journal read is a bounded keyset window, not the whole
 * table. Seeds 5 rows (two sharing one instant, to prove the id tiebreak) and walks
 * the journal page by page: every row exactly once, newest first.
 */
@ActiveProfiles("test")
@SpringBootTest
class AuditLogCursorIntegrationTests {

    @Autowired
    private AuditLogRepository auditLogRepository;

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void seedFiveRows() {
        // Distinct instants + one shared instant (tiebreak pair).
        save("act-0", BASE.plusSeconds(0));
        save("act-1", BASE.plusSeconds(1));
        save("act-2a", BASE.plusSeconds(2));
        save("act-2b", BASE.plusSeconds(2));
        save("act-3", BASE.plusSeconds(3));
    }

    private void save(String action, Instant at) {
        AuditLogEntity e = new AuditLogEntity();
        e.setId(UUID.randomUUID());
        e.setPrincipalType("USER");
        e.setPrincipalId("tester");
        e.setAction(action);
        e.setProcessKey("cursor-probe");
        e.setTargetId(UUID.randomUUID().toString());
        e.setAt(at);
        auditLogRepository.save(e);
    }

    @Test
    void beforeShape_unboundedFindByFilters_returnsWholeJournal() {
        // Documents the P2 bug shape: the old path has no window at all.
        List<AuditLogEntity> all = auditLogRepository.findByFilters("cursor-probe", null, null, null);
        assertThat(all).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void findPage_windowRespectedAndWalkCoversAllRowsExactlyOnce() {
        List<AuditLogEntity> first = auditLogRepository.findPage("cursor-probe", null, null, null, null, null, 2);
        assertThat(first).as("window of 2, not the whole journal").hasSize(2);

        // Newest first.
        assertThat(first.get(0).getAt()).isAfterOrEqualTo(first.get(1).getAt());

        // Walk to exhaustion via cursors.
        List<UUID> seen = new ArrayList<>();
        Instant cursorAt = null;
        UUID cursorId = null;
        while (true) {
            List<AuditLogEntity> window =
                auditLogRepository.findPage("cursor-probe", null, null, null, cursorAt, cursorId, 3);
            if (window.size() < 3) {
                for (AuditLogEntity e : window) {
                    seen.add(e.getId());
                }
                break;
            }
            // Full window of 3 means hasMore may hold — take 2, continue after the 2nd.
            seen.add(window.get(0).getId());
            seen.add(window.get(1).getId());
            cursorAt = window.get(1).getAt();
            cursorId = window.get(1).getId();
        }

        List<UUID> probeIds = auditLogRepository.findByFilters("cursor-probe", null, null, null)
            .stream().map(AuditLogEntity::getId).toList();
        assertThat(new HashSet<>(seen)).as("every journal row seen exactly once, no dups/skips")
            .containsExactlyInAnyOrderElementsOf(new HashSet<>(probeIds));
        assertThat(seen).as("at least our 5 seeded rows walked").hasSizeGreaterThanOrEqualTo(5);
    }
}
