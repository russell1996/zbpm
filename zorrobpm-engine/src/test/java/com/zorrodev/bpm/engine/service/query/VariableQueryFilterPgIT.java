package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.QueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DB-2 (N02+N15): variable query filters on real PostgreSQL.
 *
 * <ul>
 *   <li>N02 — {@code byValue} must filter on the real JPA attribute
 *   ({@code textValue}); the old {@code root.get("value")} cannot build a
 *   Criteria path and fails instead of returning rows.</li>
 *   <li>N15 — the internal {@code _mi_batch_*} exclusion must be a literal
 *   prefix match: a user variable like {@code amiXbatchZvalue} (which matches
 *   the unescaped LIKE pattern {@code _mi_batch_%} via the {@code _}
 *   single-char wildcards) must stay visible while the real internal row
 *   stays hidden.</li>
 * </ul>
 */
class VariableQueryFilterPgIT extends PostgresIT {

    @Autowired private VariableRepository variableRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private QueryService queryService;

    private UUID newProcessInstance() {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey("db2-probe-" + UUID.randomUUID());
        pd.setName("DB-2 Probe");
        pd.setVersion(1);
        pd.setSha256(UUID.randomUUID().toString());
        pd.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pd);
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(UUID.randomUUID());
        pi.setProcessDefinitionId(pd.getId());
        pi.setStartedAt(Instant.now());
        return processInstanceRepository.save(pi).getId();
    }

    private void row(UUID processInstanceId, String name, String textValue) {
        ProcessVariableEntity e = new ProcessVariableEntity();
        e.setId(UUID.randomUUID());
        e.setProcessInstanceId(processInstanceId);
        e.setName(name);
        e.setType(ProcessVariableType.STRING);
        e.setTextValue(textValue);
        e.setScopeId(null);
        variableRepository.save(e);
    }

    private VariableQuery queryFor(UUID processInstanceId) {
        VariableQuery q = new VariableQuery();
        q.setPageIndex(0);
        q.setPageSize(100);
        q.setProcessInstanceId(processInstanceId);
        return q;
    }

    @Transactional
    @Test
    void byValue_exactMatch_returnsOnlyMatchingRow() {
        UUID pi = newProcessInstance();
        row(pi, "varA", "alpha");
        row(pi, "varB", "beta");

        VariableQuery q = queryFor(pi);
        q.setValue("alpha");
        PagedDataDTO<ProcessVariable> result = queryService.findVariables(q, null);

        assertThat(result.getData()).extracting(ProcessVariable::getName).containsExactly("varA");
    }

    @Transactional
    @Test
    void internalBatchVariable_excludedButLookalikeUserVariable_visible() {
        UUID pi = newProcessInstance();
        row(pi, "_mi_batch_testmi", "internal");
        row(pi, "amiXbatchZvalue", "user-value");
        row(pi, "plainVar", "plain");

        PagedDataDTO<ProcessVariable> result = queryService.findVariables(queryFor(pi), null);

        assertThat(result.getData()).extracting(ProcessVariable::getName)
            .contains("amiXbatchZvalue", "plainVar")
            .doesNotContain("_mi_batch_testmi");
    }
}
