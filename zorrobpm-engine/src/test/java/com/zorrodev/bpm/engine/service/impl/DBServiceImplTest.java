package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.db.SignalSubscriptionDbOperations;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.service.db.IncidentDbOperations;
import com.zorrodev.bpm.engine.service.db.MessageSubscriptionDbOperations;
import com.zorrodev.bpm.engine.service.db.ServiceTaskDbOperations;
import com.zorrodev.bpm.engine.service.db.UserTaskDbOperations;
import com.zorrodev.bpm.engine.service.db.VariableDbOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DBServiceImplTest {

    @Mock private com.zorrodev.bpm.engine.service.db.ProcessDefinitionDbOperations processDefinitionDbOperations;
    @Mock private com.zorrodev.bpm.engine.service.db.TokenDbOperations tokenDbOperations;
    @Mock private com.zorrodev.bpm.engine.service.db.ParallelGatewayDbOperations parallelGatewayDbOperations;
    @Mock private com.zorrodev.bpm.engine.service.db.ProcessInstanceDbOperations processInstanceDbOperations;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private ServiceTaskDbOperations serviceTaskDbOperations;
    @Mock private UserTaskDbOperations userTaskDbOperations;
    @Mock private IncidentDbOperations incidentDbOperations;
    @Mock private VariableDbOperations variableDbOperations;
    @Mock private MessageSubscriptionDbOperations messageSubscriptionDbOperations;
    @Mock private TimerJobRepository timerJobRepository;
    @Mock private SignalSubscriptionDbOperations signalSubscriptionDbOperations;
    @Mock private ProcessInstanceMapper processInstanceMapper;
    @Mock private com.zorrodev.bpm.engine.event.DomainEventEmitter domainEventEmitter;

    @InjectMocks
    private DBServiceImpl dbService;

    @Test
    void createProcessInstance_savesEntityAndVariables() {
        UUID parentActivityId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        ProcessVariable v1 = newVar("a", "1", ProcessVariableType.LONG);
        ProcessVariable v2 = newVar("b", "x", ProcessVariableType.STRING);
        UUID expected = UUID.randomUUID();
        when(processInstanceDbOperations.createProcessInstance(parentActivityId, processDefinitionId, List.of(v1, v2))).thenReturn(expected);

        UUID id = dbService.createProcessInstance(parentActivityId, processDefinitionId, List.of(v1, v2));

        assertThat(id).isEqualTo(expected);
        verify(processInstanceDbOperations).createProcessInstance(parentActivityId, processDefinitionId, List.of(v1, v2));
    }

    @Test
    void createProcessInstance_nullVariables_savesEmpty() {
        UUID expected = UUID.randomUUID();
        when(processInstanceDbOperations.createProcessInstance(any(), any(), any())).thenReturn(expected);
        UUID id = dbService.createProcessInstance(null, UUID.randomUUID(), null);

        assertThat(id).isEqualTo(expected);
        verify(processInstanceDbOperations).createProcessInstance(any(), any(), any());
    }

    @Test
    void createActivity_byElement_persistsEntity() {
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        BpmnElementModel element = new BpmnElementModel();
        element.setId("startEvent");
        element.setType(BpmnElementType.START_EVENT);

        UUID id = dbService.createActivity(processInstanceId, token, element);

        assertThat(id).isNotNull();
        ArgumentCaptor<ActivityEntity> captor = ArgumentCaptor.forClass(ActivityEntity.class);
        verify(activityRepository).saveAndFlush(captor.capture());
        ActivityEntity saved = captor.getValue();
        assertThat(saved.getBpmnElementId()).isEqualTo("startEvent");
        assertThat(saved.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(saved.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(saved.getToken()).isEqualTo(token);
        assertThat(saved.getStatus()).isEqualTo(ActivityStatus.CREATED);
    }

    @Test
    void createActivity_byFlow_persistsAsSequenceFlow() {
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        BpmnFlowModel flow = new BpmnFlowModel();
        flow.setFlowId("flow1");

        UUID id = dbService.createActivity(processInstanceId, token, flow);

        assertThat(id).isNotNull();
        ArgumentCaptor<ActivityEntity> captor = ArgumentCaptor.forClass(ActivityEntity.class);
        verify(activityRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(BpmnElementType.SEQUENCE_FLOW);
        assertThat(captor.getValue().getBpmnElementId()).isEqualTo("flow1");
    }

    @Test
    void completeActivity_marksCompleted() {
        UUID activityId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();

        ActivityEntity activityEntity = new ActivityEntity();
        activityEntity.setId(activityId);
        activityEntity.setProcessInstanceId(processInstanceId);
        activityEntity.setBpmnElementId("element1");
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activityEntity));

        ProcessInstanceEntity piEntity = new ProcessInstanceEntity();
        piEntity.setId(processInstanceId);
        piEntity.setProcessDefinitionId(UUID.randomUUID());
        when(processInstanceRepository.findById(processInstanceId)).thenReturn(Optional.of(piEntity));

        dbService.completeActivity(activityId);

        verify(activityRepository).setStatusAndCompletedAt(eq(activityId), eq(ActivityStatus.COMPLETED), any(Instant.class));
        verify(domainEventEmitter).emitActivityCompleted(eq(processInstanceId), any(UUID.class), eq("element1"), isNull());
    }

    @Test
    void getProcessInstance_returnsMappedDTO() {
        UUID id = UUID.randomUUID();
        ProcessInstance dto = new ProcessInstance();
        dto.setId(id);
        when(processInstanceDbOperations.getProcessInstance(id)).thenReturn(dto);

        assertThat(dbService.getProcessInstance(id)).isSameAs(dto);
        verify(processInstanceDbOperations).getProcessInstance(id);
    }

    @Test
    void getProcessInstance_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(processInstanceDbOperations.getProcessInstance(id)).thenThrow(new NoSuchElementException());

        assertThatThrownBy(() -> dbService.getProcessInstance(id)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void createServiceTask_delegates() {
        UUID activityId = UUID.randomUUID();
        dbService.createServiceTask(activityId);
        verify(serviceTaskDbOperations).createServiceTask(activityId);
    }

    // ─── WO-EVT-9: stable job id in domain events ───────────────────────
    // WO-DEBT-1h: createServiceTask now delegates, real behaviour characterized in ServiceTaskDbOperationsImplTest

    @Test
    void createServiceTask_withJob_delegates() {
        UUID activityId = UUID.randomUUID();
        dbService.createServiceTask(activityId, 3, "draftCreate");
        verify(serviceTaskDbOperations).createServiceTask(activityId, 3, "draftCreate");
    }

    @Test
    void evt9_activityCompleted_serviceTaskCarriesJob_userTaskStaysEmpty() {
        UUID serviceActivityId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();

        ActivityEntity svc = new ActivityEntity();
        svc.setId(serviceActivityId);
        svc.setProcessInstanceId(processInstanceId);
        svc.setBpmnElementId("Activity_7f3");
        when(activityRepository.findById(serviceActivityId)).thenReturn(Optional.of(svc));

        ServiceTaskEntity st = new ServiceTaskEntity();
        st.setId(serviceActivityId);
        st.setJob("draftCreate");
        when(serviceTaskRepository.findById(serviceActivityId)).thenReturn(Optional.of(st));

        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(UUID.randomUUID());
        when(processInstanceRepository.findById(processInstanceId)).thenReturn(Optional.of(pi));

        dbService.completeActivity(serviceActivityId);
        // criterion 2a: service task carries data.job
        verify(domainEventEmitter).emitActivityCompleted(eq(processInstanceId), any(UUID.class), eq("Activity_7f3"), eq("draftCreate"));

        // criterion 2b: non-service-task element keeps data empty (old overload, no job param)
        UUID gatewayActivityId = UUID.randomUUID();
        ActivityEntity gw = new ActivityEntity();
        gw.setId(gatewayActivityId);
        gw.setProcessInstanceId(processInstanceId);
        gw.setBpmnElementId("Gateway_1");
        when(activityRepository.findById(gatewayActivityId)).thenReturn(Optional.of(gw));
        when(serviceTaskRepository.findById(gatewayActivityId)).thenReturn(Optional.empty());

        dbService.completeActivity(gatewayActivityId);
        verify(domainEventEmitter).emitActivityCompleted(eq(processInstanceId), any(UUID.class), eq("Gateway_1"), isNull());
    }

    @Test
    void incident_delegates() {
        UUID activityId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(incidentDbOperations.createIncident(activityId, "boom")).thenReturn(id);
        assertThat(dbService.createIncident(activityId, "boom")).isEqualTo(id);
        verify(incidentDbOperations).createIncident(activityId, "boom");
        dbService.completeIncident(id);
        verify(incidentDbOperations).completeIncident(id);
    }

    @Test
    void createUserTask_delegates() {
        UUID activityId = UUID.randomUUID();
        dbService.createUserTask(activityId, "ivanov", "managers", "form1");
        verify(userTaskDbOperations).createUserTask(activityId, "ivanov", "managers", "form1");
    }

    @Test
    void completeServiceTask_delegates() {
        UUID id = UUID.randomUUID();
        dbService.completeServiceTask(id);
        verify(serviceTaskDbOperations).completeServiceTask(id);
    }

    @Test
    void completeUserTask_delegates() {
        UUID id = UUID.randomUUID();
        dbService.completeUserTask(id);
        verify(userTaskDbOperations).completeUserTask(id);
    }

    @Test
    void getActivity_returnsMappedDTO() {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity();
        entity.setId(id);
        entity.setBpmnElementId("e1");
        entity.setType(BpmnElementType.SERVICE_TASK);
        entity.setStatus(ActivityStatus.CREATED);
        entity.setProcessInstanceId(UUID.randomUUID());
        entity.setToken(UUID.randomUUID());

        when(activityRepository.findById(id)).thenReturn(Optional.of(entity));

        Activity result = dbService.getActivity(id);

        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getBpmnElementId()).isEqualTo("e1");
        assertThat(result.getType()).isEqualTo(BpmnElementType.SERVICE_TASK);
        assertThat(result.getProcessInstanceId()).isEqualTo(entity.getProcessInstanceId());
    }

    @Test
    void getActivity_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(activityRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dbService.getActivity(id)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getVariables_delegates() {
        UUID processInstanceId = UUID.randomUUID();
        List<ProcessVariable> expected = List.of(newVar("a", "1", ProcessVariableType.LONG));
        when(variableDbOperations.getVariables(processInstanceId)).thenReturn(expected);
        assertThat(dbService.getVariables(processInstanceId)).isEqualTo(expected);
        verify(variableDbOperations).getVariables(processInstanceId);
    }

    @Test
    void setVariables_delegates() {
        UUID processInstanceId = UUID.randomUUID();
        List<ProcessVariable> vars = List.of(
            newVar("a", "1", ProcessVariableType.LONG),
            newVar("b", "x", ProcessVariableType.STRING)
        );
        dbService.setVariables(processInstanceId, vars);
        verify(variableDbOperations).setVariables(processInstanceId, vars);
    }

    @Test
    void getActivitiesByTokenAndBpmnElementId_returnsMapped() {
        UUID token = UUID.randomUUID();
        ActivityEntity e = new ActivityEntity();
        e.setId(UUID.randomUUID());
        e.setBpmnElementId("x");

        when(activityRepository.findByTokenAndBpmnElementId(token, "x")).thenReturn(List.of(e));

        List<Activity> result = dbService.getActivitiesByTokenAndBpmnElementId(token, "x");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBpmnElementId()).isEqualTo("x");
    }

    @Test
    void getProcessDefinition_returnsMapped() {
        ProcessDefinition expected = new ProcessDefinition();
        expected.setId(UUID.randomUUID());
        expected.setKey("k");
        expected.setVersion(2);
        when(processDefinitionDbOperations.getProcessDefinition("k", 2)).thenReturn(expected);

        ProcessDefinition result = dbService.getProcessDefinition("k", 2);

        assertThat(result).isEqualTo(expected);
        verify(processDefinitionDbOperations).getProcessDefinition("k", 2);
    }

    @Test
    void getProcessDefinition_throwsWhenMissing() {
        when(processDefinitionDbOperations.getProcessDefinition("k", 2)).thenThrow(new NoSuchElementException());
        assertThatThrownBy(() -> dbService.getProcessDefinition("k", 2)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void createToken_delegates() {
        UUID parentId = UUID.randomUUID();
        Token expected = new Token();
        expected.setId(UUID.randomUUID());
        expected.setParentId(parentId);
        when(tokenDbOperations.createToken(parentId)).thenReturn(expected);

        Token token = dbService.createToken(parentId);

        assertThat(token).isEqualTo(expected);
        verify(tokenDbOperations).createToken(parentId);
    }

    @Test
    void getToken_delegates() {
        UUID id = UUID.randomUUID();
        Token expected = new Token();
        expected.setId(id);
        when(tokenDbOperations.getToken(id)).thenReturn(expected);

        Token result = dbService.getToken(id);

        assertThat(result).isEqualTo(expected);
        verify(tokenDbOperations).getToken(id);
    }

    @Test
    void getToken_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(tokenDbOperations.getToken(id)).thenThrow(new NoSuchElementException());
        assertThatThrownBy(() -> dbService.getToken(id)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getMaxProcessDefinitionVersionByKey_returnsValueOrZero() {
        when(processDefinitionDbOperations.getMaxProcessDefinitionVersionByKey("present")).thenReturn(7);
        when(processDefinitionDbOperations.getMaxProcessDefinitionVersionByKey("absent")).thenReturn(0);

        assertThat(dbService.getMaxProcessDefinitionVersionByKey("present")).isEqualTo(7);
        assertThat(dbService.getMaxProcessDefinitionVersionByKey("absent")).isEqualTo(0);
        verify(processDefinitionDbOperations).getMaxProcessDefinitionVersionByKey("present");
        verify(processDefinitionDbOperations).getMaxProcessDefinitionVersionByKey("absent");
    }

    @Test
    void completeProcessInstance_callsRepository() {
        UUID id = UUID.randomUUID();
        dbService.completeProcessInstance(id);
        verify(processInstanceDbOperations).completeProcessInstance(id);
    }

    @Test
    void createIncident_delegates() {
        UUID activityId = UUID.randomUUID();
        UUID expected = UUID.randomUUID();
        when(incidentDbOperations.createIncident(activityId, "boom")).thenReturn(expected);
        assertThat(dbService.createIncident(activityId, "boom")).isEqualTo(expected);
        verify(incidentDbOperations).createIncident(activityId, "boom");
    }

    @Test
    void getIncident_delegates() {
        UUID incidentId = UUID.randomUUID();
        Incident expected = new Incident(); expected.setId(incidentId);
        when(incidentDbOperations.getIncident(incidentId)).thenReturn(expected);
        assertThat(dbService.getIncident(incidentId)).isEqualTo(expected);
        verify(incidentDbOperations).getIncident(incidentId);
    }

    @Test
    void completeIncident_delegates() {
        UUID incidentId = UUID.randomUUID();
        dbService.completeIncident(incidentId);
        verify(incidentDbOperations).completeIncident(incidentId);
    }

    @Test
    void createTimerJob_persistsUnfiredJob() {
        UUID activityId = UUID.randomUUID();
        Instant dueAt = Instant.now().plusSeconds(60);

        UUID id = dbService.createTimerJob(activityId, dueAt, null, null, null, null);

        assertThat(id).isNotNull();
        ArgumentCaptor<TimerJobEntity> captor = ArgumentCaptor.forClass(TimerJobEntity.class);
        verify(timerJobRepository).save(captor.capture());
        assertThat(captor.getValue().getActivityId()).isEqualTo(activityId);
        assertThat(captor.getValue().getDueAt()).isEqualTo(dueAt);
        assertThat(captor.getValue().isFired()).isFalse();
    }

    @Test
    void createTimerJob_withProcessInstanceId_persistsIt() {
        UUID activityId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        Instant dueAt = Instant.now().plusSeconds(60);

        UUID id = dbService.createTimerJob(activityId, dueAt, "boundary1", 1, "R/PT1M", processInstanceId);

        assertThat(id).isNotNull();
        ArgumentCaptor<TimerJobEntity> captor = ArgumentCaptor.forClass(TimerJobEntity.class);
        verify(timerJobRepository).save(captor.capture());
        assertThat(captor.getValue().getProcessInstanceId()).isEqualTo(processInstanceId);
    }

    @Test
    void findDueTimerJobs_mapsEntities() {
        Instant now = Instant.now();
        TimerJobEntity entity = new TimerJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setActivityId(UUID.randomUUID());
        entity.setDueAt(now.minusSeconds(1));
        when(timerJobRepository.findByFiredFalseAndDueAtLessThanEqual(now)).thenReturn(List.of(entity));

        List<TimerJob> jobs = dbService.findDueTimerJobs(now);

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getActivityId()).isEqualTo(entity.getActivityId());
    }

    @Test
    void createMessageSubscription_delegates() {
        UUID processInstanceId = UUID.randomUUID(); UUID activityId = UUID.randomUUID(); UUID expected = UUID.randomUUID();
        when(messageSubscriptionDbOperations.createMessageSubscription(processInstanceId, activityId, "msg1")).thenReturn(expected);
        assertThat(dbService.createMessageSubscription(processInstanceId, activityId, "msg1")).isEqualTo(expected);
        verify(messageSubscriptionDbOperations).createMessageSubscription(processInstanceId, activityId, "msg1");
    }

    @Test
    void findMessageSubscriptions_delegates() {
        UUID processInstanceId = UUID.randomUUID(); MessageSubscription expected = new MessageSubscription();
        when(messageSubscriptionDbOperations.findMessageSubscriptions("msg1", processInstanceId)).thenReturn(List.of(expected));
        List<MessageSubscription> subs = dbService.findMessageSubscriptions("msg1", processInstanceId);
        assertThat(subs).hasSize(1);
        verify(messageSubscriptionDbOperations).findMessageSubscriptions("msg1", processInstanceId);
    }

    @Test
    void consumeMessageSubscription_delegates() {
        UUID id = UUID.randomUUID();
        when(messageSubscriptionDbOperations.consumeMessageSubscription(id)).thenReturn(true);
        assertThat(dbService.consumeMessageSubscription(id)).isTrue();
        verify(messageSubscriptionDbOperations).consumeMessageSubscription(id);
    }

    @Test
    void consumeMessageSubscription_delegatesFalse() {
        UUID id = UUID.randomUUID();
        when(messageSubscriptionDbOperations.consumeMessageSubscription(id)).thenReturn(false);
        assertThat(dbService.consumeMessageSubscription(id)).isFalse();
        verify(messageSubscriptionDbOperations).consumeMessageSubscription(id);
    }

    @Test
    void consumeSignalSubscription_delegates() {
        UUID id = UUID.randomUUID();
        when(signalSubscriptionDbOperations.consumeSignalSubscription(id)).thenReturn(true);
        assertThat(dbService.consumeSignalSubscription(id)).isTrue();
        verify(signalSubscriptionDbOperations).consumeSignalSubscription(id);
    }

    @Test
    void consumeSignalSubscription_delegatesFalse() {
        UUID id = UUID.randomUUID();
        when(signalSubscriptionDbOperations.consumeSignalSubscription(id)).thenReturn(false);
        assertThat(dbService.consumeSignalSubscription(id)).isFalse();
        verify(signalSubscriptionDbOperations).consumeSignalSubscription(id);
    }

    private static ProcessVariable newVar(String name, String value, ProcessVariableType type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }
}
