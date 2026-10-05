package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompletionServiceTest {

    @Mock
    private DBService dbService;
    @Mock
    private BpmnService bpmnService;
    @Mock
    private ServiceTaskEnqueueService serviceTaskEnqueueService;
    @Mock
    private ElementSupport elementSupport;
    @Mock
    private MultiInstanceExecutor multiInstanceExecutor;
    @Mock
    private FlowNavigator flowNavigator;
    @Mock
    private EventTrigger eventTrigger;
    @Mock
    private ExecutionContext executionContext;

    // WO-C8-25: phase-first branches return empty/false by Mockito default —
    // existing tests exercise the pre-phase paths unchanged.
    @Mock
    private ElementListenerPhaseService elementListenerPhaseService;

    // WO-QW-2: BpmMetrics mock for the activityTransitionIgnored hook.
    @Mock
    private com.zorrodev.bpm.engine.metrics.BpmMetrics bpmMetrics;

    @InjectMocks
    private CompletionService completionService;

    @Test
    void failServiceTask_retriesExhausted_raisesIncident() {
        // Given
        UUID serviceTaskId = UUID.randomUUID();
        String errorMessage = "Worker failed";
        Integer retries = 0; // Exhausted

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setStatus(ActivityStatus.CREATED);
        activity.setProcessInstanceId(UUID.randomUUID());
        activity.setToken(UUID.randomUUID());
        activity.setBpmnElementId("serviceTask1");

        when(elementSupport.lockInstanceFirst(serviceTaskId)).thenReturn(activity);

        // When
        completionService.failServiceTask(serviceTaskId, errorMessage, retries);

        // Then
        verify(dbService).setServiceTaskRetries(serviceTaskId, 0);
        verify(dbService).errorActivity(serviceTaskId);
        verify(dbService).createIncident(eq(serviceTaskId), eq("Worker failed"));
        verify(serviceTaskEnqueueService, never()).enqueueAfterCommit(any());
    }

    @Test
    void failServiceTask_retriesLeft_redispaches() {
        // Given
        UUID serviceTaskId = UUID.randomUUID();
        String errorMessage = "Worker failed";
        Integer retries = 3; // Still have retries

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setStatus(ActivityStatus.CREATED);
        activity.setProcessInstanceId(UUID.randomUUID());
        activity.setToken(UUID.randomUUID());
        activity.setBpmnElementId("serviceTask1");

        when(elementSupport.lockInstanceFirst(serviceTaskId)).thenReturn(activity);

        // When
        completionService.failServiceTask(serviceTaskId, errorMessage, retries);

        // Then
        verify(dbService).setServiceTaskRetries(serviceTaskId, 3);
        verify(dbService, never()).errorActivity(any());
        verify(dbService, never()).createIncident(any(), any());
        verify(serviceTaskEnqueueService).enqueueAfterCommit(serviceTaskId);
    }

    @Test
    void failServiceTask_ignoresCompletedTask() {
        // Given
        UUID serviceTaskId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setStatus(ActivityStatus.COMPLETED); // Already completed

        when(elementSupport.lockInstanceFirst(serviceTaskId)).thenReturn(activity);

        // When
        completionService.failServiceTask(serviceTaskId, "error", 0);

        // Then
        verify(dbService, never()).setServiceTaskRetries(eq(serviceTaskId), org.mockito.ArgumentMatchers.anyInt());
        verify(dbService, never()).errorActivity(any());
        verify(dbService, never()).createIncident(any(), any());
    }

    /**
     * WO-QW-2 criterion 4 (behavioral POF anchor): a stale-status completion
     * drives the idempotent guard, which must call the metric hook exactly
     * once. POF-мутация — убрать вызов {@code bpmMetrics} в guard'е:
     * этот тест краснеет ({@code Wanted but not invoked}), остальные —
     * нет (хук только здесь).
     */
    @Test
    void staleStatusCompletion_incrementsIgnoredMetric() {
        UUID serviceTaskId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setStatus(ActivityStatus.COMPLETED);
        activity.setProcessInstanceId(UUID.randomUUID());
        activity.setToken(UUID.randomUUID());
        activity.setBpmnElementId("serviceTask1");

        when(elementSupport.lockInstanceFirst(serviceTaskId)).thenReturn(activity);

        // WO-C8-36 (F-5): phased-сообщение (dispatchPhase задан) — это stale_status.
        // Legacy-вариант того же игнора помечен отдельно, см. тест ниже.
        completionService.completeServiceTask(serviceTaskId, java.util.List.of(),
            com.zorrodev.bpm.exchange.ServiceTaskDispatchPhase.REAL, 0, "cid-1",
            org.mockito.Mockito.mock(com.zorrodev.bpm.engine.handler.TokenExecutor.class));

        verify(bpmMetrics).activityTransitionIgnored("stale_status");
    }

    /**
     * WO-C8-36 (F-5): тот же игнор по статусу, но для сообщения БЕЗ идентификатора
     * вызова (legacy fail-open путь) обязан получить ОТДЕЛЬНЫЙ тег причины.
     *
     * <p>Почему это не «косметика метрики»: принятый риск E-3 (fail-open без
     * CR-01) измеряется счётчиком, и если его игноры падают в общий счётчик с
     * phased-игнорами и stale_status, то доля трафика вне защиты по метрике не
     * считается — а именно ради измеримости риск и принимался.
     *
     * <p>Мутация «вернуть один тег на оба случая» валит этот тест; мутация
     * «не считать legacy вовсе» — тоже (verify требует сам факт вызова).
     */
    @Test
    void staleStatusCompletion_withoutCallIdentifier_taggedLegacyNullPhase() {
        UUID serviceTaskId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setStatus(ActivityStatus.COMPLETED);
        activity.setProcessInstanceId(UUID.randomUUID());
        activity.setToken(UUID.randomUUID());
        activity.setBpmnElementId("serviceTask1");

        when(elementSupport.lockInstanceFirst(serviceTaskId)).thenReturn(activity);

        // dispatchPhase = null — ровно legacy-путь старого воркера / REST.
        completionService.completeServiceTask(serviceTaskId, java.util.List.of(), null, null, null,
            org.mockito.Mockito.mock(com.zorrodev.bpm.engine.handler.TokenExecutor.class));

        verify(bpmMetrics).activityTransitionIgnored("legacy_null_phase");
        verify(bpmMetrics, never()).activityTransitionIgnored("stale_status");
    }

    /**
     * WO-REL-59 criterion 2 (direct POF anchor): complete-пути берут
     * instance-lock ПЕРВЫМ, через {@code ElementSupport.lockInstanceFirst}
     * (единый порядок instance→activity с cancel-путём). POF-мутация —
     * вызвать в complete-пути activity-lock напрямую ({@code
     * dbService.getActivityForUpdate}) + поздний {@code
     * dbService.lockProcessInstance} (activity-first): эти тесты
     * краснеют ({@code Wanted but not invoked} на lockInstanceFirst +
     * {@code NeverWanted} на прямых локах), остальные — нет. Сам ПОРЯДОК
     * внутри lockInstanceFirst фиксирует PG-IT
     * {@code Rel59CompleteCancelDeadlockPgIT} (мутация тела прод-метода →
     * deadlock 40P01); здесь — разводка вызовов (G-N/P-46: guard у
     * потребителя доказан отдельно от центрального объекта).
     */
    @Test
    void completeServiceTask_takesInstanceLock() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setStatus(ActivityStatus.CREATED);
        activity.setProcessInstanceId(piId);
        activity.setToken(UUID.randomUUID());
        activity.setBpmnElementId("serviceTask1");

        when(elementSupport.lockInstanceFirst(serviceTaskId)).thenReturn(activity);

        // Guard дальше требует bpmn-блоков — достаточно достичь lock'а:
        // lock стоит ДО guard'а, вызов произойдёт до любого orElseThrow ниже.
        try {
            completionService.completeServiceTask(serviceTaskId, java.util.List.of(),
                org.mockito.Mockito.mock(com.zorrodev.bpm.engine.handler.TokenExecutor.class));
        } catch (Exception ignored) {
            // путь дальше lock'а (bpmn lookup) в unit-scope не стабаем —
            // важен сам факт вызова lock'а, а не завершение пути
        }

        // WO-REL-30: ровно один SELECT FOR UPDATE — внутри lockInstanceFirst;
        // прямых lock-вызовов из CompletionService больше нет (порядок живёт
        // в одном прод-методе, а не разведён по двум вызовам).
        verify(elementSupport, org.mockito.Mockito.times(1)).lockInstanceFirst(serviceTaskId);
        verify(dbService, never()).lockProcessInstance(any());
        verify(dbService, never()).getActivity(any());
        verify(dbService, never()).getActivityForUpdate(any());
    }

    @Test
    void completeUserTask_takesInstanceLock() {
        UUID userTaskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setId(userTaskId);
        activity.setStatus(ActivityStatus.CREATED);
        activity.setProcessInstanceId(piId);
        activity.setToken(UUID.randomUUID());
        activity.setBpmnElementId("userTask1");

        when(elementSupport.lockInstanceFirst(userTaskId)).thenReturn(activity);

        try {
            completionService.completeUserTask(userTaskId, java.util.List.of(),
                org.mockito.Mockito.mock(com.zorrodev.bpm.engine.handler.TokenExecutor.class));
        } catch (Exception ignored) {
            // см. выше — важен сам факт вызова lock'а
        }

        verify(elementSupport, org.mockito.Mockito.times(1)).lockInstanceFirst(userTaskId);
        verify(dbService, never()).lockProcessInstance(any());
        verify(dbService, never()).getActivity(any());
        verify(dbService, never()).getActivityForUpdate(any());
    }

    // ── WO-REL-63: P-46 anchors — каждый переведённый путь берёт instance-lock ПЕРВЫМ ──
    //
    // По одному якорю на потребителя, а не только на центральный lockInstanceFirst:
    // возврат ЛЮБОГО из этих путей на activity-only лок валит ровно его тест
    // (Wanted but not invoked на lockInstanceFirst + NeverWanted на прямом
    // getActivityForUpdate). Порядок захватов внутри самого lockInstanceFirst
    // фиксирует Rel63RemainingAbbaDeadlockPgIT на реальном PostgreSQL.
    //
    // Вызовы ниже доходят ровно до захвата и дальше уходят в исключение —
    // Collaborator'ы после лока в unit-скоупе не стабаются намеренно (как в
    // completeServiceTask_takesInstanceLock выше); важен факт и вид захвата.

    @Test
    void failServiceTask_takesInstanceLock() {
        UUID serviceTaskId = UUID.randomUUID();
        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setStatus(ActivityStatus.COMPLETED); // early return right after the lock
        when(elementSupport.lockInstanceFirst(serviceTaskId)).thenReturn(activity);

        completionService.failServiceTask(serviceTaskId, "boom", 0);

        verify(elementSupport, org.mockito.Mockito.times(1)).lockInstanceFirst(serviceTaskId);
        verify(dbService, never()).getActivityForUpdate(any());
    }

    @Test
    void assignUserTask_takesInstanceLock() {
        UUID taskId = UUID.randomUUID();
        Activity activity = new Activity();
        activity.setId(taskId);
        activity.setStatus(ActivityStatus.CREATED);
        activity.setProcessInstanceId(UUID.randomUUID());
        when(elementSupport.lockInstanceFirst(taskId)).thenReturn(activity);

        try {
            completionService.assignUserTask(taskId, "someone");
        } catch (Exception ignored) {
            // дальше лока: bpmn-lookup в unit-скоупе не стабаем
        }

        verify(elementSupport, org.mockito.Mockito.times(1)).lockInstanceFirst(taskId);
        verify(dbService, never()).getActivityForUpdate(any());
    }

    @Test
    void claimUserTask_takesInstanceLock() {
        UUID taskId = UUID.randomUUID();
        Activity activity = new Activity();
        activity.setId(taskId);
        activity.setStatus(ActivityStatus.CREATED);
        activity.setProcessInstanceId(UUID.randomUUID());
        when(elementSupport.lockInstanceFirst(taskId)).thenReturn(activity);

        try {
            completionService.claimUserTask(taskId, "someone");
        } catch (Exception ignored) {
            // дальше лока: bpmn-lookup в unit-скоупе не стабаем
        }

        verify(elementSupport, org.mockito.Mockito.times(1)).lockInstanceFirst(taskId);
        verify(dbService, never()).getActivityForUpdate(any());
    }

    @Test
    void completeAdHocScopeJob_takesInstanceLock() {
        UUID scopeId = UUID.randomUUID();
        Activity scope = new Activity();
        scope.setId(scopeId);
        scope.setType(BpmnElementType.AD_HOC_SUB_PROCESS);
        scope.setStatus(ActivityStatus.CANCELLED); // stale scope → 409 сразу после лока
        when(elementSupport.lockInstanceFirst(scopeId)).thenReturn(scope);

        assertThatThrownBy(() -> completionService.completeAdHocScopeJob(scopeId,
            new com.zorrodev.bpm.contract.dto.AdHocJobResultDTO(),
            org.mockito.Mockito.mock(TokenExecutor.class)))
            .isInstanceOf(com.zorrodev.bpm.contract.exception.ApiException.class);

        verify(elementSupport, org.mockito.Mockito.times(1)).lockInstanceFirst(scopeId);
        verify(dbService, never()).getActivityForUpdate(any());
    }

    @Test
    void signal_takesInstanceLock() {
        UUID activityId = UUID.randomUUID();
        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setStatus(ActivityStatus.CANCELLED); // early return right after the lock
        when(elementSupport.lockInstanceFirst(activityId)).thenReturn(activity);

        completionService.signal(activityId, List.of(), org.mockito.Mockito.mock(TokenExecutor.class));

        verify(elementSupport, org.mockito.Mockito.times(1)).lockInstanceFirst(activityId);
        verify(dbService, never()).getActivityForUpdate(any());
    }
}
