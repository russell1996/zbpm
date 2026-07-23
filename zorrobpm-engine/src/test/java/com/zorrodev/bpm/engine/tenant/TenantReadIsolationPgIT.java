package com.zorrodev.bpm.engine.tenant;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ARCH-1a (G-N): V11 integration test for per-tenant read isolation.
 *
 * Uses REAL QueryService.findProcessInstances(query, allowedPdIds).
 * Tests the actual JPA Specification filter in QueryServiceImpl.
 *
 * (a) user sees only their own instances
 * (b) user does NOT see other tenant's instances
 * (c) admin (null allowedPdIds) sees everything
 * (d) empty allowedPdIds → denied (empty results)
 *
 * POF (G-N): remove .in(allowedPdIds) from QueryServiceImpl → test (b) FAILS (RED).
 * Restore → GREEN.
 */
public class TenantReadIsolationPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired QueryService queryService;
    @Autowired ProcessDefinitionRepository processDefinitionRepository;
    @Autowired ProcessInstanceRepository processInstanceRepository;

    private UUID pdIdA;
    private UUID pdIdB;
    private UUID piIdA;
    private UUID piIdB;

    @BeforeEach
    void setUp() {
        // FK order: user_tasks → process_instances → process_definitions
        jdbc.update("DELETE FROM user_tasks WHERE process_definition_id IN " +
            "(SELECT id FROM process_definitions WHERE code IN ('isol-a','isol-b'))");
        jdbc.update("DELETE FROM process_instances WHERE process_definition_id IN " +
            "(SELECT id FROM process_definitions WHERE code IN ('isol-a','isol-b'))");
        jdbc.update("DELETE FROM process_definitions WHERE code IN ('isol-a','isol-b')");

        pdIdA = UUID.randomUUID();
        pdIdB = UUID.randomUUID();
        piIdA = UUID.randomUUID();
        piIdB = UUID.randomUUID();

        var pdA = new ProcessDefinitionEntity();
        pdA.setId(pdIdA); pdA.setKey("isol-a"); pdA.setName("Isol A");
        pdA.setVersion(1); pdA.setSha256("sha-a"); pdA.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdA);

        var pdB = new ProcessDefinitionEntity();
        pdB.setId(pdIdB); pdB.setKey("isol-b"); pdB.setName("Isol B");
        pdB.setVersion(1); pdB.setSha256("sha-b"); pdB.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdB);

        var piA = new ProcessInstanceEntity();
        piA.setId(piIdA); piA.setProcessDefinitionId(pdIdA); piA.setStartedAt(Instant.now());
        processInstanceRepository.save(piA);

        var piB = new ProcessInstanceEntity();
        piB.setId(piIdB); piB.setProcessDefinitionId(pdIdB); piB.setStartedAt(Instant.now());
        processInstanceRepository.save(piB);
    }

    /** (a) user with allowedPdIds={pdA} sees only A-instances */
    @Test
    void allowedPdIds_seesOwnInstances() {
        PagedDataDTO<ProcessInstance> result = queryService.findProcessInstances(
            new ProcessInstanceQuery(), Set.of(pdIdA));
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getId()).isEqualTo(piIdA);
    }

    /** (b) user with allowedPdIds={pdA} does NOT see B-instances */
    @Test
    void allowedPdIds_excludesOtherTenant() {
        PagedDataDTO<ProcessInstance> result = queryService.findProcessInstances(
            new ProcessInstanceQuery(), Set.of(pdIdA));
        assertThat(result.getData()).noneMatch(pi -> pi.getId().equals(piIdB));
    }

    /** (c) admin (null allowedPdIds) sees everything */
    @Test
    void nullAllowedPdIds_seesEverything() {
        PagedDataDTO<ProcessInstance> result = queryService.findProcessInstances(
            new ProcessInstanceQuery(), null);
        assertThat(result.getData()).hasSizeGreaterThanOrEqualTo(2);
    }

    /** (d) empty allowedPdIds → denied (default DENY) */
    @Test
    void emptyAllowedPdIds_denied() {
        PagedDataDTO<ProcessInstance> result = queryService.findProcessInstances(
            new ProcessInstanceQuery(), Set.of());
        assertThat(result.getData()).isEmpty();
    }
}
