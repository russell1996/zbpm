package com.zorrodev.bpm.engine.processdefinition;

import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ARCH-1c: ProcessDefinition read isolation on real PostgreSQL.
 * Tests that allowedPdIds filter correctly scopes process definition visibility.
 *
 * G-N POF: comment the ID filter in getProcessDefinitions(params, allowedPdIds) -> excludesB should show B.
 */
@Tag("pg")
public class ProcessDefinitionTenantIsolationPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired JdbcTemplate jdbc;

    private UUID pdIdA;
    private UUID pdIdB;
    private static final String KEY_A = "isolation-test-a";
    private static final String KEY_B = "isolation-test-b";

    @BeforeEach
    void setUp() {
        pdIdA = UUID.randomUUID();
        pdIdB = UUID.randomUUID();
        jdbc.update("DELETE FROM process_definitions WHERE code IN (?, ?)", KEY_A, KEY_B);
        String shaA = UUID.randomUUID().toString();
        String shaB = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, name, version, sha256, created_at) VALUES (?, ?, 'PD A', 1, ?, now())",
            pdIdA, KEY_A, shaA);
        jdbc.update(
            "INSERT INTO process_definitions (id, code, name, version, sha256, created_at) VALUES (?, ?, 'PD B', 1, ?, now())",
            pdIdB, KEY_B, shaB);
    }

    @Test
    void pdList_allowedPdIds_seesOnlyA() {
        var params = new ProcessDefinitionsQueryParameters();
        PagedDataDTO<ProcessDefinition> result = processDefinitionService.getProcessDefinitions(params, Set.of(pdIdA));
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getId()).isEqualTo(pdIdA);
    }

    @Test
    void pdList_allowedPdIds_excludesB() {
        var params = new ProcessDefinitionsQueryParameters();
        PagedDataDTO<ProcessDefinition> result = processDefinitionService.getProcessDefinitions(params, Set.of(pdIdA));
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData()).noneMatch(pd -> pd.getId().equals(pdIdB));
    }

    @Test
    void pdList_nullPdIds_seesAll() {
        var params = new ProcessDefinitionsQueryParameters();
        PagedDataDTO<ProcessDefinition> result = processDefinitionService.getProcessDefinitions(params, null);
        assertThat(result.getData().stream().map(ProcessDefinition::getId))
            .contains(pdIdA, pdIdB);
    }

    @Test
    void pdList_emptyPdIds_denied() {
        var params = new ProcessDefinitionsQueryParameters();
        PagedDataDTO<ProcessDefinition> result = processDefinitionService.getProcessDefinitions(params, Set.of());
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void pdList_allowedPdIds_bothIds_seesBoth() {
        var params = new ProcessDefinitionsQueryParameters();
        PagedDataDTO<ProcessDefinition> result = processDefinitionService.getProcessDefinitions(params, Set.of(pdIdA, pdIdB));
        assertThat(result.getData()).hasSize(2);
        assertThat(result.getData().stream().map(ProcessDefinition::getId))
            .containsExactlyInAnyOrder(pdIdA, pdIdB);
    }
}
