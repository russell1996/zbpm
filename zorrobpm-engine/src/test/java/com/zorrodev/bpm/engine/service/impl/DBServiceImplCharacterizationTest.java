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
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.MessageStartSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.SignalStartSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.MessageStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.SignalStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.db.ParallelGatewayDbOperations;
import com.zorrodev.bpm.engine.service.db.ProcessDefinitionDbOperations;
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
    @Mock private com.zorrodev.bpm.engine.service.db.TokenDbOperations tokenDbOperations;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private UserTaskRepository userTaskRepository;
    @Mock private VariableRepository variableRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private TimerJobRepository timerJobRepository;
    @Mock private MessageSubscriptionRepository messageSubscriptionRepository;
    @Mock private SignalSubscriptionRepository signalSubscriptionRepository;
    @Mock private SignalStartSubscriptionRepository signalStartSubscriptionRepository;
    @Mock private MessageStartSubscriptionRepository messageStartSubscriptionRepository;
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

    @Test
    void findOpenIncidentsByActivityIds_maps() {
        UUID aId = UUID.randomUUID();
        IncidentEntity e = new IncidentEntity(); e.setId(UUID.randomUUID()); e.setActivityId(aId); e.setMessage("m");
        when(incidentRepository.findByActivityIdInAndCompletedAtIsNull(anyCollection())).thenReturn(List.of(e));
        List<Incident> result = dbService.findOpenIncidentsByActivityIds(List.of(aId));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActivityId()).isEqualTo(aId);
    }

    @Test
    void completeIncidentsByActivityIds_marksCompleted() {
        UUID aId = UUID.randomUUID();
        IncidentEntity e = new IncidentEntity(); e.setId(UUID.randomUUID()); e.setActivityId(aId);
        when(incidentRepository.findByActivityIdInAndCompletedAtIsNull(anyCollection())).thenReturn(List.of(e));
        dbService.completeIncidentsByActivityIds(List.of(aId));
        assertThat(e.getCompletedAt()).isNotNull();
        verify(incidentRepository).saveAll(anyCollection());
    }

    // ─── Variables (scope overloads + delete) ──────────────────

    @Test
    void getVariables_withScopeId_mergesRootAndLocal() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        ProcessVariableEntity root = new ProcessVariableEntity(); root.setName("a"); root.setTextValue("root"); root.setType(ProcessVariableType.STRING);
        ProcessVariableEntity local = new ProcessVariableEntity(); local.setName("a"); local.setTextValue("local"); local.setType(ProcessVariableType.STRING);
        when(variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi)).thenReturn(List.of(root));
        when(variableRepository.findByProcessInstanceIdAndScopeId(pi, scope)).thenReturn(List.of(local));
        List<ProcessVariable> result = dbService.getVariables(pi, scope);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("local"); // local shadows root
    }

    @Test
    void setVariables_withScopeId_savesOrUpdates() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        when(variableRepository.findByNameAndProcessInstanceIdAndScopeId("a", pi, scope)).thenReturn(Optional.empty());
        dbService.setVariables(pi, scope, List.of(newVar("a", "1", ProcessVariableType.LONG)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProcessVariableEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(variableRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getScopeId()).isEqualTo(scope);
    }

    @Test
    void deleteVariables_callsRepo() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        dbService.deleteVariables(pi, scope);
        verify(variableRepository).deleteByProcessInstanceIdAndScopeId(pi, scope);
    }

    // ─── Message subscriptions (overloads + event subprocess + byKey) ─

    @Test
    void createMessageSubscription_withBoundary_persists() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID();
        UUID id = dbService.createMessageSubscription(pi, act, "m", "boundary");
        assertThat(id).isNotNull();
        ArgumentCaptor<MessageSubscriptionEntity> captor = ArgumentCaptor.forClass(MessageSubscriptionEntity.class);
        verify(messageSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getBoundaryElementId()).isEqualTo("boundary");
        assertThat(captor.getValue().isConsumed()).isFalse();
    }

    @Test
    void createMessageSubscription_withCorrelationKey_persists() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID();
        UUID id = dbService.createMessageSubscription(pi, act, "m", "boundary", "ck");
        assertThat(id).isNotNull();
        ArgumentCaptor<MessageSubscriptionEntity> captor = ArgumentCaptor.forClass(MessageSubscriptionEntity.class);
        verify(messageSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getCorrelationKey()).isEqualTo("ck");
    }

    @Test
    void createEventSubprocessMessageSubscription_persistsWithNullActivity() {
        UUID pi = UUID.randomUUID();
        UUID id = dbService.createEventSubprocessMessageSubscription(pi, "m", "esp");
        assertThat(id).isNotNull();
        ArgumentCaptor<MessageSubscriptionEntity> captor = ArgumentCaptor.forClass(MessageSubscriptionEntity.class);
        verify(messageSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getActivityId()).isNull();
        assertThat(captor.getValue().getEventSubprocessId()).isEqualTo("esp");
    }

    @Test
    void findMessageSubscriptionsByKey_returnsMapped() {
        MessageSubscriptionEntity e = new MessageSubscriptionEntity(); e.setId(UUID.randomUUID()); e.setProcessInstanceId(UUID.randomUUID()); e.setActivityId(UUID.randomUUID()); e.setMessageName("m");
        when(messageSubscriptionRepository.findByConsumedFalseAndMessageNameAndCorrelationKey("m", "ck")).thenReturn(List.of(e));
        List<MessageSubscription> result = dbService.findMessageSubscriptionsByKey("m", "ck");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(e.getId());
    }

    @Test
    void createMessageStartSubscription_persists() {
        UUID pd = UUID.randomUUID();
        dbService.createMessageStartSubscription("key", pd, "el", "m");
        ArgumentCaptor<MessageStartSubscriptionEntity> captor = ArgumentCaptor.forClass(MessageStartSubscriptionEntity.class);
        verify(messageStartSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getProcessKey()).isEqualTo("key");
        assertThat(captor.getValue().getMessageName()).isEqualTo("m");
    }

    @Test
    void deleteMessageStartSubscriptionsByKey_callsRepo() {
        dbService.deleteMessageStartSubscriptionsByKey("key");
        verify(messageStartSubscriptionRepository).deleteByProcessKey("key");
    }

    @Test
    void findMessageStartSubscriptions_returnsMapped() {
        MessageStartSubscriptionEntity e = new MessageStartSubscriptionEntity(); e.setId(UUID.randomUUID()); e.setProcessKey("key"); e.setProcessDefinitionId(UUID.randomUUID()); e.setElementId("el"); e.setMessageName("m");
        when(messageStartSubscriptionRepository.findByMessageName("m")).thenReturn(List.of(e));
        List<?> result = dbService.findMessageStartSubscriptions("m");
        assertThat(result).hasSize(1);
    }

    @Test
    void deleteMessageSubscriptionsByProcessInstanceId_callsRepo() {
        UUID pi = UUID.randomUUID();
        dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
        verify(messageSubscriptionRepository).deleteByProcessInstanceId(pi);
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

    @Test
    void decrementServiceTaskRetries_returnsDecremented() {
        UUID id = UUID.randomUUID();
        ServiceTaskEntity e = new ServiceTaskEntity(); e.setId(id); e.setRetriesRemaining(3);
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(e));
        assertThat(dbService.decrementServiceTaskRetries(id)).isEqualTo(2);
        verify(serviceTaskRepository).save(e);
    }

    @Test
    void setServiceTaskRetries_saves() {
        UUID id = UUID.randomUUID();
        ServiceTaskEntity e = new ServiceTaskEntity(); e.setId(id); e.setRetriesRemaining(1);
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(e));
        dbService.setServiceTaskRetries(id, 5);
        assertThat(e.getRetriesRemaining()).isEqualTo(5);
        verify(serviceTaskRepository).save(e);
    }

    // ─── User tasks (claim/unclaim/assign) ─────────────────────

    @Test
    void claimUserTask_claimsWhenUnassigned() {
        UUID id = UUID.randomUUID(); UUID pi = UUID.randomUUID(); UUID pd = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setProcessInstanceId(pi); ut.setProcessDefinitionId(pd); ut.setBpmnElementId("ut"); ut.setAssignee(null); ut.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        when(userTaskRepository.claimAssignee(id, "ivanov")).thenReturn(1);
        dbService.claimUserTask(id, "ivanov");
        verify(domainEventEmitter).emitUserTaskAssigned(pi, pd, "ut", id, "ivanov");
    }

    @Test
    void claimUserTask_throwsWhenAlreadyCompleted() {
        UUID id = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setCompletedAt(Instant.now());
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        assertThatThrownBy(() -> dbService.claimUserTask(id, "ivanov")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void claimUserTask_throwsWhenAlreadyAssigned() {
        UUID id = UUID.randomUUID(); UUID pi = UUID.randomUUID(); UUID pd = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setProcessInstanceId(pi); ut.setProcessDefinitionId(pd); ut.setBpmnElementId("ut"); ut.setAssignee(null); ut.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        when(userTaskRepository.claimAssignee(id, "ivanov")).thenReturn(0);
        assertThatThrownBy(() -> dbService.claimUserTask(id, "ivanov")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unclaimUserTask_clearsAssignee() {
        UUID id = UUID.randomUUID(); UUID pi = UUID.randomUUID(); UUID pd = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setProcessInstanceId(pi); ut.setProcessDefinitionId(pd); ut.setBpmnElementId("ut"); ut.setAssignee("x"); ut.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        dbService.unclaimUserTask(id);
        verify(userTaskRepository).setAssignee(id, null);
        verify(domainEventEmitter).emitUserTaskUnassigned(pi, pd, "ut", id);
    }

    @Test
    void unclaimUserTask_throwsWhenCompleted() {
        UUID id = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setCompletedAt(Instant.now());
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        assertThatThrownBy(() -> dbService.unclaimUserTask(id)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assignUserTask_setsAssignee() {
        UUID id = UUID.randomUUID(); UUID pi = UUID.randomUUID(); UUID pd = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setProcessInstanceId(pi); ut.setProcessDefinitionId(pd); ut.setBpmnElementId("ut"); ut.setAssignee(null); ut.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        dbService.assignUserTask(id, "ivanov");
        verify(userTaskRepository).setAssignee(id, "ivanov");
        verify(domainEventEmitter).emitUserTaskAssigned(pi, pd, "ut", id, "ivanov");
    }

    @Test
    void assignUserTask_throwsWhenCompleted() {
        UUID id = UUID.randomUUID();
        UserTaskEntity ut = new UserTaskEntity(); ut.setId(id); ut.setCompletedAt(Instant.now());
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(ut));
        assertThatThrownBy(() -> dbService.assignUserTask(id, "ivanov")).isInstanceOf(IllegalStateException.class);
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
