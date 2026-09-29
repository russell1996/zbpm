package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.entity.*;
import com.zorrodev.bpm.engine.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
class QueryServiceImplTenantIsolationPgIT {

    @Autowired private QueryServiceImpl queryService;
    @Autowired private TimerJobRepository timerJobRepository;
    @Autowired private MessageSubscriptionRepository messageSubscriptionRepository;
    @Autowired private VariableRepository variableRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private TokenRepository tokenRepository;

    private UUID cleanupPi1, cleanupPi2, cleanupTimer1, cleanupTimer2, cleanupMsg1, cleanupMsg2, cleanupVar1, cleanupVar2;
    private UUID cleanupIncident1, cleanupIncident2, cleanupActivity1, cleanupActivity2;
    private UUID cleanupToken1, cleanupToken2;

    @AfterEach
    void cleanup() {
        if (cleanupIncident1 != null) incidentRepository.deleteById(cleanupIncident1);
        if (cleanupIncident2 != null) incidentRepository.deleteById(cleanupIncident2);
        if (cleanupActivity1 != null) activityRepository.deleteById(cleanupActivity1);
        if (cleanupActivity2 != null) activityRepository.deleteById(cleanupActivity2);
        if (cleanupToken1 != null) tokenRepository.deleteById(cleanupToken1);
        if (cleanupToken2 != null) tokenRepository.deleteById(cleanupToken2);
        if (cleanupTimer1 != null) timerJobRepository.deleteById(cleanupTimer1);
        if (cleanupTimer2 != null) timerJobRepository.deleteById(cleanupTimer2);
        if (cleanupMsg1 != null) messageSubscriptionRepository.deleteById(cleanupMsg1);
        if (cleanupMsg2 != null) messageSubscriptionRepository.deleteById(cleanupMsg2);
        if (cleanupVar1 != null) variableRepository.deleteById(cleanupVar1);
        if (cleanupVar2 != null) variableRepository.deleteById(cleanupVar2);
        if (cleanupPi1 != null) processInstanceRepository.deleteById(cleanupPi1);
        if (cleanupPi2 != null) processInstanceRepository.deleteById(cleanupPi2);
        cleanupPi1 = cleanupPi2 = cleanupTimer1 = cleanupTimer2 = cleanupMsg1 = cleanupMsg2 = cleanupVar1 = cleanupVar2 = null;
        cleanupIncident1 = cleanupIncident2 = cleanupActivity1 = cleanupActivity2 = null;
        cleanupToken1 = cleanupToken2 = null;
    }

    @Test
    void findTimerJobs_processInstanceInAllowedDefinitions_filters() {
        UUID pdAllowed = UUID.randomUUID();
        UUID pdDenied = UUID.randomUUID();
        UUID piAllowed = createPi(pdAllowed);
        UUID piDenied = createPi(pdDenied);
        cleanupPi1 = piAllowed; cleanupPi2 = piDenied;
        UUID timerAllowed = createTimer(piAllowed);
        UUID timerDenied = createTimer(piDenied);
        cleanupTimer1 = timerAllowed; cleanupTimer2 = timerDenied;

        TimerJobQuery q = new TimerJobQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<TimerJob> result = queryService.findTimerJobs(q, List.of(pdAllowed)).getData();
        assertThat(result).extracting(TimerJob::getId).contains(timerAllowed).doesNotContain(timerDenied);
        assertThat(result).extracting(TimerJob::getProcessInstanceId).contains(piAllowed).doesNotContain(piDenied);
    }

    @Test
    void findMessageSubscriptions_processInstanceInAllowedDefinitions_filters() {
        UUID pdAllowed = UUID.randomUUID();
        UUID pdDenied = UUID.randomUUID();
        UUID piAllowed = createPi(pdAllowed);
        UUID piDenied = createPi(pdDenied);
        cleanupPi1 = piAllowed; cleanupPi2 = piDenied;
        UUID msgAllowed = createMsg(piAllowed);
        UUID msgDenied = createMsg(piDenied);
        cleanupMsg1 = msgAllowed; cleanupMsg2 = msgDenied;

        MessageSubscriptionQuery q = new MessageSubscriptionQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<MessageSubscription> result = queryService.findMessageSubscriptions(q, List.of(pdAllowed)).getData();
        assertThat(result).extracting(MessageSubscription::getId).contains(msgAllowed).doesNotContain(msgDenied);
    }

    @Test
    void findVariables_processInstanceInAllowedDefinitions_filters() {
        UUID pdAllowed = UUID.randomUUID();
        UUID pdDenied = UUID.randomUUID();
        UUID piAllowed = createPi(pdAllowed);
        UUID piDenied = createPi(pdDenied);
        cleanupPi1 = piAllowed; cleanupPi2 = piDenied;
        UUID varAllowed = createVar(piAllowed);
        UUID varDenied = createVar(piDenied);
        cleanupVar1 = varAllowed; cleanupVar2 = varDenied;

        VariableQuery q = new VariableQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<ProcessVariable> result = queryService.findVariables(q, List.of(pdAllowed)).getData();
        assertThat(result).extracting(ProcessVariable::getName).contains("k-allowed");
        assertThat(result).hasSize(1);
    }

    @Test
    void findIncidents_doubleSubquery_filters() {
        UUID pdAllowed = UUID.randomUUID();
        UUID pdDenied = UUID.randomUUID();
        UUID piAllowed = createPi(pdAllowed);
        UUID piDenied = createPi(pdDenied);
        cleanupPi1 = piAllowed; cleanupPi2 = piDenied;
        UUID activityAllowed = createActivity(piAllowed);
        UUID activityDenied = createActivity(piDenied);
        cleanupActivity1 = activityAllowed; cleanupActivity2 = activityDenied;
        UUID incidentAllowed = createIncident(activityAllowed);
        UUID incidentDenied = createIncident(activityDenied);
        cleanupIncident1 = incidentAllowed; cleanupIncident2 = incidentDenied;

        IncidentQuery q = new IncidentQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<Incident> result = queryService.findIncidents(q, List.of(pdAllowed)).getData();
        assertThat(result).extracting(Incident::getId).contains(incidentAllowed).doesNotContain(incidentDenied);
        assertThat(result).hasSize(1);
    }

    private UUID createPi(UUID pdId) {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(pdId);
        pd.setKey("k-" + pdId.toString().substring(0, 8));
        pd.setVersion(1);
        pd.setCreatedAt(Instant.now());
        pd.setSha256(UUID.randomUUID().toString());
        pd.setName("test");
        pd.setDeploymentState("ACTIVE");
        processDefinitionRepository.saveAndFlush(pd);
        assertThat(processDefinitionRepository.findById(pdId)).isPresent();
        UUID piId = UUID.randomUUID();
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(pdId);
        pi.setStartedAt(Instant.now());
        pi.setCompletedAt(null);
        pi.setInitiator("test");
        processInstanceRepository.saveAndFlush(pi);
        return piId;
    }

    private UUID createTimer(UUID piId) {
        UUID id = UUID.randomUUID();
        TimerJobEntity e = new TimerJobEntity();
        e.setId(id);
        e.setProcessInstanceId(piId);
        e.setActivityId(UUID.randomUUID());
        e.setDueAt(Instant.now().plusSeconds(60));
        e.setFired(false);
        e.setCreatedAt(Instant.now());
        timerJobRepository.saveAndFlush(e);
        return id;
    }

    private UUID createMsg(UUID piId) {
        UUID id = UUID.randomUUID();
        MessageSubscriptionEntity e = new MessageSubscriptionEntity();
        e.setId(id);
        e.setProcessInstanceId(piId);
        e.setActivityId(UUID.randomUUID());
        e.setMessageName("test-tenant-msg");
        e.setConsumed(false);
        e.setCreatedAt(Instant.now());
        messageSubscriptionRepository.saveAndFlush(e);
        return id;
    }

    private UUID createVar(UUID piId) {
        UUID id = UUID.randomUUID();
        ProcessVariableEntity e = new ProcessVariableEntity();
        e.setId(id);
        e.setProcessInstanceId(piId);
        e.setName("k-allowed");
        e.setType(ProcessVariableType.STRING);
        e.setTextValue("v");
        variableRepository.saveAndFlush(e);
        return id;
    }

    private UUID createActivity(UUID piId) {
        UUID id = UUID.randomUUID();
        // WO-OPS-12: fk_activities__token — activity ссылается только на существующий token.
        UUID tokenId = UUID.randomUUID();
        TokenEntity token = new TokenEntity();
        token.setId(tokenId);
        tokenRepository.saveAndFlush(token);
        if (cleanupToken1 == null) cleanupToken1 = tokenId; else cleanupToken2 = tokenId;
        ActivityEntity e = new ActivityEntity();
        e.setId(id);
        e.setProcessInstanceId(piId);
        e.setToken(tokenId);
        e.setBpmnElementId("task-" + id.toString().substring(0, 8));
        e.setCreatedAt(Instant.now());
        e.setType(BpmnElementType.SERVICE_TASK);
        e.setStatus(ActivityStatus.CREATED);
        activityRepository.saveAndFlush(e);
        return id;
    }

    private UUID createIncident(UUID activityId) {
        UUID id = UUID.randomUUID();
        IncidentEntity e = new IncidentEntity();
        e.setId(id);
        e.setActivityId(activityId);
        e.setMessage("test incident");
        e.setCreatedAt(Instant.now());
        incidentRepository.saveAndFlush(e);
        return id;
    }
}
