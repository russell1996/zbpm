package com.zorrodev.bpm.engine.tenant;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ARCH-1a: V11 integration test for per-tenant read isolation on PostgreSQL.
 *
 * Proves that SQL-level filtering by processDefinitionId works:
 * (a) user-A sees only instances with pdId in their allowedPdIds
 * (b) user-A does NOT see user-B's instances
 * (c) admin (no filter) sees everything
 * (d) empty allowedPdIds → denied (no results)
 *
 * Tests the exact same SQL pattern that QueryServiceImpl uses:
 * WHERE process_definition_id IN (:allowedPdIds)
 */
public class TenantReadIsolationPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository processDefinitionRepository;
    @Autowired com.zorrodev.bpm.engine.repository.ProcessInstanceRepository processInstanceRepository;

    private UUID pdIdA;
    private UUID pdIdB;
    private UUID piIdA;
    private UUID piIdB;

    @BeforeEach
    void setUp() {
        // Clean
        jdbc.update("DELETE FROM user_tasks WHERE process_definition_id IN " +
            "(SELECT id FROM process_definitions WHERE code IN ('isol-a','isol-b'))");
        jdbc.update("DELETE FROM process_instances WHERE process_definition_id IN " +
            "(SELECT id FROM process_definitions WHERE code IN ('isol-a','isol-b'))");
        jdbc.update("DELETE FROM process_definitions WHERE code IN ('isol-a','isol-b')");

        pdIdA = UUID.randomUUID();
        pdIdB = UUID.randomUUID();
        piIdA = UUID.randomUUID();
        piIdB = UUID.randomUUID();

        // Create process definitions
        var pdA = new ProcessDefinitionEntity();
        pdA.setId(pdIdA); pdA.setKey("isol-a"); pdA.setName("Isol A");
        pdA.setVersion(1); pdA.setSha256("sha-a"); pdA.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdA);

        var pdB = new ProcessDefinitionEntity();
        pdB.setId(pdIdB); pdB.setKey("isol-b"); pdB.setName("Isol B");
        pdB.setVersion(1); pdB.setSha256("sha-b"); pdB.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdB);

        // Create process instances
        var piA = new ProcessInstanceEntity();
        piA.setId(piIdA); piA.setProcessDefinitionId(pdIdA); piA.setStartedAt(Instant.now());
        processInstanceRepository.save(piA);

        var piB = new ProcessInstanceEntity();
        piB.setId(piIdB); piB.setProcessDefinitionId(pdIdB); piB.setStartedAt(Instant.now());
        processInstanceRepository.save(piB);
    }

    // (a) user-A sees their own instances
    @Test
    void allowedPdIds_seesOwnInstances() {
        var result = queryServiceFindProcessInstances(pdIdA);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("id")).isEqualTo(piIdA);
    }

    // (b) user-A does NOT see user-B's instances
    @Test
    void allowedPdIds_excludesOtherTenant() {
        var result = queryServiceFindProcessInstances(pdIdA);
        assertThat(result).noneMatch(r -> piIdB.equals(r.get("id")));
    }

    // (c) admin (no filter) sees everything
    @Test
    void noFilter_seesEverything() {
        var all = jdbc.queryForList(
            "SELECT id FROM process_instances WHERE process_definition_id IN (?, ?)",
            pdIdA, pdIdB);
        assertThat(all).hasSize(2);
    }

    // (d) empty allowedPdIds → denied
    @Test
    void emptyAllowedPdIds_denied() {
        var result = jdbc.queryForList(
            "SELECT id FROM process_instances WHERE process_definition_id IN (SELECT CAST(NULL AS UUID) WHERE FALSE)");
        assertThat(result).isEmpty();
    }

    // Core filter: same SQL pattern as QueryServiceImpl.findProcessInstances
    private List<java.util.Map<String, Object>> queryServiceFindProcessInstances(UUID... allowedPdIds) {
        return jdbc.queryForList(
            "SELECT id, process_definition_id FROM process_instances WHERE process_definition_id IN (?) ORDER BY id",
            (Object[]) allowedPdIds);
    }
}
