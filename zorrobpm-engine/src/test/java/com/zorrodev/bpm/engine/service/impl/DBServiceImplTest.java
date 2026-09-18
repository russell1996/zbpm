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
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.service.db.ActivityDbOperations;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.db.SignalSubscriptionDbOperations;
import com.zorrodev.bpm.engine.service.db.TimerDbOperations;
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
    @Mock private ServiceTaskDbOperations serviceTaskDbOperations;
    @Mock private UserTaskDbOperations userTaskDbOperations;
    @Mock private IncidentDbOperations incidentDbOperations;
    @Mock private VariableDbOperations variableDbOperations;
    @Mock private MessageSubscriptionDbOperations messageSubscriptionDbOperations;
    @Mock private SignalSubscriptionDbOperations signalSubscriptionDbOperations;
    @Mock private TimerDbOperations timerDbOperations;
    @Mock private ActivityDbOperations activityDbOperations;

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
    void createActivity_delegates() {
        UUID pi = UUID.randomUUID(); UUID token = UUID.randomUUID(); BpmnElementModel el = new BpmnElementModel(); el.setId("x"); UUID expected = UUID.randomUUID();
        when(activityDbOperations.createActivity(eq(pi), eq(token), any(BpmnElementModel.class))).thenReturn(expected);
        assertThat(dbService.createActivity(pi, token, el)).isEqualTo(expected);
        verify(activityDbOperations).createActivity(eq(pi), eq(token), any(BpmnElementModel.class));
    }

    @Test
    void createActivity_byFlow_delegates() {
        UUID pi = UUID.randomUUID(); UUID token = UUID.randomUUID(); BpmnFlowModel flow = new BpmnFlowModel(); flow.setFlowId("f1"); UUID expected = UUID.randomUUID();
        when(activityDbOperations.createActivity(eq(pi), eq(token), any(BpmnFlowModel.class))).thenReturn(expected);
        assertThat(dbService.createActivity(pi, token, flow)).isEqualTo(expected);
        verify(activityDbOperations).createActivity(eq(pi), eq(token), any(BpmnFlowModel.class));
    }

    @Test
    void completeActivity_delegates() {
        UUID id = UUID.randomUUID();
        dbService.completeActivity(id);
        verify(activityDbOperations).completeActivity(id);
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

    // ─── WO-C8-11: listener index passthrough ───────────────────────────

    @Test
    void createServiceTask_withListenerIndex_delegates() {
        UUID activityId = UUID.randomUUID();
        dbService.createServiceTask(activityId, 3, "job-c8", 0);
        verify(serviceTaskDbOperations).createServiceTask(activityId, 3, "job-c8", 0);
    }

    @Test
    void setPendingListenerIndex_delegates() {
        UUID id = UUID.randomUUID();
        dbService.setPendingListenerIndex(id, 1);
        verify(serviceTaskDbOperations).setPendingListenerIndex(id, 1);
    }

    @Test
    void getServiceTaskPendingListenerIndex_delegates() {
        UUID id = UUID.randomUUID();
        when(serviceTaskDbOperations.getPendingListenerIndex(id)).thenReturn(0);
        assertThat(dbService.getServiceTaskPendingListenerIndex(id)).isEqualTo(0);
        verify(serviceTaskDbOperations).getPendingListenerIndex(id);
    }

    @Test
    void setPendingEndListenerIndex_delegates() {
        UUID id = UUID.randomUUID();
        dbService.setPendingEndListenerIndex(id, 1);
        verify(serviceTaskDbOperations).setPendingEndListenerIndex(id, 1);
    }

    @Test
    void getServiceTaskPendingEndListenerIndex_delegates() {
        UUID id = UUID.randomUUID();
        when(serviceTaskDbOperations.getPendingEndListenerIndex(id)).thenReturn(0);
        assertThat(dbService.getServiceTaskPendingEndListenerIndex(id)).isEqualTo(0);
        verify(serviceTaskDbOperations).getPendingEndListenerIndex(id);
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
        dbService.createUserTask(activityId, "ivanov", "managers", "form1", null, null, null, null, null);
        verify(userTaskDbOperations).createUserTask(activityId, "ivanov", "managers", "form1", null, null, null, null, null);
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
    void getActivity_delegates() {
        UUID id = UUID.randomUUID(); Activity expected = new Activity(); expected.setId(id);
        when(activityDbOperations.getActivity(id)).thenReturn(expected);
        assertThat(dbService.getActivity(id)).isEqualTo(expected);
        verify(activityDbOperations).getActivity(id);
    }

    @Test
    void getActivity_throwsWhenMissing_delegates() {
        UUID id = UUID.randomUUID();
        when(activityDbOperations.getActivity(id)).thenThrow(new NoSuchElementException());
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
    void getActivitiesByTokenAndBpmnElementId_delegates() {
        UUID token = UUID.randomUUID(); List<Activity> expected = List.of(new Activity());
        when(activityDbOperations.getActivitiesByTokenAndBpmnElementId(token, "x")).thenReturn(expected);
        assertThat(dbService.getActivitiesByTokenAndBpmnElementId(token, "x")).isEqualTo(expected);
        verify(activityDbOperations).getActivitiesByTokenAndBpmnElementId(token, "x");
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
    void getMaxProcessDefinitionVersionByKeyAndDeploymentId_delegates() {
        UUID deploymentId = UUID.randomUUID();
        when(processDefinitionDbOperations.getMaxProcessDefinitionVersionByKeyAndDeploymentId("present", deploymentId)).thenReturn(2);

        assertThat(dbService.getMaxProcessDefinitionVersionByKeyAndDeploymentId("present", deploymentId)).isEqualTo(2);
        verify(processDefinitionDbOperations).getMaxProcessDefinitionVersionByKeyAndDeploymentId("present", deploymentId);
    }

    @Test
    void getDeploymentIdByProcessDefinitionId_delegates() {
        UUID pdId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(processDefinitionDbOperations.getDeploymentIdByProcessDefinitionId(pdId)).thenReturn(deploymentId);

        assertThat(dbService.getDeploymentIdByProcessDefinitionId(pdId)).isEqualTo(deploymentId);
        verify(processDefinitionDbOperations).getDeploymentIdByProcessDefinitionId(pdId);
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
    void createTimerJob_delegates() {
        UUID activityId = UUID.randomUUID(); Instant dueAt = Instant.now().plusSeconds(60); UUID expected = UUID.randomUUID();
        when(timerDbOperations.createTimerJob(eq(activityId), any(Instant.class), any(), any(), any(), any())).thenReturn(expected);
        assertThat(dbService.createTimerJob(activityId, dueAt, null, null, null, null)).isEqualTo(expected);
        verify(timerDbOperations).createTimerJob(eq(activityId), any(Instant.class), any(), any(), any(), any());
    }

    @Test
    void createTimerJob_withProcessInstanceId_delegates() {
        UUID activityId = UUID.randomUUID(); UUID processInstanceId = UUID.randomUUID(); Instant dueAt = Instant.now().plusSeconds(60); UUID expected = UUID.randomUUID();
        when(timerDbOperations.createTimerJob(eq(activityId), any(Instant.class), eq("boundary1"), eq(1), eq("R/PT1M"), eq(processInstanceId))).thenReturn(expected);
        assertThat(dbService.createTimerJob(activityId, dueAt, "boundary1", 1, "R/PT1M", processInstanceId)).isEqualTo(expected);
        verify(timerDbOperations).createTimerJob(eq(activityId), any(Instant.class), eq("boundary1"), eq(1), eq("R/PT1M"), eq(processInstanceId));
    }

    @Test
    void findDueTimerJobs_delegates() {
        Instant now = Instant.now(); TimerJob expected = new TimerJob();
        when(timerDbOperations.findDueTimerJobs(now)).thenReturn(List.of(expected));
        List<TimerJob> jobs = dbService.findDueTimerJobs(now);
        assertThat(jobs).hasSize(1);
        verify(timerDbOperations).findDueTimerJobs(now);
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
