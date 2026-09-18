package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.service.db.TimerDbOperations;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.service.db.ActivityDbOperations;
import com.zorrodev.bpm.engine.service.db.SignalSubscriptionDbOperations;
import com.zorrodev.bpm.engine.service.db.IncidentDbOperations;
import com.zorrodev.bpm.engine.service.db.MessageSubscriptionDbOperations;
import com.zorrodev.bpm.engine.service.db.ParallelGatewayDbOperations;
import com.zorrodev.bpm.engine.service.db.ProcessDefinitionDbOperations;
import com.zorrodev.bpm.engine.service.db.ServiceTaskDbOperations;
import com.zorrodev.bpm.engine.service.db.UserTaskDbOperations;
import com.zorrodev.bpm.engine.service.db.VariableDbOperations;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-1a: characterization tests for DBServiceImpl (Phase 0 safe-net).
 * FIXES CURRENT BEHAVIOUR AS-IS — does NOT change production code.
 * Unit style (Mockito), mirroring the existing DBServiceImplTest. Covers every
 * public method of DBServiceImpl not already exercised there. The 4 CAS methods'
 * concurrent-winner semantics are characterized by DBServiceImplCasRaceTest (PG).
 */
@ExtendWith(MockitoExtension.class)
class DBServiceImplCharacterizationTest {

    @Mock private ProcessDefinitionDbOperations processDefinitionDbOperations;
    @Mock private ParallelGatewayDbOperations parallelGatewayDbOperations;
    @Mock private com.zorrodev.bpm.engine.service.db.ProcessInstanceDbOperations processInstanceDbOperations;
    @Mock private ServiceTaskDbOperations serviceTaskDbOperations;
    @Mock private com.zorrodev.bpm.engine.service.db.TokenDbOperations tokenDbOperations;
    @Mock private UserTaskDbOperations userTaskDbOperations;
    @Mock private IncidentDbOperations incidentDbOperations;
    @Mock private MessageSubscriptionDbOperations messageSubscriptionDbOperations;
    @Mock private VariableDbOperations variableDbOperations;
    @Mock private TimerDbOperations timerDbOperations;
    @Mock private SignalSubscriptionDbOperations signalSubscriptionDbOperations;
    @Mock private ActivityDbOperations activityDbOperations;

    @InjectMocks
    private DBServiceImpl dbService;

    // ─── Activity / Token lifecycle ─────────────────────────────
    // WO-DEBT-1n: moved to ActivityDbOperationsImpl — DBServiceImpl now only delegates.

    @Test
    void createActivity_byElement_delegates() {
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
    void errorActivity_delegates() {
        UUID id = UUID.randomUUID();
        dbService.errorActivity(id);
        verify(activityDbOperations).errorActivity(id);
    }

    @Test
    void cancelActivity_delegates() {
        UUID id = UUID.randomUUID();
        dbService.cancelActivity(id);
        verify(activityDbOperations).cancelActivity(id);
    }

    @Test
    void cancelActiveActivities_delegates() {
        UUID pi = UUID.randomUUID();
        dbService.cancelActiveActivities(pi);
        verify(activityDbOperations).cancelActiveActivities(pi);
    }

    @Test
    void cancelActiveActivitiesForToken_delegates() {
        UUID token = UUID.randomUUID();
        dbService.cancelActiveActivitiesForToken(token);
        verify(activityDbOperations).cancelActiveActivitiesForToken(token);
    }

    @Test
    void getActiveActivities_delegates() {
        UUID pi = UUID.randomUUID();
        List<Activity> expected = List.of(new Activity());
        when(activityDbOperations.getActiveActivities(pi)).thenReturn(expected);
        assertThat(dbService.getActiveActivities(pi)).isEqualTo(expected);
        verify(activityDbOperations).getActiveActivities(pi);
    }

    @Test
    void hasActiveActivityOnTokenAndElement_delegates() {
        UUID token = UUID.randomUUID();
        when(activityDbOperations.hasActiveActivityOnTokenAndElement(token, "x")).thenReturn(true);
        assertThat(dbService.hasActiveActivityOnTokenAndElement(token, "x")).isTrue();
        verify(activityDbOperations).hasActiveActivityOnTokenAndElement(token, "x");
    }

    @Test
    void hasActiveActivityOnTokenAndElement_delegatesFalse() {
        UUID token = UUID.randomUUID();
        when(activityDbOperations.hasActiveActivityOnTokenAndElement(token, "x")).thenReturn(false);
        assertThat(dbService.hasActiveActivityOnTokenAndElement(token, "x")).isFalse();
        verify(activityDbOperations).hasActiveActivityOnTokenAndElement(token, "x");
    }

    @Test
    void getCompletedActivities_delegates() {
        UUID pi = UUID.randomUUID();
        List<Activity> expected = List.of(new Activity());
        when(activityDbOperations.getCompletedActivities(pi)).thenReturn(expected);
        assertThat(dbService.getCompletedActivities(pi)).isEqualTo(expected);
        verify(activityDbOperations).getCompletedActivities(pi);
    }

    @Test
    void lockProcessInstance_locksForUpdate() {
        UUID pi = UUID.randomUUID();
        dbService.lockProcessInstance(pi);
        verify(processInstanceDbOperations).lockProcessInstance(pi);
    }

    @Test
    void lockProcessInstance_throwsWhenMissing() {
        UUID pi = UUID.randomUUID();
        doThrow(new NoSuchElementException()).when(processInstanceDbOperations).lockProcessInstance(pi);
        assertThatThrownBy(() -> dbService.lockProcessInstance(pi)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void deleteToken_deletesById() {
        UUID id = UUID.randomUUID();
        dbService.deleteToken(id);
        verify(tokenDbOperations).deleteToken(id);
    }

    @Test
    void createToken_withScope_persistsScopeActivityId() {
        UUID parent = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        com.zorrodev.bpm.engine.dto.Token expected = new com.zorrodev.bpm.engine.dto.Token();
        expected.setId(UUID.randomUUID());
        when(tokenDbOperations.createToken(parent, scope)).thenReturn(expected);
        com.zorrodev.bpm.engine.dto.Token result = dbService.createToken(parent, scope);
        assertThat(result).isEqualTo(expected);
        verify(tokenDbOperations).createToken(parent, scope);
    }

    // WO-DEBT-1d: setPendingBranches/decrementPendingBranches moved to ParallelGatewayDbOperationsImpl
    // (real behaviour characterized in ParallelGatewayDbOperationsImplTest) — DBServiceImpl now only
    // delegates, so these two just check the delegation, not the pendingBranches arithmetic itself.
    @Test
    void setPendingBranches_delegates() {
        UUID tokenId = UUID.randomUUID();
        dbService.setPendingBranches(tokenId, 3);
        verify(parallelGatewayDbOperations).setPendingBranches(tokenId, 3);
    }

    @Test
    void decrementPendingBranches_delegates() {
        UUID tokenId = UUID.randomUUID();
        when(parallelGatewayDbOperations.decrementPendingBranches(tokenId)).thenReturn(2);
        assertThat(dbService.decrementPendingBranches(tokenId)).isEqualTo(2);
        verify(parallelGatewayDbOperations).decrementPendingBranches(tokenId);
    }

    // ─── Incidents ─────────────────────────────────────────────
    // WO-DEBT-1j: moved to IncidentDbOperationsImpl — DBServiceImpl now only delegates.

    @Test
    void findOpenIncidentsByActivityIds_delegates() {
        UUID aId = UUID.randomUUID();
        Incident expected = new Incident(); expected.setId(UUID.randomUUID()); expected.setActivityId(aId);
        when(incidentDbOperations.findOpenIncidentsByActivityIds(List.of(aId))).thenReturn(List.of(expected));
        List<Incident> result = dbService.findOpenIncidentsByActivityIds(List.of(aId));
        assertThat(result).hasSize(1);
        verify(incidentDbOperations).findOpenIncidentsByActivityIds(List.of(aId));
    }

    @Test
    void completeIncidentsByActivityIds_delegates() {
        UUID aId = UUID.randomUUID();
        dbService.completeIncidentsByActivityIds(List.of(aId));
        verify(incidentDbOperations).completeIncidentsByActivityIds(List.of(aId));
    }

    // ─── Variables (scope overloads + delete) ──────────────────
    // WO-DEBT-1g: moved to VariableDbOperationsImpl (real behaviour characterized in
    // VariableDbOperationsImplTest) — DBServiceImpl now only delegates.

    @Test
    void getVariables_withScopeId_delegates() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        List<ProcessVariable> expected = List.of(newVar("a", "1", ProcessVariableType.STRING));
        when(variableDbOperations.getVariables(pi, scope)).thenReturn(expected);
        assertThat(dbService.getVariables(pi, scope)).isEqualTo(expected);
        verify(variableDbOperations).getVariables(pi, scope);
    }

    @Test
    void setVariables_withScopeId_delegates() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        List<ProcessVariable> vars = List.of(newVar("a", "1", ProcessVariableType.LONG));
        dbService.setVariables(pi, scope, vars);
        verify(variableDbOperations).setVariables(pi, scope, vars);
    }

    @Test
    void deleteVariables_delegates() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        dbService.deleteVariables(pi, scope);
        verify(variableDbOperations).deleteVariables(pi, scope);
    }

    // ─── Message subscriptions (overloads + event subprocess + byKey) ─
    // WO-DEBT-1k: moved to MessageSubscriptionDbOperationsImpl — DBServiceImpl now only delegates.

    @Test
    void createMessageSubscription_withBoundary_delegates() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID(); UUID expected = UUID.randomUUID();
        when(messageSubscriptionDbOperations.createMessageSubscription(pi, act, "m", "boundary")).thenReturn(expected);
        assertThat(dbService.createMessageSubscription(pi, act, "m", "boundary")).isEqualTo(expected);
        verify(messageSubscriptionDbOperations).createMessageSubscription(pi, act, "m", "boundary");
    }

    @Test
    void createMessageSubscription_withCorrelationKey_delegates() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID(); UUID expected = UUID.randomUUID();
        when(messageSubscriptionDbOperations.createMessageSubscription(pi, act, "m", "boundary", "ck")).thenReturn(expected);
        assertThat(dbService.createMessageSubscription(pi, act, "m", "boundary", "ck")).isEqualTo(expected);
        verify(messageSubscriptionDbOperations).createMessageSubscription(pi, act, "m", "boundary", "ck");
    }

    @Test
    void createEventSubprocessMessageSubscription_delegates() {
        UUID pi = UUID.randomUUID(); UUID expected = UUID.randomUUID();
        when(messageSubscriptionDbOperations.createEventSubprocessMessageSubscription(pi, "m", "esp")).thenReturn(expected);
        assertThat(dbService.createEventSubprocessMessageSubscription(pi, "m", "esp")).isEqualTo(expected);
        verify(messageSubscriptionDbOperations).createEventSubprocessMessageSubscription(pi, "m", "esp");
    }

    @Test
    void findMessageSubscriptionsByKey_delegates() {
        MessageSubscription e = new MessageSubscription(); e.setId(UUID.randomUUID());
        when(messageSubscriptionDbOperations.findMessageSubscriptionsByKey("m", "ck")).thenReturn(List.of(e));
        List<MessageSubscription> result = dbService.findMessageSubscriptionsByKey("m", "ck");
        assertThat(result).hasSize(1);
        verify(messageSubscriptionDbOperations).findMessageSubscriptionsByKey("m", "ck");
    }

    @Test
    void createMessageStartSubscription_delegates() {
        UUID pd = UUID.randomUUID();
        dbService.createMessageStartSubscription("key", pd, "el", "m");
        verify(messageSubscriptionDbOperations).createMessageStartSubscription("key", pd, "el", "m");
    }

    @Test
    void deleteMessageStartSubscriptionsByKey_delegates() {
        dbService.deleteMessageStartSubscriptionsByKey("key");
        verify(messageSubscriptionDbOperations).deleteMessageStartSubscriptionsByKey("key");
    }

    @Test
    void findMessageStartSubscriptions_delegates() {
        List<com.zorrodev.bpm.engine.dto.MessageStartSubscription> expected = List.of(new com.zorrodev.bpm.engine.dto.MessageStartSubscription());
        when(messageSubscriptionDbOperations.findMessageStartSubscriptions("m")).thenReturn(expected);
        assertThat(dbService.findMessageStartSubscriptions("m")).isEqualTo(expected);
        verify(messageSubscriptionDbOperations).findMessageStartSubscriptions("m");
    }

    @Test
    void deleteMessageSubscriptionsByProcessInstanceId_delegates() {
        UUID pi = UUID.randomUUID();
        dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
        verify(messageSubscriptionDbOperations).deleteMessageSubscriptionsByProcessInstanceId(pi);
    }

    @Test
    void findMessageSubscriptions_delegates() {
        List<MessageSubscription> expected = List.of(new MessageSubscription());
        UUID pi = UUID.randomUUID();
        when(messageSubscriptionDbOperations.findMessageSubscriptions("m", pi)).thenReturn(expected);
        assertThat(dbService.findMessageSubscriptions("m", pi)).isEqualTo(expected);
        verify(messageSubscriptionDbOperations).findMessageSubscriptions("m", pi);
    }

    @Test
    void createMessageSubscription_delegates() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID(); UUID expected = UUID.randomUUID();
        when(messageSubscriptionDbOperations.createMessageSubscription(pi, act, "m")).thenReturn(expected);
        assertThat(dbService.createMessageSubscription(pi, act, "m")).isEqualTo(expected);
        verify(messageSubscriptionDbOperations).createMessageSubscription(pi, act, "m");
    }

    @Test
    void consumeMessageSubscription_delegates() {
        UUID id = UUID.randomUUID();
        when(messageSubscriptionDbOperations.consumeMessageSubscription(id)).thenReturn(true);
        assertThat(dbService.consumeMessageSubscription(id)).isTrue();
        verify(messageSubscriptionDbOperations).consumeMessageSubscription(id);
    }

    // ─── Signal subscriptions ──────────────────────────────────
    // WO-DEBT-1l: moved to SignalSubscriptionDbOperationsImpl — DBServiceImpl now only delegates.

    @Test
    void createSignalSubscription_withBoundary_delegates() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID(); UUID expected = UUID.randomUUID();
        when(signalSubscriptionDbOperations.createSignalSubscription(pi, act, "s", "boundary")).thenReturn(expected);
        assertThat(dbService.createSignalSubscription(pi, act, "s", "boundary")).isEqualTo(expected);
        verify(signalSubscriptionDbOperations).createSignalSubscription(pi, act, "s", "boundary");
    }

    @Test
    void createSignalSubscription_3arg_delegates() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID(); UUID expected = UUID.randomUUID();
        when(signalSubscriptionDbOperations.createSignalSubscription(pi, act, "s")).thenReturn(expected);
        assertThat(dbService.createSignalSubscription(pi, act, "s")).isEqualTo(expected);
        verify(signalSubscriptionDbOperations).createSignalSubscription(pi, act, "s");
    }

    @Test
    void createEventSubprocessSignalSubscription_delegates() {
        UUID pi = UUID.randomUUID(); UUID expected = UUID.randomUUID();
        when(signalSubscriptionDbOperations.createEventSubprocessSignalSubscription(pi, "s", "esp")).thenReturn(expected);
        assertThat(dbService.createEventSubprocessSignalSubscription(pi, "s", "esp")).isEqualTo(expected);
        verify(signalSubscriptionDbOperations).createEventSubprocessSignalSubscription(pi, "s", "esp");
    }

    @Test
    void findSignalSubscriptions_delegates() {
        List<com.zorrodev.bpm.engine.dto.SignalSubscription> expected = List.of(new com.zorrodev.bpm.engine.dto.SignalSubscription());
        when(signalSubscriptionDbOperations.findSignalSubscriptions("s")).thenReturn(expected);
        assertThat(dbService.findSignalSubscriptions("s")).isEqualTo(expected);
        verify(signalSubscriptionDbOperations).findSignalSubscriptions("s");
    }

    @Test
    void createSignalStartSubscription_delegates() {
        UUID pd = UUID.randomUUID();
        dbService.createSignalStartSubscription("key", pd, "el", "s");
        verify(signalSubscriptionDbOperations).createSignalStartSubscription("key", pd, "el", "s");
    }

    @Test
    void deleteSignalStartSubscriptionsByKey_delegates() {
        dbService.deleteSignalStartSubscriptionsByKey("key");
        verify(signalSubscriptionDbOperations).deleteSignalStartSubscriptionsByKey("key");
    }

    @Test
    void findSignalStartSubscriptions_delegates() {
        List<com.zorrodev.bpm.engine.dto.SignalStartSubscription> expected = List.of(new com.zorrodev.bpm.engine.dto.SignalStartSubscription());
        when(signalSubscriptionDbOperations.findSignalStartSubscriptions("s")).thenReturn(expected);
        List<?> result = dbService.findSignalStartSubscriptions("s");
        assertThat(result).isEqualTo(expected);
        verify(signalSubscriptionDbOperations).findSignalStartSubscriptions("s");
    }

    @Test
    void consumeSignalSubscription_delegates() {
        UUID id = UUID.randomUUID();
        when(signalSubscriptionDbOperations.consumeSignalSubscription(id)).thenReturn(true);
        assertThat(dbService.consumeSignalSubscription(id)).isTrue();
        verify(signalSubscriptionDbOperations).consumeSignalSubscription(id);
    }

    // ─── Timers (uncovered) ─────────────────────────────────────

    // WO-DEBT-1m: moved to TimerDbOperationsImpl — DBServiceImpl now only delegates.
    @Test
    void createEventSubprocessTimerJob_delegates() {
        UUID pi = UUID.randomUUID(); Instant due = Instant.now().plusSeconds(60); UUID expected = UUID.randomUUID();
        when(timerDbOperations.createEventSubprocessTimerJob(pi, due, "esp")).thenReturn(expected);
        assertThat(dbService.createEventSubprocessTimerJob(pi, due, "esp")).isEqualTo(expected);
        verify(timerDbOperations).createEventSubprocessTimerJob(pi, due, "esp");
    }

    @Test
    void createTimerJob_delegates() {
        UUID act = UUID.randomUUID(); Instant due = Instant.now().plusSeconds(60); UUID expected = UUID.randomUUID();
        when(timerDbOperations.createTimerJob(eq(act), any(Instant.class), any(), any(), any(), any())).thenReturn(expected);
        assertThat(dbService.createTimerJob(act, due, null, null, null, null)).isEqualTo(expected);
        verify(timerDbOperations).createTimerJob(eq(act), any(Instant.class), any(), any(), any(), any());
    }

    @Test
    void findDueTimerJobs_delegates() {
        Instant now = Instant.now();
        List<TimerJob> expected = List.of(new TimerJob());
        when(timerDbOperations.findDueTimerJobs(now)).thenReturn(expected);
        assertThat(dbService.findDueTimerJobs(now)).isEqualTo(expected);
        verify(timerDbOperations).findDueTimerJobs(now);
    }

    @Test
    void findDueTimerJobsLocked_delegates() {
        Instant now = Instant.now();
        List<TimerJob> expected = List.of(new TimerJob());
        when(timerDbOperations.findDueTimerJobsLocked(now, 10)).thenReturn(expected);
        assertThat(dbService.findDueTimerJobsLocked(now, 10)).isEqualTo(expected);
        verify(timerDbOperations).findDueTimerJobsLocked(now, 10);
    }

    @Test
    void recordTimerJobError_delegates() {
        UUID id = UUID.randomUUID();
        dbService.recordTimerJobError(id, "boom");
        verify(timerDbOperations).recordTimerJobError(id, "boom");
    }

    @Test
    void recordTimerStartJobError_delegates() {
        UUID id = UUID.randomUUID();
        dbService.recordTimerStartJobError(id, "boom");
        verify(timerDbOperations).recordTimerStartJobError(id, "boom");
    }

    @Test
    void deleteTimerJobsByProcessInstanceId_delegates() {
        UUID pi = UUID.randomUUID();
        dbService.deleteTimerJobsByProcessInstanceId(pi);
        verify(timerDbOperations).deleteTimerJobsByProcessInstanceId(pi);
    }

    @Test
    void createTimerStartJob_4arg_delegates() {
        UUID pd = UUID.randomUUID(); Instant due = Instant.now().plusSeconds(60);
        dbService.createTimerStartJob("key", pd, "el", due);
        verify(timerDbOperations).createTimerStartJob("key", pd, "el", due);
    }

    @Test
    void createTimerStartJob_5arg_delegates() {
        UUID pd = UUID.randomUUID(); Instant due = Instant.now().plusSeconds(60);
        dbService.createTimerStartJob("key", pd, "el", due, 5);
        verify(timerDbOperations).createTimerStartJob("key", pd, "el", due, 5);
    }

    @Test
    void deleteTimerStartJobsByKey_delegates() {
        dbService.deleteTimerStartJobsByKey("key");
        verify(timerDbOperations).deleteTimerStartJobsByKey("key");
    }

    @Test
    void findDueTimerStartJobs_delegates() {
        Instant now = Instant.now();
        List<com.zorrodev.bpm.engine.dto.TimerStartJob> expected = List.of(new com.zorrodev.bpm.engine.dto.TimerStartJob());
        when(timerDbOperations.findDueTimerStartJobs(now)).thenReturn(expected);
        assertThat(dbService.findDueTimerStartJobs(now)).isEqualTo(expected);
        verify(timerDbOperations).findDueTimerStartJobs(now);
    }

    @Test
    void findDueTimerStartJobsLocked_delegates() {
        Instant now = Instant.now();
        List<com.zorrodev.bpm.engine.dto.TimerStartJob> expected = List.of(new com.zorrodev.bpm.engine.dto.TimerStartJob());
        when(timerDbOperations.findDueTimerStartJobsLocked(now, 10)).thenReturn(expected);
        assertThat(dbService.findDueTimerStartJobsLocked(now, 10)).isEqualTo(expected);
        verify(timerDbOperations).findDueTimerStartJobsLocked(now, 10);
    }

    // ─── Service tasks (retries) ───────────────────────────────
    // WO-DEBT-1h: moved to ServiceTaskDbOperationsImpl (real behaviour characterized in
    // ServiceTaskDbOperationsImplTest) — DBServiceImpl now only delegates.

    @Test
    void decrementServiceTaskRetries_delegates() {
        UUID id = UUID.randomUUID();
        when(serviceTaskDbOperations.decrementServiceTaskRetries(id)).thenReturn(2);
        assertThat(dbService.decrementServiceTaskRetries(id)).isEqualTo(2);
        verify(serviceTaskDbOperations).decrementServiceTaskRetries(id);
    }

    @Test
    void setServiceTaskRetries_delegates() {
        UUID id = UUID.randomUUID();
        dbService.setServiceTaskRetries(id, 5);
        verify(serviceTaskDbOperations).setServiceTaskRetries(id, 5);
    }

    // ─── User tasks (claim/unclaim/assign) ─────────────────────
    // WO-DEBT-1i: moved to UserTaskDbOperationsImpl (real behaviour characterized in
    // UserTaskDbOperationsImplTest) — DBServiceImpl now only delegates.

    @Test
    void createUserTask_delegates() {
        UUID activityId = UUID.randomUUID();
        dbService.createUserTask(activityId, "ivanov", "managers", "form1", null, null, null, null, null);
        verify(userTaskDbOperations).createUserTask(activityId, "ivanov", "managers", "form1", null, null, null, null, null);
    }

    @Test
    void completeUserTask_delegates() {
        UUID id = UUID.randomUUID();
        dbService.completeUserTask(id);
        verify(userTaskDbOperations).completeUserTask(id);
    }

    @Test
    void claimUserTask_delegates() {
        UUID id = UUID.randomUUID();
        dbService.claimUserTask(id, "ivanov");
        verify(userTaskDbOperations).claimUserTask(id, "ivanov");
    }

    @Test
    void unclaimUserTask_delegates() {
        UUID id = UUID.randomUUID();
        dbService.unclaimUserTask(id);
        verify(userTaskDbOperations).unclaimUserTask(id);
    }

    @Test
    void assignUserTask_delegates() {
        UUID id = UUID.randomUUID();
        dbService.assignUserTask(id, "ivanov");
        verify(userTaskDbOperations).assignUserTask(id, "ivanov");
    }

    // ─── Process instance ──────────────────────────────────────

    @Test
    void cancelProcessInstance_cancelsAndEmits() {
        UUID id = UUID.randomUUID();
        dbService.cancelProcessInstance(id);
        verify(processInstanceDbOperations).cancelProcessInstance(id);
    }

    // ─── Gateway state ────────────────────────────────────────
    // WO-DEBT-1d: all 5 moved to ParallelGatewayDbOperationsImpl (real behaviour characterized in
    // ParallelGatewayDbOperationsImplTest) — DBServiceImpl now only delegates.

    @Test
    void recordParallelGatewayArrival_delegates() {
        UUID pi = UUID.randomUUID();
        dbService.recordParallelGatewayArrival(pi, "g", "f");
        verify(parallelGatewayDbOperations).recordParallelGatewayArrival(pi, "g", "f");
    }

    @Test
    void getParallelGatewayArrivedFlows_delegates() {
        UUID pi = UUID.randomUUID();
        when(parallelGatewayDbOperations.getParallelGatewayArrivedFlows(pi, "g")).thenReturn(Set.of("f1", "f2"));
        Set<String> result = dbService.getParallelGatewayArrivedFlows(pi, "g");
        assertThat(result).containsExactlyInAnyOrder("f1", "f2");
    }

    @Test
    void clearParallelGatewayArrivals_delegates() {
        UUID pi = UUID.randomUUID();
        dbService.clearParallelGatewayArrivals(pi, "g");
        verify(parallelGatewayDbOperations).clearParallelGatewayArrivals(pi, "g");
    }

    @Test
    void recordInclusiveExpected_delegates() {
        UUID pi = UUID.randomUUID();
        dbService.recordInclusiveExpected(pi, "g", 3);
        verify(parallelGatewayDbOperations).recordInclusiveExpected(pi, "g", 3);
    }

    @Test
    void getInclusiveExpected_delegates() {
        UUID pi = UUID.randomUUID();
        when(parallelGatewayDbOperations.getInclusiveExpected(pi, "g")).thenReturn(3);
        assertThat(dbService.getInclusiveExpected(pi, "g")).isEqualTo(3);
    }

    // ─── CAS: claim* single-call characterization (race is PG IT) ──
    // WO-DEBT-1m: moved to TimerDbOperationsImpl — DBServiceImpl now only delegates.

    @Test
    void claimTimerJob_delegates() {
        UUID id = UUID.randomUUID();
        when(timerDbOperations.claimTimerJob(id)).thenReturn(true);
        assertThat(dbService.claimTimerJob(id)).isTrue();
        verify(timerDbOperations).claimTimerJob(id);
    }

    @Test
    void claimTimerJob_delegatesFalse() {
        UUID id = UUID.randomUUID();
        when(timerDbOperations.claimTimerJob(id)).thenReturn(false);
        assertThat(dbService.claimTimerJob(id)).isFalse();
        verify(timerDbOperations).claimTimerJob(id);
    }

    @Test
    void claimTimerStartJob_delegates() {
        UUID id = UUID.randomUUID();
        when(timerDbOperations.claimTimerStartJob(id)).thenReturn(true);
        assertThat(dbService.claimTimerStartJob(id)).isTrue();
        verify(timerDbOperations).claimTimerStartJob(id);
    }

    @Test
    void claimTimerStartJob_delegatesFalse() {
        UUID id = UUID.randomUUID();
        when(timerDbOperations.claimTimerStartJob(id)).thenReturn(false);
        assertThat(dbService.claimTimerStartJob(id)).isFalse();
        verify(timerDbOperations).claimTimerStartJob(id);
    }

    private static ProcessVariable newVar(String name, String value, ProcessVariableType type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }
}
