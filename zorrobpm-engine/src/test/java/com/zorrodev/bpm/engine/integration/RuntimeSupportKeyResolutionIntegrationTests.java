package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.RuntimeSupportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-31 CR-4: real-query characterization for the single-query key-resolution
 * refactors. The JPQL joins (cross-entity ON, subquery) are validated by Spring Data
 * only at startup/first call — mock tests would pass even if a query were invalid —
 * so these tests exercise the REAL repositories on H2 with real rows.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class RuntimeSupportKeyResolutionIntegrationTests {

    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ServiceTaskRepository serviceTaskRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private VariableRepository variableRepository;
    @Autowired private UiUserRepository uiUserRepository;
    @Autowired private TokenRepository tokenRepository;
    @Autowired private DBService dbService;
    @Autowired private RuntimeSupportService support;

    private ProcessDefinitionEntity newDefinition(String key) {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey(key);
        pd.setName("CR-4 probe");
        pd.setVersion(1);
        pd.setSha256(UUID.randomUUID().toString());
        pd.setCreatedAt(Instant.now());
        return processDefinitionRepository.save(pd);
    }

    private UUID newInstance(UUID processDefinitionId) {
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(UUID.randomUUID());
        pi.setProcessDefinitionId(processDefinitionId);
        pi.setStartedAt(Instant.now());
        return processInstanceRepository.save(pi).getId();
    }

    private ActivityEntity newActivity(UUID processInstanceId) {
        ActivityEntity a = new ActivityEntity();
        a.setId(UUID.randomUUID());
        a.setProcessInstanceId(processInstanceId);
        // WO-OPS-12: fk_activities__token — ссылаемся только на существующий token.
        UUID tokenId = UUID.randomUUID();
        TokenEntity token = new TokenEntity();
        token.setId(tokenId);
        tokenRepository.save(token);
        a.setToken(tokenId);
        a.setBpmnElementId("svc");
        a.setStatus(ActivityStatus.CREATED);
        a.setType(BpmnElementType.SERVICE_TASK);
        a.setCreatedAt(Instant.now());
        return activityRepository.save(a);
    }

    @Transactional
    @Test
    void resolveDefinitionKeyByInstance_joinQueryReturnsKey() {
        ProcessDefinitionEntity pd = newDefinition("cr4-inst-" + UUID.randomUUID());
        UUID pi = newInstance(pd.getId());

        assertThat(processInstanceRepository.findDefinitionKeyById(pi)).contains(pd.getKey());
        // missing instance → empty, not exception
        assertThat(processInstanceRepository.findDefinitionKeyById(UUID.randomUUID())).isEmpty();
    }

    @Transactional
    @Test
    void resolveDefinitionKeyByServiceTask_directColumnJoin() {
        ProcessDefinitionEntity pd = newDefinition("cr4-st-" + UUID.randomUUID());
        UUID pi = newInstance(pd.getId());
        ActivityEntity activity = newActivity(pi);
        ServiceTaskEntity st = new ServiceTaskEntity();
        st.setId(activity.getId());
        st.setBpmnElementId("svc");
        st.setProcessInstanceId(pi);
        st.setProcessDefinitionId(pd.getId());
        st.setCreatedAt(Instant.now());
        serviceTaskRepository.save(st);

        assertThat(serviceTaskRepository.findDefinitionKeyById(st.getId())).contains(pd.getKey());
        assertThat(serviceTaskRepository.findDefinitionKeyById(UUID.randomUUID())).isEmpty();
    }

    @Transactional
    @Test
    void resolveDefinitionKeyByIncident_nestedJoin() {
        ProcessDefinitionEntity pd = newDefinition("cr4-inc-" + UUID.randomUUID());
        UUID pi = newInstance(pd.getId());
        ActivityEntity activity = newActivity(pi);
        IncidentEntity incident = new IncidentEntity();
        incident.setId(UUID.randomUUID());
        incident.setActivityId(activity.getId());
        incident.setMessage("cr-4 probe");
        incident.setCreatedAt(Instant.now());
        incidentRepository.save(incident);

        assertThat(incidentRepository.findDefinitionKeyById(incident.getId())).contains(pd.getKey());
        assertThat(incidentRepository.findDefinitionKeyById(UUID.randomUUID())).isEmpty();
    }

    @Transactional
    @Test
    void membershipByDefinitionKey_trueWhenProcessAndMemberExist() {
        ProcessDefinitionEntity pd = newDefinition("cr4-mem-" + UUID.randomUUID());
        UUID pi = newInstance(pd.getId());
        UUID userId = UUID.randomUUID();
        seedProcessAndMember(pd.getKey(), userId);

        assertThat(processMemberRepository.isMemberByDefinitionKey(userId, pd.getKey())).isTrue();
        // user without membership in the same process → false
        assertThat(processMemberRepository.isMemberByDefinitionKey(UUID.randomUUID(), pd.getKey())).isFalse();
    }

    @Transactional
    @Test
    void membershipByDefinitionKey_falseWhenNoProcessForKey() {
        assertThat(processMemberRepository.isMemberByDefinitionKey(
            UUID.randomUUID(), "cr4-no-such-process-" + UUID.randomUUID())).isFalse();
    }

    @Transactional
    @Test
    void checkAssignee_allowsRealProcessMember() {
        ProcessDefinitionEntity pd = newDefinition("cr4-ca-" + UUID.randomUUID());
        UUID pi = newInstance(pd.getId());
        UUID userId = UUID.randomUUID();
        seedProcessAndMember(pd.getKey(), userId);

        // unassigned + no candidate groups → member can complete
        support.checkAssignee(new Principal.UserPrincipal(userId, "alice", "USER"), null, null, pi);
    }

    @Transactional
    @Test
    void checkAssignee_deniesNonMemberDespiteExistingProcess() {
        ProcessDefinitionEntity pd = newDefinition("cr4-ca-" + UUID.randomUUID());
        UUID pi = newInstance(pd.getId());
        seedProcessAndMember(pd.getKey(), UUID.randomUUID());

        UUID stranger = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            support.checkAssignee(new Principal.UserPrincipal(stranger, "eve", "USER"), null, null, pi))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Transactional
    @Test
    void getVariablesRootAndScoped_singleQueryScopedWins() {
        ProcessDefinitionEntity pd = newDefinition("cr4-var-" + UUID.randomUUID());
        UUID pi = newInstance(pd.getId());
        UUID scope = UUID.randomUUID();
        seedVariable(pi, "k", null, "root");
        seedVariable(pi, "k", scope, "scoped");
        seedVariable(pi, "rootOnly", null, "r");

        java.util.List<com.zorrodev.bpm.contract.model.ProcessVariable> result = dbService.getVariables(pi, scope);
        // root-only row present, duplicate name resolves to the SCOPED value
        assertThat(result).extracting(com.zorrodev.bpm.contract.model.ProcessVariable::getName)
            .containsExactlyInAnyOrder("k", "rootOnly");
        assertThat(result).filteredOn(v -> "k".equals(v.getName()))
            .singleElement()
            .extracting(com.zorrodev.bpm.contract.model.ProcessVariable::getValue)
            .isEqualTo("scoped");
    }

    private UiUserEntity seedUser(UUID id, String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(id);
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setUserType("HUMAN");
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        return uiUserRepository.save(user);
    }

    private void seedProcessAndMember(String definitionKey, UUID userId) {
        seedUser(userId, "cr4-user-" + userId.toString().substring(0, 8));
        ProcessEntity process = new ProcessEntity();
        process.setId(UUID.randomUUID());
        process.setDefinitionKey(definitionKey);
        process.setName("CR-4 proc");
        process.setCreatedAt(Instant.now());
        processRepository.save(process);

        ProcessMemberEntity member = new ProcessMemberEntity();
        member.setProcessId(process.getId());
        member.setUserId(userId);
        member.setRole("OWNER");
        member.setAddedAt(Instant.now());
        processMemberRepository.save(member);
    }

    private void seedVariable(UUID processInstanceId, String name, UUID scopeId, String value) {
        ProcessVariableEntity e = new ProcessVariableEntity();
        e.setId(UUID.randomUUID());
        e.setProcessInstanceId(processInstanceId);
        e.setName(name);
        e.setType(ProcessVariableType.STRING);
        e.setTextValue(value);
        e.setScopeId(scopeId);
        variableRepository.save(e);
    }
}