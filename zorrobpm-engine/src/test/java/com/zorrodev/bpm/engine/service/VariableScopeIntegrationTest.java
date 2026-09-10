package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-14: activity-scoped ioMapping variables must not leak into the
 * process variable list. Real rows + real query path (H2), not mocks:
 * one root variable (scopeId null) and one activity-scoped variable on the
 * same processInstanceId.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class VariableScopeIntegrationTest {

    @Autowired private VariableRepository variableRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private QueryService queryService;

    private UUID newProcessInstance() {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey("scope-probe-" + UUID.randomUUID());
        pd.setName("Scope Probe");
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

    private ProcessVariableEntity row(UUID processInstanceId, String name, UUID scopeId) {
        ProcessVariableEntity e = new ProcessVariableEntity();
        e.setId(UUID.randomUUID());
        e.setProcessInstanceId(processInstanceId);
        e.setName(name);
        e.setType(ProcessVariableType.STRING);
        e.setTextValue("v-" + name);
        e.setScopeId(scopeId);
        return variableRepository.save(e);
    }

    private VariableQuery queryFor(UUID processInstanceId) {
        VariableQuery q = new VariableQuery();
        q.setPageIndex(0);
        q.setPageSize(10);
        q.setProcessInstanceId(processInstanceId);
        return q;
    }

    @Transactional
    @Test
    void findVariables_withoutActivityId_returnsOnlyRootScope() {
        UUID pi = newProcessInstance();
        UUID activity = UUID.randomUUID();
        row(pi, "rootVar", null);
        row(pi, "scopedVar", activity);

        PagedDataDTO<ProcessVariable> result = queryService.findVariables(queryFor(pi), null);

        assertThat(result.getData()).extracting(ProcessVariable::getName).containsExactly("rootVar");
    }

    @Transactional
    @Test
    void findVariables_withActivityId_returnsOnlyThatScope() {
        UUID pi = newProcessInstance();
        UUID activity = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        row(pi, "rootVar", null);
        row(pi, "scopedVar", activity);
        row(pi, "otherScopedVar", other);

        VariableQuery q = queryFor(pi);
        q.setActivityId(activity);
        PagedDataDTO<ProcessVariable> result = queryService.findVariables(q, null);

        assertThat(result.getData()).extracting(ProcessVariable::getName).containsExactly("scopedVar");
    }

    @Transactional
    @Test
    void findVariables_scopedRow_mapsScopeIdToActivityId() {
        UUID pi = newProcessInstance();
        UUID activity = UUID.randomUUID();
        row(pi, "scopedVar", activity);

        VariableQuery q = queryFor(pi);
        q.setActivityId(activity);
        PagedDataDTO<ProcessVariable> result = queryService.findVariables(q, null);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getActivityId()).isEqualTo(activity);
    }

    @Transactional
    @Test
    void findVariables_rootRow_mapsNullActivityId() {
        UUID pi = newProcessInstance();
        row(pi, "rootVar", null);

        PagedDataDTO<ProcessVariable> result = queryService.findVariables(queryFor(pi), null);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getActivityId()).isNull();
    }
}
