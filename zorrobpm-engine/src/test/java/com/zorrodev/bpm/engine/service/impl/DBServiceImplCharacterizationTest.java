package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.SignalStartSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.SignalStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
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
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private UserTaskDbOperations userTaskDbOperations;
    @Mock private IncidentDbOperations incidentDbOperations;
    @Mock private MessageSubscriptionDbOperations messageSubscriptionDbOperations;
    @Mock private VariableDbOperations variableDbOperations;
    @Mock private TimerJobRepository timerJobRepository;
    @Mock private SignalSubscriptionRepository signalSubscriptionRepository;
    @Mock private SignalStartSubscriptionRepository signalStartSubscriptionRepository;
    @Mock private TimerStartJobRepository timerStartJobRepository;
    @Mock private ProcessInstanceMapper processInstanceMapper;
    @Mock private DomainEventEmitter domainEventEmitter;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DBServiceImpl dbService;

    // ─── Activity / Token lifecycle ─────────────────────────────

    @Test
    void errorActivity_marksErrorWithoutCompletedAt() {
        UUID id = UUID.randomUUID();
        dbService.errorActivity(id);
        verify(activityRepository).setStatusAndCompletedAt(eq(id), eq(ActivityStatus.ERROR), isNull());
    }

    @Test
    void cancelActivity_marksCancelled() {
        UUID id = UUID.randomUUID();
        dbService.cancelActivity(id);
        verify(activityRepository).setStatusAndCompletedAt(eq(id), eq(ActivityStatus.CANCELLED), any(Instant.class));
    }

    @Test
    void cancelActiveActivities_cancelsEachActive() {
        UUID pi = UUID.randomUUID();
        ActivityEntity a1 = new ActivityEntity(); a1.setId(UUID.randomUUID());
        ActivityEntity a2 = new ActivityEntity(); a2.setId(UUID.randomUUID());
        when(activityRepository.findByProcessInstanceIdAndStatusIn(eq(pi), anyCollection())).thenReturn(List.of(a1, a2));

        dbService.cancelActiveActivities(pi);

        verify(activityRepository).setStatusAndCompletedAt(eq(a1.getId()), eq(ActivityStatus.CANCELLED), any(Instant.class));
        verify(activityRepository).setStatusAndCompletedAt(eq(a2.getId()), eq(ActivityStatus.CANCELLED), any(Instant.class));
    }

    @Test
    void cancelActiveActivitiesForToken_cancelsEachActive() {
        UUID token = UUID.randomUUID();
        ActivityEntity a1 = new ActivityEntity(); a1.setId(UUID.randomUUID());
        when(activityRepository.findByTokenAndStatusIn(eq(token), anyCollection())).thenReturn(List.of(a1));

        dbService.cancelActiveActivitiesForToken(token);

        verify(activityRepository).setStatusAndCompletedAt(eq(a1.getId()), eq(ActivityStatus.CANCELLED), any(Instant.class));
    }

    @Test
    void getActiveActivities_returnsMapped() {
        UUID pi = UUID.randomUUID();
        ActivityEntity e = new ActivityEntity();
        e.setId(UUID.randomUUID());
        e.setBpmnElementId("x");
        e.setType(BpmnElementType.USER_TASK);
        e.setStatus(ActivityStatus.CREATED);
        e.setProcessInstanceId(pi);
        e.setToken(UUID.randomUUID());
        when(activityRepository.findByProcessInstanceIdAndStatusIn(eq(pi), anyCollection())).thenReturn(List.of(e));

        List<Activity> result = dbService.getActiveActivities(pi);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBpmnElementId()).isEqualTo("x");
    }

    @Test
    void hasActiveActivityOnTokenAndElement_trueWhenPresent() {
        UUID token = UUID.randomUUID();
        ActivityEntity e = new ActivityEntity(); e.setId(UUID.randomUUID());
        when(activityRepository.findByTokenAndBpmnElementIdAndStatusIn(eq(token), eq("x"), anyCollection())).thenReturn(List.of(e));
        assertThat(dbService.hasActiveActivityOnTokenAndElement(token, "x")).isTrue();
    }

    @Test
    void hasActiveActivityOnTokenAndElement_falseWhenAbsent() {
        UUID token = UUID.randomUUID();
        when(activityRepository.findByTokenAndBpmnElementIdAndStatusIn(eq(token), eq("x"), anyCollection())).thenReturn(List.of());
        assertThat(dbService.hasActiveActivityOnTokenAndElement(token, "x")).isFalse();
    }

    @Test
    void getCompletedActivities_returnsMapped() {
        UUID pi = UUID.randomUUID();
        ActivityEntity e = new ActivityEntity(); e.setId(UUID.randomUUID()); e.setBpmnElementId("y");
        when(activityRepository.findByProcessInstanceIdAndStatusIn(eq(pi), anyCollection())).thenReturn(List.of(e));
        List<Activity> result = dbService.getCompletedActivities(pi);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBpmnElementId()).isEqualTo("y");
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

    @Test
    void createSignalSubscription_withBoundary_persists() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID();
        UUID id = dbService.createSignalSubscription(pi, act, "s", "boundary");
        assertThat(id).isNotNull();
        ArgumentCaptor<SignalSubscriptionEntity> captor = ArgumentCaptor.forClass(SignalSubscriptionEntity.class);
        verify(signalSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getBoundaryElementId()).isEqualTo("boundary");
    }

    @Test
    void createSignalSubscription_3arg_persistsWithNullBoundary() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID();
        UUID id = dbService.createSignalSubscription(pi, act, "s");
        assertThat(id).isNotNull();
        ArgumentCaptor<SignalSubscriptionEntity> captor = ArgumentCaptor.forClass(SignalSubscriptionEntity.class);
        verify(signalSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getBoundaryElementId()).isNull();
    }

    @Test
    void createEventSubprocessSignalSubscription_persistsWithNullActivity() {
        UUID pi = UUID.randomUUID();
        UUID id = dbService.createEventSubprocessSignalSubscription(pi, "s", "esp");
        assertThat(id).isNotNull();
        ArgumentCaptor<SignalSubscriptionEntity> captor = ArgumentCaptor.forClass(SignalSubscriptionEntity.class);
        verify(signalSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getActivityId()).isNull();
        assertThat(captor.getValue().getEventSubprocessId()).isEqualTo("esp");
    }

    @Test
    void findSignalSubscriptions_returnsMapped() {
        SignalSubscriptionEntity e = new SignalSubscriptionEntity(); e.setId(UUID.randomUUID()); e.setProcessInstanceId(UUID.randomUUID()); e.setActivityId(UUID.randomUUID()); e.setSignalName("s");
        when(signalSubscriptionRepository.findByConsumedFalseAndSignalName("s")).thenReturn(List.of(e));
        List<?> result = dbService.findSignalSubscriptions("s");
        assertThat(result).hasSize(1);
    }

    @Test
    void createSignalStartSubscription_persists() {
        UUID pd = UUID.randomUUID();
        dbService.createSignalStartSubscription("key", pd, "el", "s");
        ArgumentCaptor<SignalStartSubscriptionEntity> captor = ArgumentCaptor.forClass(SignalStartSubscriptionEntity.class);
        verify(signalStartSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getSignalName()).isEqualTo("s");
    }

    @Test
    void deleteSignalStartSubscriptionsByKey_callsRepo() {
        dbService.deleteSignalStartSubscriptionsByKey("key");
        verify(signalStartSubscriptionRepository).deleteByProcessKey("key");
    }

    @Test
    void findSignalStartSubscriptions_returnsMapped() {
        SignalStartSubscriptionEntity e = new SignalStartSubscriptionEntity(); e.setId(UUID.randomUUID()); e.setProcessKey("key"); e.setProcessDefinitionId(UUID.randomUUID()); e.setElementId("el"); e.setSignalName("s");
        when(signalStartSubscriptionRepository.findBySignalName("s")).thenReturn(List.of(e));
        List<?> result = dbService.findSignalStartSubscriptions("s");
        assertThat(result).hasSize(1);
    }

    // ─── Timers (uncovered) ─────────────────────────────────────

    @Test
    void createEventSubprocessTimerJob_persistsWithNullActivity() {
        UUID pi = UUID.randomUUID(); Instant due = Instant.now().plusSeconds(60);
        UUID id = dbService.createEventSubprocessTimerJob(pi, due, "esp");
        assertThat(id).isNotNull();
        ArgumentCaptor<TimerJobEntity> captor = ArgumentCaptor.forClass(TimerJobEntity.class);
        verify(timerJobRepository).save(captor.capture());
        assertThat(captor.getValue().getActivityId()).isNull();
        assertThat(captor.getValue().getEventSubprocessId()).isEqualTo("esp");
        assertThat(captor.getValue().isFired()).isFalse();
    }

    @Test
    void findDueTimerJobsLocked_returnsMapped() {
        Instant now = Instant.now();
        TimerJobEntity e = new TimerJobEntity(); e.setId(UUID.randomUUID()); e.setActivityId(UUID.randomUUID()); e.setDueAt(now);
        when(timerJobRepository.findDueLocked(now, 10)).thenReturn(List.of(e));
        List<TimerJob> result = dbService.findDueTimerJobsLocked(now, 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActivityId()).isEqualTo(e.getActivityId());
    }

    @Test
    void recordTimerJobError_callsRepo() {
        UUID id = UUID.randomUUID();
        dbService.recordTimerJobError(id, "boom");
        verify(timerJobRepository).recordTimerJobError(id, "boom");
    }

    @Test
    void recordTimerStartJobError_callsRepo() {
        UUID id = UUID.randomUUID();
        dbService.recordTimerStartJobError(id, "boom");
        verify(timerStartJobRepository).recordTimerStartJobError(id, "boom");
    }

    @Test
    void deleteTimerJobsByProcessInstanceId_callsRepo() {
        UUID pi = UUID.randomUUID();
        dbService.deleteTimerJobsByProcessInstanceId(pi);
        verify(timerJobRepository).deleteByProcessInstanceId(pi);
    }

    @Test
    void createTimerStartJob_4arg_persists() {
        UUID pd = UUID.randomUUID(); Instant due = Instant.now().plusSeconds(60);
        dbService.createTimerStartJob("key", pd, "el", due);
        ArgumentCaptor<TimerStartJobEntity> captor = ArgumentCaptor.forClass(TimerStartJobEntity.class);
        verify(timerStartJobRepository).save(captor.capture());
        assertThat(captor.getValue().getProcessKey()).isEqualTo("key");
        assertThat(captor.getValue().isFired()).isFalse();
    }

    @Test
    void createTimerStartJob_5arg_persistsRemainingCount() {
        UUID pd = UUID.randomUUID(); Instant due = Instant.now().plusSeconds(60);
        dbService.createTimerStartJob("key", pd, "el", due, 5);
        ArgumentCaptor<TimerStartJobEntity> captor = ArgumentCaptor.forClass(TimerStartJobEntity.class);
        verify(timerStartJobRepository).save(captor.capture());
        assertThat(captor.getValue().getRemainingCount()).isEqualTo(5);
    }

    @Test
    void deleteTimerStartJobsByKey_callsRepo() {
        dbService.deleteTimerStartJobsByKey("key");
        verify(timerStartJobRepository).deleteByProcessKey("key");
    }

    @Test
    void findDueTimerStartJobs_returnsMapped() {
        Instant now = Instant.now();
        TimerStartJobEntity e = new TimerStartJobEntity(); e.setId(UUID.randomUUID()); e.setProcessKey("key"); e.setElementId("el"); e.setDueAt(now);
        when(timerStartJobRepository.findByFiredFalseAndDueAtLessThanEqual(now)).thenReturn(List.of(e));
        List<?> result = dbService.findDueTimerStartJobs(now);
        assertThat(result).hasSize(1);
    }

    @Test
    void findDueTimerStartJobsLocked_returnsMapped() {
        Instant now = Instant.now();
        TimerStartJobEntity e = new TimerStartJobEntity(); e.setId(UUID.randomUUID()); e.setProcessKey("key"); e.setElementId("el"); e.setDueAt(now);
        when(timerStartJobRepository.findDueLocked(now, 10)).thenReturn(List.of(e));
        List<?> result = dbService.findDueTimerStartJobsLocked(now, 10);
        assertThat(result).hasSize(1);
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
        dbService.createUserTask(activityId, "ivanov", "managers", "form1");
        verify(userTaskDbOperations).createUserTask(activityId, "ivanov", "managers", "form1");
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

    @Test
    void claimTimerJob_returnsTrueWhenRowUnlocked() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.queryForList(
                eq("SELECT id FROM timer_jobs WHERE id = ? AND fired = false FOR UPDATE SKIP LOCKED"),
                eq(UUID.class), eq(id))).thenReturn(List.of(id));
        when(timerJobRepository.claimTimerJob(id)).thenReturn(1);
        assertThat(dbService.claimTimerJob(id)).isTrue();
    }

    @Test
    void claimTimerJob_returnsFalseWhenRowLockedOrFired() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq(id))).thenReturn(List.of());
        assertThat(dbService.claimTimerJob(id)).isFalse();
        verify(timerJobRepository, never()).claimTimerJob(any());
    }

    @Test
    void claimTimerStartJob_returnsTrueWhenRowUnlocked() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.queryForList(
                eq("SELECT id FROM timer_start_jobs WHERE id = ? AND fired = false FOR UPDATE SKIP LOCKED"),
                eq(UUID.class), eq(id))).thenReturn(List.of(id));
        when(timerStartJobRepository.claimTimerStartJob(id)).thenReturn(1);
        assertThat(dbService.claimTimerStartJob(id)).isTrue();
    }

    @Test
    void claimTimerStartJob_returnsFalseWhenRowLockedOrFired() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq(id))).thenReturn(List.of());
        assertThat(dbService.claimTimerStartJob(id)).isFalse();
        verify(timerStartJobRepository, never()).claimTimerStartJob(any());
    }

    private static ProcessVariable newVar(String name, String value, ProcessVariableType type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }
}
