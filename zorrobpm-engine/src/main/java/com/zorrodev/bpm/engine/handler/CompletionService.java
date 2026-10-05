package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.exchange.ServiceTaskDispatchPhase;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Collaborator extracted from ActivityServiceImpl (WO-AUD-24).
 * Owns the completion/signal logic: completeUserTask, completeServiceTask, failServiceTask, signal,
 * triggerConditionalEvents, and the shared lockInstanceFirst/isBehindEventBasedGateway helpers.
 * TokenExecutor is passed as a parameter (port) to avoid circular bean dependencies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional
public class CompletionService {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final ElementSupport elementSupport;
    private final MultiInstanceExecutor multiInstanceExecutor;
    private final FlowNavigator flowNavigator;
    private final EventTrigger eventTrigger;
    private final ExecutionContext executionContext;
    private final UserTaskHandler userTaskHandler;
    private final ElementListenerPhaseService elementListenerPhaseService;
    private final AdHocSubProcessHandler adHocSubProcessHandler;
    private final InclusiveGatewayHandler inclusiveGatewayHandler;
    private final IncidentService incidentService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final BpmMetrics bpmMetrics;

    /**
     * WO-C8-36 (H-2): durable-дедуп отправок в {@code completion_dedup} — в БД, а не
     * в памяти JVM (in-memory не работал при N&gt;1 репликах, см. javadoc стора).
     */
    private final com.zorrodev.bpm.engine.service.CompletionDedupStore completionDedupStore;

    // Раунд 4 (F-8): поля completionDedupTtlSeconds здесь больше НЕТ. Оно
    // передавалось в claim(completionId, ttlSeconds), а параметр не читался —
    // время жизни маркера задаёт порасписание CompletionDedupCleanupJob. Поле с
    // javadoc «совпадает с TTL-очисткой» выглядело как носительство инварианта,
    // которого нет; оставлять его — мина под следующую правку захвата (P-14).

    /**
     * WO-C8-25 (extends WO-C8-24): element kinds whose jobs never live in
     * {@code service_tasks} rows (user tasks — C8-21/C8-24 phases; gateways and events —
     * C8-25 phases). A service-task completion arriving for them with no listener phase
     * open is spurious (e.g. a redelivered listener completion; the broker is at-least-once)
     * and is ignored instead of falling into the service-task tail (no row → orElseThrow).
     * Any FUTURE kind defaults to the tail (loud 500) — fail-closed by construction.
     */
    private static final Set<BpmnElementType> PHASE_ONLY_ELEMENT_TYPES = EnumSet.of(
        BpmnElementType.USER_TASK,
        BpmnElementType.EXCLUSIVE_GATEWAY, BpmnElementType.PARALLEL_GATEWAY,
        BpmnElementType.EVENT_BASED_GATEWAY, BpmnElementType.INCLUSIVE_GATEWAY,
        BpmnElementType.START_EVENT, BpmnElementType.MESSAGE_START_EVENT,
        BpmnElementType.TIMER_START_EVENT, BpmnElementType.SIGNAL_START_EVENT,
        BpmnElementType.END_EVENT, BpmnElementType.TERMINATE_END_EVENT,
        BpmnElementType.ERROR_END_EVENT, BpmnElementType.ESCALATION_END_EVENT,
        BpmnElementType.CANCEL_END_EVENT,
        BpmnElementType.INTERMEDIATE_CATCH_EVENT, BpmnElementType.MESSAGE_CATCH_EVENT,
        BpmnElementType.TIMER_CATCH_EVENT, BpmnElementType.SIGNAL_CATCH_EVENT,
        BpmnElementType.LINK_CATCH_EVENT, BpmnElementType.CONDITIONAL_CATCH_EVENT,
        BpmnElementType.INTERMEDIATE_THROW_EVENT, BpmnElementType.MESSAGE_THROW_EVENT,
        BpmnElementType.SIGNAL_THROW_EVENT, BpmnElementType.LINK_THROW_EVENT,
        BpmnElementType.ESCALATION_THROW_EVENT, BpmnElementType.COMPENSATION_THROW_EVENT);

    /** True if {@code element} is a catch event whose (only) incoming flow comes from an event-based gateway. */
    private boolean isBehindEventBasedGateway(BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        if (element.getIncoming() == null) {
            return false;
        }
        for (String incoming : element.getIncoming()) {
            BpmnFlowModel flow = bpmn.getFlow(incoming);
            if (flow == null) {
                continue;
            }
            BpmnElementModel source = bpmn.getElement(flow.getSourceRef());
            if (source != null && source.getType() == BpmnElementType.EVENT_BASED_GATEWAY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Completes a user task: applies variables, marks the activity and user task done, handles
     * IO mappings and multi-instance, then follows outgoing flows and re-evaluates conditionals.
     *
     * <p>WO-C8-24: if the element declares {@code completing} listeners and no phase is open,
     * this call OPENS the phase instead of completing — variables applied durably first,
     * listener job dispatched, activity stays put, token parked. A repeat call while the
     * phase is open throws {@code TaskCompletionInProgressException} (REST → 409).
     */
    public void completeUserTask(UUID userTaskId, List<ProcessVariable> variables, TokenExecutor executor) {
        // WO-REL-59: единый порядок захвата instance→activity — тот же, что у
        // cancel-пути (lockProcessInstance → cancelActiveActivities). Старый
        // комментарий WO-ENG-28 («инверсии порядка нет») был неверен: на PG
        // activity-лок внутри findByIdForUpdate — это FOR UPDATE OF с одним
        // алиасом (только activity, см. Rel59SqlProbePgIT), так что complete
        // брал activity→instance, а cancel — instance→activity: ABBA-deadlock.
        // Re-read после лока — паттерн WO-ENG-19 (внутри lockInstanceFirst:
        // отмена, целиком прошедшая между нашим первым чтением и локом, иначе
        // решалась бы по stale-статусу guard'ом ниже). Инвариант WO-REL-30
        // (ровно один SELECT FOR UPDATE) сохранён.
        Activity activity = elementSupport.lockInstanceFirst(userTaskId);
        if (!isUserTaskCompletionAllowed(userTaskId, activity)) {
            return;
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID token = activity.getToken();

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        rejectOpenAssigningPhase(userTaskId, activity, bpmnElement);
        if (openUpdatingPhaseOnVariables(userTaskId, variables, processInstanceId, token, bpmnElement, activity)) {
            return;
        }
        if (openCompletingPhase(userTaskId, variables, processInstanceId, token, bpmnElement, activity)) {
            return;
        }

        finishUserTaskCompletion(processInstanceId, token, userTaskId, variables, bpmn, bpmnElement, executor);
    }

    /**
     * WO-DEBT-6 S3: status guard of {@link #completeUserTask}.
     * Verbatim block except mechanical return plumbing. Returns true when the completion may proceed.
     */
    private boolean isUserTaskCompletionAllowed(UUID userTaskId, Activity activity) {
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            // only an active task may complete — ignore a duplicate/late completion, a boundary-timer
            // interruption (CANCELLED) or a task superseded by incident-resolve (ERROR) to avoid double execution
            log.info("Ignoring completion of user task {} in status {}", userTaskId, activity.getStatus());
            // WO-QW-2: observability for the idempotent guard above (log level + no-op unchanged).
            bpmMetrics.activityTransitionIgnored("stale_status");
            return false;
        }
        return true;
    }

    /**
     * WO-DEBT-6 S3: assigning-phase guard of {@link #completeUserTask} (WO-C8-28).
     * Verbatim block (void, zero-touch).
     */
    private void rejectOpenAssigningPhase(UUID userTaskId, Activity activity, BpmnElementModel bpmnElement) {
        // WO-C8-28: a complete attempted while another listener phase is open is a
        // client conflict (409), never a silent double transition — the in-flight
        // phase owns this task until its listeners finish. Same exception class as
        // completing (the REST catch maps by class); the message names the open phase.
        // (An updating phase is opened by this very method below; a completing phase
        // open hits its own 409 in its branch.)
        List<ListenerModel> assigningListeners = elementSupport.userTaskAssigningListeners(bpmnElement);
        Integer pendingAssigningOnComplete =
            assigningListeners.isEmpty() ? null : dbService.getPendingAssigningListenerIndex(userTaskId);
        if (pendingAssigningOnComplete != null) {
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already assigning"
                    + " (assigning listener " + pendingAssigningOnComplete + " in flight) — wait for it to finish");
        }
    }

    /**
     * WO-DEBT-6 S3: updating-phase opener of {@link #completeUserTask} (WO-C8-28).
     * Verbatim block except mechanical return plumbing (the phase-open return stops
     * the whole completion, hence boolean). Returns true when handled.
     */
    private boolean openUpdatingPhaseOnVariables(UUID userTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID token, BpmnElementModel bpmnElement, Activity activity) {
        // WO-C8-28: updating-listener phase — read only for elements that declare
        // updating listeners, so the common path never touches the new state. Opens
        // ONLY on a real variable write (complete WITH variables): a complete without
        // variables is not an update, so it skips straight to completing/immediate
        // below. There is no standalone task-variables endpoint (adding a public REST
        // signature is G-C stop-list), so complete-with-variables is the only entry.
        List<ListenerModel> updatingListeners = elementSupport.userTaskUpdatingListeners(bpmnElement);
        if (!updatingListeners.isEmpty() && variables != null && !variables.isEmpty()) {
            Integer pendingUpdating = dbService.getPendingUpdatingListenerIndex(userTaskId);
            if (pendingUpdating == null) {
                // First complete-with-variables → open the phase. Variables go FIRST
                // and durably (same reason as completing): if a listener fails and the
                // phase waits for incident resolve, the caller's variables must already
                // be in the instance.
                dbService.setVariables(processInstanceId, variables);
                dbService.setPendingUpdatingListenerIndex(userTaskId, 0);
                dbService.setUpdatingListenerRetriesRemaining(userTaskId,
                    elementSupport.listenerBudget(updatingListeners.get(0)));
                serviceTaskEnqueueService.enqueueAfterCommit(userTaskId);
                log.info("{}/{}: Completing user task, opening updating-listener phase of {}: {}/{}",
                    processInstanceId, token, activity.getBpmnElementId(), userTaskId, activity.getBpmnElementId());
                return true;
            }
            // Phase already open: same 409 discipline as completing (same exception
            // class — the REST catch maps by class; the message names this phase).
            // deny is deferred (criterion 5): no deny channel exists anywhere, so a
            // repeat complete can only wait, never cancel the update.
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already updating"
                    + " (updating listener " + pendingUpdating + " in flight) — wait for it to finish");
        }
        return false;
    }

    /**
     * WO-DEBT-6 S3: completing-phase opener of {@link #completeUserTask} (WO-C8-24).
     * Verbatim block except mechanical return plumbing (the phase-open return stops
     * the whole completion, hence boolean). Returns true when handled.
     */
    private boolean openCompletingPhase(UUID userTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID token, BpmnElementModel bpmnElement, Activity activity) {
        // WO-C8-24: completing-listener phase — read only for elements that declare
        // completing listeners, so the common path never touches the new state.
        List<ListenerModel> completingListeners = elementSupport.userTaskCompletingListeners(bpmnElement);
        if (!completingListeners.isEmpty()) {
            Integer pendingCompleting = dbService.getPendingCompletingListenerIndex(userTaskId);
            if (pendingCompleting == null) {
                // First complete → open the phase. Variables go FIRST and durably: if a
                // listener fails and the phase waits for incident resolve, the caller's
                // variables must already be in the instance (WO step 3).
                dbService.setVariables(processInstanceId, variables);
                dbService.setPendingCompletingListenerIndex(userTaskId, 0);
                dbService.setCompletingListenerRetriesRemaining(userTaskId,
                    elementSupport.listenerBudget(completingListeners.get(0)));
                serviceTaskEnqueueService.enqueueAfterCommit(userTaskId);
                log.info("{}/{}: Completing user task, opening completing-listener phase of {}: {}/{}",
                    processInstanceId, token, activity.getBpmnElementId(), userTaskId, activity.getBpmnElementId());
                return true;
            }
            // Phase already open: a repeat complete is a client conflict (409), never a
            // silent re-completion and never a 500 (WO step 5; closes the R1-review defect
            // class on this path — mid-phase REST used to fall into a 500).
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already completing"
                    + " (completing listener " + pendingCompleting + " in flight) — wait for it to finish");
        }
        return false;
    }

    /**
     * WO-C8-28: phase-aware assignment (assign-API). Without assigning listeners the
     * call is byte-identical to {@code dbService.assignUserTask} (same guards, routed
     * there directly). With listeners the assignment parks in {@code pendingAssignee}
     * and an assigning phase runs first; a repeat assign while any listener phase is
     * open is a client conflict (409, same class as completing — the REST catch maps
     * by class, the message names the open phase). deny is deferred (criterion 5):
     * no deny channel exists anywhere in the codebase, so a parked assignment can
     * only wait for its listeners, never be vetoed through this path.
     */
    public void assignUserTask(UUID taskId, String assignee) {
        // WO-REL-63: instance-lock ПЕРВЫМ — единый порядок захвата, см.
        // ElementSupport.lockInstanceFirst (обоснование и почему activity-first
        // ловил ABBA с отменой — там же).
        Activity activity = elementSupport.lockInstanceFirst(taskId);
        BpmnElementModel bpmnElement = bpmnElementOf(activity);
        List<ListenerModel> assigningListeners = elementSupport.userTaskAssigningListeners(bpmnElement);
        if (assigningListeners.isEmpty()
            || (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS)) {
            dbService.assignUserTask(taskId, assignee);
            return;
        }
        String openPhase = openListenerPhaseName(taskId);
        if (openPhase != null) {
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already " + openPhase
                    + " — wait for it to finish");
        }
        dbService.setPendingAssignee(taskId, assignee);
        dbService.setPendingAssigningListenerIndex(taskId, 0);
        dbService.setAssigningListenerRetriesRemaining(taskId,
            elementSupport.listenerBudget(assigningListeners.get(0)));
        serviceTaskEnqueueService.enqueueAfterCommit(taskId);
        log.info("{}/{}: Assigning user task, opening assigning-listener phase of {}: {}/{}",
            activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), taskId,
            activity.getBpmnElementId());
    }

    /**
     * WO-C8-28: phase-aware claim (Tasklist assignment). Same shape as
     * {@link #assignUserTask}: the CAS property (claim wins only on an unassigned
     * task) is enforced by the REST pre-check plus the 409 below — every runtime
     * assignee writer funnels through these phase checks under the instance lock,
     * so nothing can slip an assignment in between check and park. The resume tail
     * applies the parked assignee with the plain write (already serialized).
     */
    public void claimUserTask(UUID taskId, String assignee) {
        // WO-REL-63: instance-lock ПЕРВЫМ (см. assignUserTask).
        Activity activity = elementSupport.lockInstanceFirst(taskId);
        BpmnElementModel bpmnElement = bpmnElementOf(activity);
        List<ListenerModel> assigningListeners = elementSupport.userTaskAssigningListeners(bpmnElement);
        if (assigningListeners.isEmpty()
            || (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS)) {
            dbService.claimUserTask(taskId, assignee);
            return;
        }
        String openPhase = openListenerPhaseName(taskId);
        if (openPhase != null) {
            throw new com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException(
                "User task '" + activity.getBpmnElementId() + "' is already " + openPhase
                    + " — wait for it to finish");
        }
        dbService.setPendingAssignee(taskId, assignee);
        dbService.setPendingAssigningListenerIndex(taskId, 0);
        dbService.setAssigningListenerRetriesRemaining(taskId,
            elementSupport.listenerBudget(assigningListeners.get(0)));
        serviceTaskEnqueueService.enqueueAfterCommit(taskId);
        log.info("{}/{}: Claiming user task, opening assigning-listener phase of {}: {}/{}",
            activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), taskId,
            activity.getBpmnElementId());
    }

    /**
     * WO-C8-28: name of the in-flight listener phase on an activity ("assigning" /
     * "updating" / "completing" / "canceling"), or null when none is open. A creating
     * phase cannot be open wherever a task row exists (it closes by creating the row),
     * so it is not checked here — its callers 404 on the missing row first.
     */
    private String openListenerPhaseName(UUID activityId) {
        if (dbService.getPendingAssigningListenerIndex(activityId) != null) {
            return "assigning";
        }
        if (dbService.getPendingUpdatingListenerIndex(activityId) != null) {
            return "updating";
        }
        if (dbService.getPendingCompletingListenerIndex(activityId) != null) {
            return "completing";
        }
        if (dbService.getPendingCancelingListenerIndex(activityId) != null) {
            return "canceling";
        }
        return null;
    }

    private BpmnElementModel bpmnElementOf(Activity activity) {
        ProcessInstance processInstance = dbService.getProcessInstance(activity.getProcessInstanceId());
        BpmnProcessDefinitionModel bpmn =
            bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        return bpmn.getElement(activity.getBpmnElementId());
    }

    /**
     * WO-C8-24: the real user-task completion tail (variables already applied by the caller).
     * One body shared by the immediate path above and the last completing listener below —
     * the two cannot diverge.
     */
    private void finishUserTaskCompletion(UUID processInstanceId, UUID token, UUID userTaskId,
            List<ProcessVariable> variables, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement,
            TokenExecutor executor) {
        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(userTaskId);
        dbService.completeUserTask(userTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, token, BpmnElementType.USER_TASK, userTaskId, bpmnElement.getId());

        elementSupport.applyIoMappings(processInstanceId, userTaskId, bpmnElement, false);
        // multi-instance: append this instance's outputElement to the outputCollection before its scoped
        // variables (inputElement/loopCounter) are dropped
        multiInstanceExecutor.aggregateMultiInstanceOutput(processInstanceId, userTaskId, bpmnElement);
        dbService.deleteVariables(processInstanceId, userTaskId);
        if (multiInstanceExecutor.isMultiInstance(bpmnElement) && !multiInstanceExecutor.multiInstanceContinue(processInstanceId, token, bpmnElement, userTaskId)) {
            // more instances are outstanding (parallel) or the next one was just started (sequential)
            return;
        }
        // WO-C8-34 (CR-06): a finished user-task handler may unpark a
        // waitForCompletion thrower (same resume as the service-task tail).
        resumeParkedCompensationThrowers(processInstanceId, executor);
        // WO-C8-35 (CR-09, ШАГ 3/B2): a completed task is a DEACTIVATION — the last possible
        // deliverer of a parked inclusive-join may have just died (e.g. its XOR took the other
        // branch). Without this re-check the parked token never woke up (BLOCKER-2 red-team:
        // the instance stayed RUNNING forever, with no incident).
        inclusiveGatewayHandler.resumeParkedInclusiveJoins(processInstanceId, token, bpmn, executor);
        flowNavigator.proceedToOutgoing(processInstanceId, token, bpmn, bpmnElement, executor);
        triggerConditionalEvents(processInstanceId, executor);
    }

    /**
     * Completes a service task: applies variables, marks the activity and service task done, handles
     * IO mappings and multi-instance, then follows outgoing flows and re-evaluates conditionals.
     *
     * <p>WO-C8-11: if a start listener is in flight ({@code pendingListenerIndex != null}), a
     * completion means "this listener finished" — apply its variables, advance to the next
     * listener (or to the real job) and {@code return} WITHOUT completing the activity and
     * WITHOUT moving the token. Only the real job's completion follows the path below.
     */
    public void completeServiceTask(UUID serviceTaskId, List<ProcessVariable> variables, TokenExecutor executor) {
        completeServiceTask(serviceTaskId, variables, null, null, null, executor);
    }

    public void completeServiceTask(UUID serviceTaskId, List<ProcessVariable> variables,
            String dispatchPhase, Integer dispatchIndex, TokenExecutor executor) {
        completeServiceTask(serviceTaskId, variables, dispatchPhase, dispatchIndex, null, executor);
    }

    /**
     * WO-C8-36 (CR-01): тот же complete с идентификатором вызова из сообщения
     * воркера. Null-фаза = legacy без проверки (старый воркер/REST). Не-null фаза =
     * exact-match: принимается ТОЛЬКО результат ожидаемого вызова, дубликат/
     * устаревший игнорятся ДО переменных/ретраев/переходов и НИКОГДА не падают
     * в хвост чужой фазы (fail-closed; legacy остаётся fail-open — см. отчёт).
     *
     * <p>WO-C8-36 (red-team HOLD-1): {@code completionId} на SUCCESS-пути НЕ
     * используется — дедуп там не нужен (первый результат закрывает фазу,
     * повтор отсекает exact-match по фазе, см. {@link #completePhased}). Параметр
     * оставлен в сигнатуре, чтобы phased-вызовы имели единый набор
     * идентификаторов вызова; мёртвый параметр удалён бы в отдельном WO по P-14.
     * Дедуп FAILED-дубликатов — в {@link #failServiceTask}.
     */
    public void completeServiceTask(UUID serviceTaskId, List<ProcessVariable> variables,
            String dispatchPhase, Integer dispatchIndex, String completionId, TokenExecutor executor) {
        if (resumeElementListenerPhase(serviceTaskId, variables, executor)) {
            return;
        }
        if (ServiceTaskDispatchPhase.ELEMENT_START.equals(dispatchPhase)) {
            // Фазовая отправка, а PK-фазы уже нет (удалена после incident-exhaustion —
            // см. ElementListenerPhaseService.failPhaseListener): инцидент владеет
            // токеном, поздний SUCCESS воскрешать ничего не должен. Игнор вместо
            // orElseThrow лока ниже.
            log.info("Ignoring completion of element-listener phase {} with no phase row", serviceTaskId);
            bpmMetrics.activityTransitionIgnored("stale_phase");
            return;
        }
        Activity activity = elementSupport.lockInstanceFirst(serviceTaskId);
        // WO-REL-59: тот же единый порядок instance→activity, что в
        // completeUserTask выше (сериализация с cancel; см. комментарий выше).
        // Phase-resume выше лока осознанно: у phase-job нет activity-строки
        // (lock ниже orElseThrow — см. C8-25).
        if (!isCompletionAllowed(serviceTaskId, activity, dispatchPhase)) {
            return;
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID tokenId = activity.getToken();

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        if (dispatchPhase != null) {
            completePhased(serviceTaskId, variables, dispatchPhase, dispatchIndex, completionId,
                processInstanceId, tokenId, bpmn, bpmnElement, activity, executor);
            return;
        }

        // WO-C8-36 (red-team HOLD-2 — ОТКАЧЕНО, см. отчёт §HOLD-2): null-phase
        // при открытой фазе шёл в игнор, но это сломало 29 существующих тестов —
        // плоский вызов с открытыми фазами является легальным REST-путём
        // (оператор подтверждает текущий шаг фазы) и путём старых воркеров.
        // Менять семантику REST в этом WO нельзя (V7/scope) — legacy-цепочка
        // без изменений. Защита CR-01 действует на phased-сообщения нового
        // воркера; смешанные версии — остаточный риск с flag-day планом.
        //
        // WO-C8-36 (M-3): принятый риск обязан быть ИЗМЕРИМ. Счётчик + WARN на
        // каждый legacy-проход: без них во время rolling-обновления нельзя
        // оценить долю трафика вне защиты CR-01 и отличить намеренный обход
        // (flag-day) от обычного REST-трафика. Семантика при этом НЕ меняется.
        bpmMetrics.legacyUnphasedCompletion();
        log.warn("WO-C8-36: service-task completion {} accepted WITHOUT call identifier "
                + "(dispatchPhase=null) — legacy fail-open path, CR-01 exact-match guard "
                + "does NOT apply (old worker or REST caller)",
            serviceTaskId);
        completeLegacyChain(serviceTaskId, variables, processInstanceId, tokenId, bpmn, bpmnElement, activity, executor);
    }

    /**
     * WO-C8-36 (CR-01): legacy-цепочка completeServiceTask — побайтово вынесена из
     * метода выше (нулевой diff поведения). Сюда же делегирует phased-путь для
     * {@code real} при закрытых фазах (все фазовые ветки при этом no-op).
     */
    private void completeLegacyChain(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel bpmnElement, Activity activity, TokenExecutor executor) {
        if (handleCreatingListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement, activity)) {
            return;
        }

        if (handleCompletingListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmn, bpmnElement, activity, executor)) {
            return;
        }

        if (handleAssigningListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement, activity)) {
            return;
        }

        if (handleUpdatingListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement, activity, executor)) {
            return;
        }

        if (handleCancelingListeners(serviceTaskId, processInstanceId, tokenId, bpmn, bpmnElement, activity, executor)) {
            return;
        }

        if (rejectPhaseOnlyCompletion(serviceTaskId, bpmnElement)) {
            return;
        }

        if (handleStartListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement, activity)) {
            return;
        }

        if (handleEndListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement, activity)) {
            return;
        }
        finishServiceTaskCompletion(serviceTaskId, variables, processInstanceId, tokenId, bpmn, bpmnElement, activity, executor);
    }

    /**
     * WO-C8-36 (CR-01): phased-маршрутизация completion. Вызывается ПОСЛЕ лока
     * (instance→activity) и статус-гарда — конкурентные дубликаты сериализуются
     * локом, второй видит уже продвинутую фазу и глохнет. Совпадение — СТРОГОЕ
     * равенство текущей фазовой колонки с (phase,index) из сообщения; любое
     * несовпадение (дубликат закрытой фазы, устаревший индекс, чужой мусор) —
     * игнор БЕЗ fall-through в хвост (именно он и был дефектом CR-01).
     *
     * <p>При совпадении — делегация в существующую handle-ветку (она перечитывает
     * ту же колонку и находит её in-range, тело мутации общее, дублирования нет).
     *
     * <p><b>Возврат обработчика больше НЕ выбрасывается (WO-C8-36 H-3).</b> exact-match
     * выполнен, а обработчик всё равно может вернуть {@code false}: индекс оказался ВНЕ
     * диапазона новой модели (модель передеплоена без этого слушателя, пока воркер
     * отвечает) либо объявление фазы исчезло совсем. На этом месте legacy-цепочка
     * fall-through'ит дальше (fail-open, «completes and moves the token» — комментарий
     * {@code handleStartListeners}), и расхождение фазовой семантики с legacy было бы
     * ровно тем тихим зависанием, которое описал red-team: ни переменных, ни
     * {@code completeActivity}/{@code proceedToOutgoing}, ни лога, ни метрики, токен
     * паркован навсегда. Поэтому {@code false} = «продолжить по-legacy», а не возврат.
     *
     * <p>Почему именно {@link #completeLegacyChain}, а не прямой
     * {@code finishServiceTaskCompletion} (как в ветке END):
     * <ul>
     *   <li>для START ветка END недостижима — там {@code false} это «последний
     *       end-listener отработал, иди в хвост» с УЖЕ очищенной колонкой, повторный
     *       проход переоткрыл бы end-фазу (поймано живьём, комментарий в её ветке);</li>
     *   <li>для user-task фаз прямой хвост недопустим вовсе: у user-task нет строки
     *       {@code service_tasks}, {@code dbService.completeServiceTask} упал бы
     *       orElseThrow, тогда как legacy молча уходит в
     *       {@code rejectPhaseOnlyCompletion} (USER_TASK в PHASE_ONLY_ELEMENT_TYPES);</li>
     *   <li>в остальном полный проход — это буквально тот же путь, который проходит
     *       legacy на том же входе, включая открытие end-фазы у START.</li>
     * </ul>
     * Повторный вызов упавшего обработчика безвреден: вернуть {@code false} он может
     * только на ветке, которая НИЧЕГО не мутирует (все мутации — в in-range ветках,
     * вернувших {@code true}).
     */
    private void completePhased(UUID serviceTaskId, List<ProcessVariable> variables,
            String dispatchPhase, Integer dispatchIndex, String completionId,
            UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel bpmnElement, Activity activity, TokenExecutor executor) {
        switch (dispatchPhase) {
            case ServiceTaskDispatchPhase.START -> {
                Integer pending = dbService.getServiceTaskPendingListenerIndex(serviceTaskId);
                if (pending != null && pending.equals(dispatchIndex)) {
                    if (!handleStartListeners(serviceTaskId, variables, processInstanceId, tokenId,
                            bpmnElement, activity)) {
                        completeLegacyChain(serviceTaskId, variables, processInstanceId, tokenId, bpmn,
                            bpmnElement, activity, executor);
                    }
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, pending);
                }
            }
            case ServiceTaskDispatchPhase.END -> {
                Integer pending = dbService.getServiceTaskPendingEndListenerIndex(serviceTaskId);
                if (pending != null && pending.equals(dispatchIndex)) {
                    // Последний end-listener падает в хвост: handleEndListeners
                    // чистит индекс и возвращает false (= "иди в хвост") — но
                    // хвост здесь НЕ вся legacy-цепочка (она переоткрыла бы
                    // end-фазу: индекс уже null — поймано живьём, двойное
                    // "Real job done, opening end-listener phase"), а ПРЯМО
                    // finishServiceTaskCompletion, как падает legacy-хвост.
                    if (!handleEndListeners(serviceTaskId, variables, processInstanceId, tokenId,
                            bpmnElement, activity)) {
                        finishServiceTaskCompletion(serviceTaskId, variables, processInstanceId, tokenId,
                            bpmn, bpmnElement, activity, executor);
                    }
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, pending);
                }
            }
            case ServiceTaskDispatchPhase.CREATING -> {
                Integer pending = dbService.getPendingCreatingListenerIndex(serviceTaskId);
                if (activity.getType() == BpmnElementType.USER_TASK && pending != null && pending.equals(dispatchIndex)) {
                    if (!handleCreatingListeners(serviceTaskId, variables, processInstanceId, tokenId,
                            bpmnElement, activity)) {
                        completeLegacyChain(serviceTaskId, variables, processInstanceId, tokenId, bpmn,
                            bpmnElement, activity, executor);
                    }
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, pending);
                }
            }
            case ServiceTaskDispatchPhase.COMPLETING -> {
                Integer pending = dbService.getPendingCompletingListenerIndex(serviceTaskId);
                if (activity.getType() == BpmnElementType.USER_TASK && pending != null && pending.equals(dispatchIndex)) {
                    if (!handleCompletingListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmn,
                            bpmnElement, activity, executor)) {
                        completeLegacyChain(serviceTaskId, variables, processInstanceId, tokenId, bpmn,
                            bpmnElement, activity, executor);
                    }
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, pending);
                }
            }
            case ServiceTaskDispatchPhase.ASSIGNING -> {
                Integer pending = dbService.getPendingAssigningListenerIndex(serviceTaskId);
                if (activity.getType() == BpmnElementType.USER_TASK && pending != null && pending.equals(dispatchIndex)) {
                    if (!handleAssigningListeners(serviceTaskId, variables, processInstanceId, tokenId,
                            bpmnElement, activity)) {
                        completeLegacyChain(serviceTaskId, variables, processInstanceId, tokenId, bpmn,
                            bpmnElement, activity, executor);
                    }
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, pending);
                }
            }
            case ServiceTaskDispatchPhase.UPDATING -> {
                Integer pending = dbService.getPendingUpdatingListenerIndex(serviceTaskId);
                if (activity.getType() == BpmnElementType.USER_TASK && pending != null && pending.equals(dispatchIndex)) {
                    if (!handleUpdatingListeners(serviceTaskId, variables, processInstanceId, tokenId, bpmnElement,
                            activity, executor)) {
                        completeLegacyChain(serviceTaskId, variables, processInstanceId, tokenId, bpmn,
                            bpmnElement, activity, executor);
                    }
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, pending);
                }
            }
            case ServiceTaskDispatchPhase.CANCELING -> {
                Integer pending = dbService.getPendingCancelingListenerIndex(serviceTaskId);
                if (activity.getType() == BpmnElementType.USER_TASK && pending != null && pending.equals(dispatchIndex)) {
                    if (!handleCancelingListeners(serviceTaskId, processInstanceId, tokenId, bpmn, bpmnElement,
                            activity, executor)) {
                        completeLegacyChain(serviceTaskId, variables, processInstanceId, tokenId, bpmn,
                            bpmnElement, activity, executor);
                    }
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, pending);
                }
            }
            case ServiceTaskDispatchPhase.REAL -> {
                if (allListenerPhasesClosed(serviceTaskId, bpmnElement)) {
                    // Ни одна фаза не открыта — все фазовые ветки legacy-цепочки
                    // no-op, end-фаза при её наличии откроется штатно, затем хвост.
                    completeLegacyChain(serviceTaskId, variables, processInstanceId, tokenId, bpmn,
                        bpmnElement, activity, executor);
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, null);
                }
            }
            default ->
                // Неизвестная фаза (будущий продюсер) — fail-closed игнор, не падение.
                ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, null);
        }
    }

    /**
     * WO-C8-36: все ли фазовые колонки закрыты (зеркало чтения enqueue-штампа —
     * читается только то, что элемент декларирует, как и везде в этом файле).
     */
    private boolean allListenerPhasesClosed(UUID serviceTaskId, BpmnElementModel bpmnElement) {
        if (!elementSupport.userTaskCreatingListeners(bpmnElement).isEmpty()
            && dbService.getPendingCreatingListenerIndex(serviceTaskId) != null) {
            return false;
        }
        if (!elementSupport.userTaskCompletingListeners(bpmnElement).isEmpty()
            && dbService.getPendingCompletingListenerIndex(serviceTaskId) != null) {
            return false;
        }
        if (!elementSupport.userTaskAssigningListeners(bpmnElement).isEmpty()
            && dbService.getPendingAssigningListenerIndex(serviceTaskId) != null) {
            return false;
        }
        if (!elementSupport.userTaskUpdatingListeners(bpmnElement).isEmpty()
            && dbService.getPendingUpdatingListenerIndex(serviceTaskId) != null) {
            return false;
        }
        if (!elementSupport.userTaskCancelingListeners(bpmnElement).isEmpty()
            && dbService.getPendingCancelingListenerIndex(serviceTaskId) != null) {
            return false;
        }
        if (!elementSupport.serviceTaskStartListeners(bpmnElement).isEmpty()
            && dbService.getServiceTaskPendingListenerIndex(serviceTaskId) != null) {
            return false;
        }
        if (!elementSupport.serviceTaskEndListeners(bpmnElement).isEmpty()
            && dbService.getServiceTaskPendingEndListenerIndex(serviceTaskId) != null) {
            return false;
        }
        return true;
    }

    /** WO-C8-36: единый игнор устаревшего/дубликатного completion (лог + метрика, без эффекта). */
    private void ignoreStaleCompletion(UUID serviceTaskId, String dispatchPhase, Integer dispatchIndex,
            Integer pending) {
        log.info("Ignoring stale/duplicate completion of service task {} (phase={}, index={}, pending={})",
            serviceTaskId, dispatchPhase, dispatchIndex, pending);
        bpmMetrics.activityTransitionIgnored("stale_phase");
    }

    /**
     * WO-DEBT-6 S1: status guard of {@link #completeServiceTask} (WO-C8-28).
     * Extracted byte-identical except mechanical return plumbing. Returns true when the completion may proceed.
     */
    private boolean isCompletionAllowed(UUID serviceTaskId, Activity activity, String dispatchPhase) {
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            // WO-C8-28: a CANCELLED activity with an open canceling phase is NOT done —
            // its listener completions must reach the canceling branch below (the phase
            // defers the cancellation tail). The extra read runs only for non-active
            // statuses, so the hot CREATED/IN_PROGRESS path never touches the new state.
            if (activity.getStatus() != ActivityStatus.CANCELLED
                || dbService.getPendingCancelingListenerIndex(serviceTaskId) == null) {
                // only an active task may complete. Ignore anything else to avoid advancing the token twice:
                // a redelivered/late RabbitMQ completion (broker is at-least-once), a boundary-timer
                // interruption (CANCELLED), an already-COMPLETED task, or a task parked on an incident
                // (ERROR) that was superseded by incident-resolve re-execution.
                log.info("Ignoring completion of service task {} in status {}", serviceTaskId, activity.getStatus());
                // WO-QW-2: observability for the idempotent guard above (log level + no-op unchanged).
                // WO-C8-36 (F-5): тег причины РАЗДЕЛЁН — игнор без идентификатора
                // вызова (legacy fail-open путь, dispatchPhase == null) иначе неотличим
                // от игнора phased-сообщения, хотя у первого нет защиты CR-01 вовсе.
                // Это тот же объект «принято осознанным риском» (E-3), но измеряемый:
                // E-3a обязан быть виден по метрике, а не только по WARN в логе.
                bpmMetrics.activityTransitionIgnored(dispatchPhase == null
                    ? "legacy_null_phase" : "stale_status");
                return false;
            }
        }
        return true;
    }

    /**
     * WO-DEBT-6 S1: element-listener phase head of {@link #completeServiceTask} (WO-C8-25).
     * Extracted byte-identical except mechanical return plumbing. Returns true when routed.
     */
    private boolean resumeElementListenerPhase(UUID serviceTaskId, List<ProcessVariable> variables,
            TokenExecutor executor) {
        // WO-C8-25: element-listener phase jobs carry no activity row — route by phase PK
        // FIRST (the lock below would orElseThrow). Absent phase = existing path below,
        // byte-identical (one indexed PK read extra on the completion path).
        Optional<ElementListenerPhaseService.Resume> phaseResume =
            elementListenerPhaseService.completePhaseListener(serviceTaskId, variables);
        if (phaseResume.isPresent()) {
            ElementListenerPhaseService.Resume resume = phaseResume.get();
            if (resume.finished()) {
                // No re-entry guard needed: the finished phase is marked done BEFORE this
                // call, so the park-check below finds the done marker and proceeds to the
                // handler instead of re-opening (a loop is structurally impossible).
                executor.execute(resume.processInstanceId(), resume.tokenId(), resume.bpmnElementId());
            }
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: creating-listener dispatcher of {@link #completeServiceTask}.
     * New seam (leaves hold the verbatim blocks); conditions/comments moved unchanged. Returns true when handled.
     */
    private boolean handleCreatingListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity) {
        // WO-C8-21r2: creating-listener completion — the phase index lives on the ACTIVITY
        // row (no user_tasks row exists until the task is really created). A completion means
        // "this listener finished": advance to the next listener (with its own retry budget),
        // or create the task after the last one — WITHOUT touching the service-task tail below.
        List<ListenerModel> creatingListeners = elementSupport.userTaskCreatingListeners(bpmnElement);
        if (!creatingListeners.isEmpty()) {
            Integer pendingCreating = dbService.getPendingCreatingListenerIndex(serviceTaskId);
            if (pendingCreating != null) {
                if (pendingCreating >= 0 && pendingCreating < creatingListeners.size()) {
                    return advanceCreatingListener(serviceTaskId, variables, processInstanceId, tokenId,
                        bpmnElement, activity, pendingCreating, creatingListeners);
                }
                // Out-of-bounds/foreign index (model redeployed mid-flight, phase MEANT open):
                // fail-open into task creation rather than stranding (mirror of the C8-11
                // fail-open below). A null index is NOT this case — see below.
                return failOpenCreatingListener(serviceTaskId, processInstanceId, tokenId, bpmnElement, activity);
            }
            // Null index = NO creating phase open: this completion is not a creating-listener
            // completion (e.g. a completing-listener job on an element declaring both kinds) —
            // fall through so the completing branch below sees it. WO-C8-24: the old code
            // fail-opened here and re-created the task on every later completion.
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: in-range creating-listener step of {@link #completeServiceTask}.
     * Split from {@link #handleCreatingListeners}; verbatim block, mechanical plumbing. Returns true.
     */
    private boolean advanceCreatingListener(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity,
            Integer pendingCreating, List<ListenerModel> creatingListeners) {
        dbService.setVariables(processInstanceId, variables);
        if (pendingCreating + 1 < creatingListeners.size()) {
            dbService.setPendingCreatingListenerIndex(serviceTaskId, pendingCreating + 1);
            dbService.setCreatingListenerRetriesRemaining(serviceTaskId,
                elementSupport.listenerBudget(creatingListeners.get(pendingCreating + 1)));
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Completing creating listener {} of {}: {}/{}", processInstanceId, tokenId,
                pendingCreating, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
            return true;
        }
        dbService.setPendingCreatingListenerIndex(serviceTaskId, null);
        dbService.setCreatingListenerRetriesRemaining(serviceTaskId, null);
        // WO-C8-30: broken priorityDefinition halts here (incident, no row) —
        // same guard as the immediate path above.
        if (!userTaskHandler.createTaskRow(processInstanceId, serviceTaskId, bpmnElement)) {
            return true;
        }
        userTaskHandler.postCreation(processInstanceId, tokenId, serviceTaskId, bpmnElement);
        log.info("{}/{}: Last creating listener done, task created: {}/{}", processInstanceId, tokenId,
            serviceTaskId, activity.getBpmnElementId());
        // WO-C8-28: creating runs first; a parked assignment opens its own
        // phase now (deterministic order, WO test 8) instead of finishing
        // activation while an assigning transition is due.
        if (userTaskHandler.openAssigningPhaseAfterCreation(processInstanceId, serviceTaskId, bpmnElement)) {
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Creating done, opening assigning-listener phase: {}/{}",
                processInstanceId, tokenId, serviceTaskId, activity.getBpmnElementId());
        }
        return true;
    }

    /**
     * WO-DEBT-6 S1: out-of-bounds creating-listener tail of {@link #completeServiceTask}.
     * Split from {@link #handleCreatingListeners}; verbatim block, mechanical plumbing. Returns true.
     */
    private boolean failOpenCreatingListener(UUID serviceTaskId, UUID processInstanceId, UUID tokenId,
            BpmnElementModel bpmnElement, Activity activity) {
        dbService.setPendingCreatingListenerIndex(serviceTaskId, null);
        dbService.setCreatingListenerRetriesRemaining(serviceTaskId, null);
        // WO-C8-30: same halt-on-broken-priority guard as the normal tail above.
        if (!userTaskHandler.createTaskRow(processInstanceId, serviceTaskId, bpmnElement)) {
            return true;
        }
        userTaskHandler.postCreation(processInstanceId, tokenId, serviceTaskId, bpmnElement);
        log.info("{}/{}: Out-of-bounds creating listener index, task created fail-open: {}/{}",
            processInstanceId, tokenId, serviceTaskId, activity.getBpmnElementId());
        // WO-C8-28: same assigning hook as the normal tail above (the row was
        // just written by the same body, so the parked-assignee condition holds
        // identically) — a corrupt creating index must not swallow a due
        // assigning transition.
        if (userTaskHandler.openAssigningPhaseAfterCreation(processInstanceId, serviceTaskId, bpmnElement)) {
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Fail-open creating done, opening assigning-listener phase: {}/{}",
                processInstanceId, tokenId, serviceTaskId, activity.getBpmnElementId());
        }
        return true;
    }

    /**
     * WO-DEBT-6 S1: completing-listener phase of {@link #completeServiceTask} (WO-C8-24).
     * Extracted byte-identical except mechanical return plumbing. Returns true when handled.
     */
    private boolean handleCompletingListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel bpmnElement, Activity activity, TokenExecutor executor) {
        // WO-C8-24: completing-listener completion — read only for elements that declare
        // completing listeners, so the common path never touches the new state. A completion
        // means "this listener finished": advance to the next listener (with its own retry
        // budget), or run the real user-task completion tail after the last one — WITHOUT
        // touching the service-task tail below (there is no service_tasks row for a user
        // task; falling through would complete a foreign tail and move the token wrongly).
        List<ListenerModel> completingListeners = elementSupport.userTaskCompletingListeners(bpmnElement);
        if (!completingListeners.isEmpty()) {
            Integer pendingCompleting = dbService.getPendingCompletingListenerIndex(serviceTaskId);
            if (pendingCompleting != null) {
                if (pendingCompleting >= 0 && pendingCompleting < completingListeners.size()) {
                    dbService.setVariables(processInstanceId, variables);
                    if (pendingCompleting + 1 < completingListeners.size()) {
                        dbService.setPendingCompletingListenerIndex(serviceTaskId, pendingCompleting + 1);
                        dbService.setCompletingListenerRetriesRemaining(serviceTaskId,
                            elementSupport.listenerBudget(completingListeners.get(pendingCompleting + 1)));
                        serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                        log.info("{}/{}: Completing completing listener {} of {}: {}/{}", processInstanceId, tokenId,
                            pendingCompleting, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                        return true;
                    }
                    dbService.setPendingCompletingListenerIndex(serviceTaskId, null);
                    dbService.setCompletingListenerRetriesRemaining(serviceTaskId, null);
                    finishUserTaskCompletion(processInstanceId, tokenId, serviceTaskId, variables, bpmn, bpmnElement, executor);
                    log.info("{}/{}: Last completing listener done, task completed: {}/{}", processInstanceId, tokenId,
                        serviceTaskId, activity.getBpmnElementId());
                    return true;
                }
                // Out-of-bounds/foreign index (model redeployed mid-flight, phase MEANT open):
                // fail-open into the real user-task completion (same spirit as the C8-11
                // fail-open) rather than stranding — the task was asked to complete, so
                // complete it. NOT a fall-through into the service-task tail below (no
                // service_tasks row for a user task).
                dbService.setPendingCompletingListenerIndex(serviceTaskId, null);
                dbService.setCompletingListenerRetriesRemaining(serviceTaskId, null);
                finishUserTaskCompletion(processInstanceId, tokenId, serviceTaskId, variables, bpmn, bpmnElement, executor);
                log.info("{}/{}: Out-of-bounds completing listener index, task completed fail-open: {}/{}",
                    processInstanceId, tokenId, serviceTaskId, activity.getBpmnElementId());
                return true;
            }
            // Null index = NO completing phase open: fall through (a creating-listener
            // completion on an element declaring both kinds is handled above; anything else
            // reaching the user-task guard below is spurious and ignored there).
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: assigning-listener phase of {@link #completeServiceTask} (WO-C8-28).
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean handleAssigningListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity) {
        // WO-C8-28: assigning-listener completion — read only for elements that declare
        // assigning listeners. A completion means "this listener finished": advance to
        // the next listener (with its own retry budget), or apply the parked assignment
        // after the last one. The assignment applies only while the task is still
        // active — a cancellation that won meanwhile leaves the row untouched (the
        // phase is cleared either way so nothing strands).
        List<ListenerModel> assigningListenersRt = elementSupport.userTaskAssigningListeners(bpmnElement);
        if (!assigningListenersRt.isEmpty()) {
            Integer pendingAssigning = dbService.getPendingAssigningListenerIndex(serviceTaskId);
            if (pendingAssigning != null) {
                if (pendingAssigning >= 0 && pendingAssigning < assigningListenersRt.size()) {
                    dbService.setVariables(processInstanceId, variables);
                    if (pendingAssigning + 1 < assigningListenersRt.size()) {
                        dbService.setPendingAssigningListenerIndex(serviceTaskId, pendingAssigning + 1);
                        dbService.setAssigningListenerRetriesRemaining(serviceTaskId,
                            elementSupport.listenerBudget(assigningListenersRt.get(pendingAssigning + 1)));
                        serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                        log.info("{}/{}: Completing assigning listener {} of {}: {}/{}", processInstanceId, tokenId,
                            pendingAssigning, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                        return true;
                    }
                    String parkedAssignee = dbService.getPendingAssignee(serviceTaskId);
                    dbService.setPendingAssigningListenerIndex(serviceTaskId, null);
                    dbService.setAssigningListenerRetriesRemaining(serviceTaskId, null);
                    dbService.setPendingAssignee(serviceTaskId, null);
                    if (parkedAssignee != null && (activity.getStatus() == ActivityStatus.CREATED
                        || activity.getStatus() == ActivityStatus.IN_PROGRESS)) {
                        dbService.assignUserTask(serviceTaskId, parkedAssignee);
                    }
                    log.info("{}/{}: Last assigning listener done, assignment applied: {}/{}", processInstanceId, tokenId,
                        serviceTaskId, activity.getBpmnElementId());
                    return true;
                }
                // Out-of-bounds/foreign index (model redeployed mid-flight): incident, same
                // as creating/completing — a user task has no real job to fail open into.
                dbService.errorActivity(serviceTaskId);
                dbService.createIncident(serviceTaskId,
                    "User task '" + activity.getBpmnElementId() + "' has out-of-bounds assigning listener index — fix the process model");
                return true;
            }
            // Null index = NO assigning phase open: fall through (a completion for a
            // sibling phase on an element declaring several kinds is handled by its
            // own branch — phases never overlap by construction, see the open sites).
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: updating-listener phase of {@link #completeServiceTask} (WO-C8-28).
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean handleUpdatingListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity,
            TokenExecutor executor) {
        // WO-C8-28: updating-listener completion — read only for elements that declare
        // updating listeners. Advance like the sibling phases; after the last listener
        // continue EXACTLY as if complete() was just called with no new variables
        // (re-invocation, not duplication): the user's variables are already durable
        // (applied when the phase opened and at every advance), so an empty call can
        // only open the completing phase or finish — never reopen updating, which
        // requires non-empty variables.
        List<ListenerModel> updatingListenersRt = elementSupport.userTaskUpdatingListeners(bpmnElement);
        if (!updatingListenersRt.isEmpty()) {
            Integer pendingUpdating = dbService.getPendingUpdatingListenerIndex(serviceTaskId);
            if (pendingUpdating != null) {
                if (pendingUpdating >= 0 && pendingUpdating < updatingListenersRt.size()) {
                    dbService.setVariables(processInstanceId, variables);
                    if (pendingUpdating + 1 < updatingListenersRt.size()) {
                        dbService.setPendingUpdatingListenerIndex(serviceTaskId, pendingUpdating + 1);
                        dbService.setUpdatingListenerRetriesRemaining(serviceTaskId,
                            elementSupport.listenerBudget(updatingListenersRt.get(pendingUpdating + 1)));
                        serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                        log.info("{}/{}: Completing updating listener {} of {}: {}/{}", processInstanceId, tokenId,
                            pendingUpdating, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                        return true;
                    }
                    dbService.setPendingUpdatingListenerIndex(serviceTaskId, null);
                    dbService.setUpdatingListenerRetriesRemaining(serviceTaskId, null);
                    log.info("{}/{}: Last updating listener done, continuing to completion: {}/{}", processInstanceId, tokenId,
                        serviceTaskId, activity.getBpmnElementId());
                    completeUserTask(serviceTaskId, List.of(), executor);
                    return true;
                }
                // Out-of-bounds/foreign index: incident, same as the sibling phases.
                dbService.errorActivity(serviceTaskId);
                dbService.createIncident(serviceTaskId,
                    "User task '" + activity.getBpmnElementId() + "' has out-of-bounds updating listener index — fix the process model");
                return true;
            }
            // Null index = NO updating phase open: fall through.
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: canceling-listener dispatcher of {@link #completeServiceTask}.
     * New seam (in-range step lives in {@link #advanceOrFinishCanceling}); conditions/comments moved unchanged. Returns true when handled.
     */
    private boolean handleCancelingListeners(UUID serviceTaskId, UUID processInstanceId, UUID tokenId,
            BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement, Activity activity,
            TokenExecutor executor) {
        // WO-C8-28: canceling-listener completion — read only for elements that declare
        // canceling listeners (reachable on CANCELLED activities via the guard exemption
        // above). Advance like the sibling phases; after the last listener run the
        // deferred tail — but only if this was the last open canceling phase in scope
        // (serialized by the process-instance lock held since method entry, so two
        // concurrent closers cannot both see "none open"). Boundary path defers the
        // boundary continuation (per token); process-cancel path defers the
        // process-cancel tail (per instance). Observe-only: no deny branch exists
        // (Camunda: "it's not possible to deny the cancelation").
        List<ListenerModel> cancelingListenersRt = elementSupport.userTaskCancelingListeners(bpmnElement);
        if (!cancelingListenersRt.isEmpty()) {
            Integer pendingCanceling = dbService.getPendingCancelingListenerIndex(serviceTaskId);
            if (pendingCanceling != null) {
                if (pendingCanceling >= 0 && pendingCanceling < cancelingListenersRt.size()) {
                    return advanceOrFinishCanceling(serviceTaskId, processInstanceId, tokenId, bpmn,
                        bpmnElement, activity, executor, pendingCanceling, cancelingListenersRt);
                }
                // Out-of-bounds/foreign index: incident, same as the sibling phases.
                dbService.errorActivity(serviceTaskId);
                dbService.createIncident(serviceTaskId,
                    "User task '" + activity.getBpmnElementId() + "' has out-of-bounds canceling listener index — fix the process model");
                return true;
            }
            // Null index = NO canceling phase open: fall through.
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: in-range canceling step of {@link #completeServiceTask} — advance to
     * the next listener, or run the deferred tail after the last one. Verbatim in-range
     * block with mechanical return plumbing. Returns true.
     */
    private boolean advanceOrFinishCanceling(UUID serviceTaskId, UUID processInstanceId, UUID tokenId,
            BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement, Activity activity,
            TokenExecutor executor, Integer pendingCanceling, List<ListenerModel> cancelingListenersRt) {
        if (pendingCanceling + 1 < cancelingListenersRt.size()) {
            dbService.setPendingCancelingListenerIndex(serviceTaskId, pendingCanceling + 1);
            dbService.setCancelingListenerRetriesRemaining(serviceTaskId,
                elementSupport.listenerBudget(cancelingListenersRt.get(pendingCanceling + 1)));
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Completing canceling listener {} of {}: {}/{}", processInstanceId, tokenId,
                pendingCanceling, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
            return true;
        }
        String deferredBoundary = dbService.getPendingCancelBoundaryElementId(serviceTaskId);
        UUID resumeToken = activity.getToken();
        UUID resumePi = activity.getProcessInstanceId();
        dbService.setPendingCancelingListenerIndex(serviceTaskId, null);
        dbService.setCancelingListenerRetriesRemaining(serviceTaskId, null);
        dbService.setPendingCancelBoundaryElementId(serviceTaskId, null);
        log.info("{}/{}: Last canceling listener done, running deferred tail: {}/{}", processInstanceId, tokenId,
            serviceTaskId, activity.getBpmnElementId());
        if (deferredBoundary != null) {
            if (!dbService.hasOpenCancelingListenerPhaseOnToken(resumeToken)) {
                BpmnElementModel boundaryElement = bpmn.getElement(deferredBoundary);
                Token resumeHostToken = dbService.getToken(resumeToken);
                if (resumeHostToken.getPendingBranches() != null && resumeHostToken.getPendingBranches() > 0) {
                    dbService.decrementPendingBranches(resumeToken);
                }
                flowNavigator.proceedToOutgoing(resumePi, resumeToken, bpmn, boundaryElement, executor);
            }
        } else {
            if (!dbService.hasOpenCancelingListenerPhaseInInstance(resumePi)) {
                dbService.deleteTimerJobsByProcessInstanceId(resumePi);
                dbService.deleteMessageSubscriptionsByProcessInstanceId(resumePi);
                dbService.cancelProcessInstance(resumePi);
            }
        }
        return true;
    }

    /**
     * WO-DEBT-6 S1: phase-only element guard of {@link #completeServiceTask} (WO-C8-25).
     * Verbatim block, mechanical plumbing. Returns true when ignored.
     */
    private boolean rejectPhaseOnlyCompletion(UUID serviceTaskId, BpmnElementModel bpmnElement) {
        // WO-C8-25 (extends WO-C8-24): element kinds whose jobs never live in
        // service_tasks rows have no "real" job — a service-task completion arriving here
        // with no listener phase open on any branch above is spurious (e.g. a redelivered
        // listener completion; the broker is at-least-once). Ignore it instead of falling
        // into the service-task branches/tail below (no service_tasks row → orElseThrow).
        // Same philosophy as the status guard at the top of this method. Job-based
        // elements (taskDefinition present — C8-16 end/throw events) are EXEMPT: their jobs
        // do own service_tasks rows and complete through the normal path below.
        // Kinds owning service_tasks rows never match otherwise, so their path — including
        // the orElseThrow loudness on corruption — is unchanged. Future kinds default to
        // the tail (loud) — fail-closed by construction.
        String elementJob = elementSupport.serviceTaskJob(bpmnElement);
        if (PHASE_ONLY_ELEMENT_TYPES.contains(bpmnElement.getType())
            && (elementJob == null || elementJob.isBlank())) {
            log.info("Ignoring service-task completion of {} {} with no listener phase in flight",
                bpmnElement.getType(), serviceTaskId);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: start-listener phase of {@link #completeServiceTask} (WO-C8-11).
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean handleStartListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity) {
        // WO-C8-11: listener-step completion — read only for elements that declare listeners,
        // so the common path never touches the new state.
        List<ListenerModel> startListeners = elementSupport.serviceTaskStartListeners(bpmnElement);
        if (!startListeners.isEmpty()) {
            Integer pending = dbService.getServiceTaskPendingListenerIndex(serviceTaskId);
            if (pending != null && pending >= 0 && pending < startListeners.size()) {
                dbService.setVariables(processInstanceId, variables);
                if (pending + 1 < startListeners.size()) {
                    dbService.setPendingListenerIndex(serviceTaskId, pending + 1);
                    // WO-C8-21r2: the next listener owns its own retry budget (model value,
                    // default 3) — listener failures no longer eat the real job's budget.
                    dbService.setServiceTaskRetries(serviceTaskId,
                        elementSupport.listenerBudget(startListeners.get(pending + 1)));
                } else {
                    dbService.setPendingListenerIndex(serviceTaskId, null);
                    // WO-C8-21r2: the real job dispatches next with its own budget, fresh —
                    // whatever the listeners consumed stays with them.
                    dbService.setServiceTaskRetries(serviceTaskId,
                        elementSupport.serviceTaskRetries(bpmnElement));
                }
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                log.info("{}/{}: Completing start listener {} of {}: {}/{}", processInstanceId, tokenId,
                    pending, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                return true;
            }
            // Out-of-bounds/foreign index (model redeployed mid-flight): fall through to the
            // normal path below (fail-open, completes and moves the token) rather than stranding.
        }
        return false;
    }

    /**
     * WO-DEBT-6 S1: end-listener phase of {@link #completeServiceTask} (WO-C8-11b).
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean handleEndListeners(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement, Activity activity) {
        // WO-C8-11b: end-listener phase — read only for elements that declare end listeners,
        // so the common path never touches the new state.
        List<ListenerModel> endListeners = elementSupport.serviceTaskEndListeners(bpmnElement);
        Integer pendingEnd = endListeners.isEmpty() ? null : dbService.getServiceTaskPendingEndListenerIndex(serviceTaskId);
        if (!endListeners.isEmpty() && pendingEnd == null) {
            // Real-job completion → open the end phase WITHOUT completing the activity
            // (design CTO: the element is not complete until its end listeners ran, so the
            // status guard above stays green for every listener completion).
            dbService.setVariables(processInstanceId, variables);
            dbService.setPendingEndListenerIndex(serviceTaskId, 0);
            // WO-C8-21r2: the in-flight end listener owns its own retry budget.
            dbService.setServiceTaskRetries(serviceTaskId,
                elementSupport.listenerBudget(endListeners.get(0)));
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            log.info("{}/{}: Real job done, opening end-listener phase of {}: {}/{}", processInstanceId, tokenId,
                activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
            return true;
        }
        if (pendingEnd != null && pendingEnd >= 0 && pendingEnd < endListeners.size()) {
            if (pendingEnd + 1 < endListeners.size()) {
                dbService.setVariables(processInstanceId, variables);
                dbService.setPendingEndListenerIndex(serviceTaskId, pendingEnd + 1);
                // WO-C8-21r2: the next end listener owns its own retry budget.
                dbService.setServiceTaskRetries(serviceTaskId,
                    elementSupport.listenerBudget(endListeners.get(pendingEnd + 1)));
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                log.info("{}/{}: Completing end listener {} of {}: {}/{}", processInstanceId, tokenId,
                    pendingEnd, activity.getBpmnElementId(), serviceTaskId, activity.getBpmnElementId());
                return true;
            }
            dbService.setPendingEndListenerIndex(serviceTaskId, null);
            // Last end listener done → fall through to the real completion tail below
            // (it applies this completion's variables itself).
        }
        // No end phase (or corrupt/foreign end index): fail-open into the normal tail below.
        return false;
    }

    /**
     * WO-DEBT-6 S1: real completion tail of {@link #completeServiceTask}. Verbatim block (void, zero-touch).
     */
    private void finishServiceTaskCompletion(UUID serviceTaskId, List<ProcessVariable> variables,
            UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel bpmnElement, Activity activity, TokenExecutor executor) {
        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(serviceTaskId);
        dbService.completeServiceTask(serviceTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, tokenId, activity.getType(), serviceTaskId, activity.getBpmnElementId());

        if (bpmnElement.getType() == BpmnElementType.END_EVENT) {
            // WO-C8-16: job-based end event — the worker's completion ends the branch exactly
            // like EndEventHandler (finishBranch; no proceedToOutgoing/conditional pass, which
            // would strand the token: proceedToOutgoing is a no-op without outgoing flows).
            // Only plain ends can arrive here (typed ends never park — see isJobBasedEvent).
            flowNavigator.finishBranch(processInstanceId, tokenId, bpmn, executor);
            return;
        }

        // WO-ENG-29, путь (a): output-маппинг, чей source не вычислился (FEEL
        // failure — например, переменная, которую воркер не вернул в результате
        // джобы), паркует токен как инцидент через ТОТ ЖЕ IncidentService, что и
        // input-путь в ActivityServiceImpl.execute() — не молча глотается и не
        // катит транзакцию без следа. ScriptOverloadException (временная
        // перегрузка пула) сюда НЕ попадает — он EngineException-наследник и
        // идёт прежним путём (контейнерный retry, см. ServiceTaskCompleteListener).
        try {
            elementSupport.applyIoMappings(processInstanceId, serviceTaskId, bpmnElement, false);
        } catch (com.zorrodev.bpm.engine.service.FeelEvaluationException e) {
            incidentService.raiseIncident(processInstanceId, tokenId, bpmnElement, e);
            return;
        }
        multiInstanceExecutor.aggregateMultiInstanceOutput(processInstanceId, serviceTaskId, bpmnElement);
        dbService.deleteVariables(processInstanceId, serviceTaskId);
        if (multiInstanceExecutor.isMultiInstance(bpmnElement) && !multiInstanceExecutor.multiInstanceContinue(processInstanceId, tokenId, bpmnElement, serviceTaskId)) {
            // more instances are outstanding (parallel) or the next one was just started (sequential)
            return;
        }
        // WO-C8-34 (CR-06): a finished compensation handler may unpark a
        // waitForCompletion thrower (resume completes it + continues outgoing).
        // The executor is THIS method's own parameter — the tail already carries it,
        // so the resume reuses it instead of a thread-local. The ThreadLocal this
        // replaced was a silent hang generator: any future caller reaching the tail
        // without going through completeServiceTask got null, the resume was skipped
        // and the thrower stayed parked with no log line (CTO HOLD 2026-10-05 п.3).
        // A null executor still skips the resume — reachable only from tests, visibly.
        if (executor != null) {
            resumeParkedCompensationThrowers(processInstanceId, executor);
            // WO-C8-35 (CR-09, ШАГ 3/B2) — the same re-check as the user-task tail.
            inclusiveGatewayHandler.resumeParkedInclusiveJoins(processInstanceId, tokenId, bpmn, executor);
        }
        flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, executor);
        triggerConditionalEvents(processInstanceId, executor);
    }

    /**
     * WO-C8-34 (CR-06): scans parked compensation throwers (IN_PROGRESS rows)
     * and resumes those whose launched handlers are all done: completes the
     * thrower row and continues its outgoing flow. Called from BOTH completion
     * tails (service-task + user-task) — a handler may itself be either kind,
     * and the throw path parks before knowing which kind will finish. Same
     * transaction as the completing tail, so the check sees the just-written
     * COMPLETED row.
     */
    public void resumeParkedCompensationThrowers(UUID processInstanceId, TokenExecutor executor) {
        resumeParkedCompensationThrowers(processInstanceId, executor, java.util.Set.of());
    }

    /**
     * WO-C8-34 red-team B2: {@code forceTerminalHandlerIds} carries the verdict of the
     * failing path (see {@code ElementSupport.compensationThrowerHasPending}) — the
     * retry-exhausted handler is done, however its row still reads in this snapshot.
     */
    public void resumeParkedCompensationThrowers(UUID processInstanceId, TokenExecutor executor,
            java.util.Set<String> forceTerminalHandlerIds) {
        List<Activity> parked = dbService.getActiveActivities(processInstanceId).stream()
            .filter(a -> a.getType() == BpmnElementType.COMPENSATION_THROW_EVENT)
            .toList();
        for (Activity thrower : parked) {
            ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
            BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());
            BpmnElementModel el = bpmn.getElement(thrower.getBpmnElementId());
            if (el == null) {
                continue;
            }
            // WO-C8-34 red-team: the candidate/pending rule lives in ElementSupport (one
            // copy for both sides — the two copies had diverged, see B1).
            List<Activity> targets = elementSupport.compensationTargets(processInstanceId, thrower.getToken(), el);
            if (!elementSupport.compensationThrowerHasPending(processInstanceId, thrower.getId(), bpmn, targets,
                    forceTerminalHandlerIds)) {
                dbService.completeActivity(thrower.getId());
                log.info("{}/{}: Compensation throw {} resumed after handlers completed", processInstanceId, thrower.getToken(), el.getId());
                flowNavigator.proceedToOutgoing(processInstanceId, thrower.getToken(), bpmn, el, executor);
            }
        }
    }

    /**
     * WO-C8-33: completes a job-worker ad-hoc scope job with its structured result —
     * the counterpart of {@link #completeServiceTask} for the FIRST typed job result in
     * this project. Deliberately NOT routed through the flat tail above (which would
     * proceed the scope's own outgoing): the worker's decision drives activation and
     * finishing here.
     * <p>
     * Staleness is explicit, never silent (unlike the at-least-once-tolerant flat tail):
     * unknown id → {@code NoSuchElementException} (REST 404); inactive scope or token
     * mismatch → 409 CONFLICT (the Zeebe {@code NOT_FOUND}-on-stale-completion analog).
     * A result that both fulfills the condition AND activates elements violates the raw
     * schema ("cannot fulfill both at the same time") → 400.
     */
    public void completeAdHocScopeJob(UUID scopeActivityId,
            com.zorrodev.bpm.contract.dto.AdHocJobResultDTO result, TokenExecutor executor) {
        // WO-REL-63: instance-lock ПЕРВЫМ (единый порядок захвата, см.
        // ElementSupport.lockInstanceFirst).
        Activity scope = elementSupport.lockInstanceFirst(scopeActivityId);
        if (scope.getType() != BpmnElementType.AD_HOC_SUB_PROCESS) {
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "AD_HOC_SCOPE_EXPECTED",
                "Activity " + scopeActivityId + " is not an ad-hoc sub-process scope",
                Map.of("scopeActivityId", scopeActivityId.toString()));
        }
        if (scope.getStatus() != ActivityStatus.CREATED && scope.getStatus() != ActivityStatus.IN_PROGRESS) {
            // Finished/cancelled/errored scope: nobody may decide for it anymore.
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.CONFLICT, "AD_HOC_JOB_STALE",
                "Ad-hoc scope job " + scopeActivityId + " is stale (scope " + scope.getStatus() + ")",
                Map.of("scopeActivityId", scopeActivityId.toString()));
        }
        boolean fulfilled = Boolean.TRUE.equals(result.getIsCompletionConditionFulfilled());
        List<com.zorrodev.bpm.contract.dto.AdHocActivateElementDTO> activate =
            result.getActivateElements() == null ? List.of() : result.getActivateElements();
        if (fulfilled && !activate.isEmpty()) {
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "AD_HOC_RESULT_CONTRADICTION",
                "Ad-hoc job result cannot fulfill the completion condition and activate elements at the same time",
                Map.of("scopeActivityId", scopeActivityId.toString()));
        }
        UUID processInstanceId = scope.getProcessInstanceId();
        UUID tokenId = scope.getToken();
        String currentToken = dbService.getVariables(processInstanceId).stream()
            .filter(v -> AdHocJoin.jobTokenVariable(scopeActivityId).equals(v.getName()))
            .findFirst()
            .map(ProcessVariable::getValue)
            .orElse(null);
        if (currentToken == null || result.getJobToken() == null || !currentToken.equals(result.getJobToken())) {
            // Recreated (or internal-mode) scope: this generation is over, explicitly.
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.CONFLICT, "AD_HOC_JOB_STALE",
                "Ad-hoc scope job " + scopeActivityId + " is stale (job recreated or not job-managed)",
                Map.of("scopeActivityId", scopeActivityId.toString()));
        }
        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn =
            bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel scopeElement = bpmn.getElement(scope.getBpmnElementId());
        if (fulfilled) {
            // Worker-owned finish: its cancel flag decides (schema default false — NOT the
            // BPMN attribute default true; different sources, implemented exactly).
            AdHocJoin.ScopeState state =
                AdHocJoin.resolve(dbService, objectMapper, processInstanceId, scopeActivityId);
            dbService.completeServiceTask(scopeActivityId);
            flowNavigator.finishAdHocScope(processInstanceId, tokenId, bpmn, scope,
                state == null ? null : AdHocJoin.joinKey(scopeActivityId, state.batchUuid()), executor,
                Boolean.TRUE.equals(result.getIsCancelRemainingInstances()));
            triggerConditionalEvents(processInstanceId, executor);
            return;
        }
        if (activate.isEmpty()) {
            // Explicit park (documented): worker decided nothing and did not fulfill.
            // Consume the job row so it stops being offered; no recreation, no incident.
            dbService.completeServiceTask(scopeActivityId);
            log.info("{}/{}: Ad-hoc scope job {} completed with empty activation — scope parked",
                processInstanceId, tokenId, scopeActivityId);
            return;
        }
        List<AdHocSubProcessHandler.ActivationRequest> requests = new java.util.ArrayList<>();
        for (com.zorrodev.bpm.contract.dto.AdHocActivateElementDTO item : activate) {
            requests.add(new AdHocSubProcessHandler.ActivationRequest(item.getElementId(),
                item.getVariables() == null ? List.of() : item.getVariables()));
        }
        ExecutionCtx activationCtx =
            new ExecutionCtx(processInstanceId, tokenId, executor, executionContext);
        if (!adHocSubProcessHandler.activateInnerElements(activationCtx, bpmn, scopeElement,
                scopeActivityId, requests, false)) {
            // Invalid element id: incident raised inside (mirrors internal mode), the
            // consumed job is NOT recreated — operator recovery, same posture as entry.
            dbService.completeServiceTask(scopeActivityId);
            return;
        }
        // Consume this generation (row upsert inside issueScopeJob resets it) and offer
        // exactly one current job: at most one VALID generation, older ones go 409.
        AdHocJoin.issueScopeJob(dbService, elementSupport, serviceTaskEnqueueService,
            processInstanceId, scopeActivityId, scopeElement);
    }

    /**
     * Reports a service-task (job) failure from a worker. {@code retries} follows Camunda {@code failJob}:
     * when non-null the retry budget is set to it ({@code 0} raises the incident immediately); when null the
     * budget is decremented by one. While retries remain the job is re-dispatched; when exhausted the activity
     * is marked ERROR and an incident carrying {@code errorMessage} is raised. The token stays parked.
     */
    /**
     * Executor-less failure report, kept for callers that have no flow to continue (unit
     * tests, pure bookkeeping): a null executor means no parked compensation thrower can
     * be released from here — the incident is still raised. Production goes through
     * {@code ActivityServiceImpl.failServiceTask}, which passes {@code this}.
     */
    public void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries) {
        failServiceTask(serviceTaskId, errorMessage, retries, null, null, null);
    }

    /**
     * WO-C8-34 (CR-06, red-team B2): overload без идентификатора вызова, но с executor'ом —
     * {@code ActivityServiceImpl.failServiceTask} (legacy/REST-путь) едет через него.
     */
    public void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries, TokenExecutor executor) {
        failServiceTask(serviceTaskId, errorMessage, retries, null, null, null, executor);
    }

    public void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries,
            String dispatchPhase, Integer dispatchIndex) {
        failServiceTask(serviceTaskId, errorMessage, retries, dispatchPhase, dispatchIndex, null);
    }

    /**
     * WO-C8-36 (CR-01, крит.2): тот же fail с идентификатором вызова.
     * Null-фаза = legacy без проверки. Не-null фаза = exact-match: бюджет трогает
     * ТОЛЬКО FAILED ожидаемого вызова; устаревшая FAILED чужой (уже закрытой)
     * фазы игнорятся и НЕ расходуют бюджет нового вызова.
     *
     * <p>WO-C8-36 (red-team HOLD-1): {@code completionId} — дедуп дубликатов
     * ОТКРЫТОЙ фазы (confirm-loss переотправка той же отправки). Первый FAILED
     * с этим id обрабатывается (бюджет −1, редispatch), повтор с тем же id —
     * игнор. Null = legacy без дедупа.
     *
     * <p>Executor едет параметром (WO-C8-34 CR-06 red-team B2): thread-local тут
     * не возвращаем — тихий {@code null} при смене пути оставил бы паркованный
     * thrower ждать вечно.
     */
    public void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries,
            String dispatchPhase, Integer dispatchIndex, String completionId) {
        failServiceTask(serviceTaskId, errorMessage, retries, dispatchPhase, dispatchIndex, completionId, null);
    }

    /**
     * Единственная рабочая точка входа: идентификатор вызова и executor — параметрами.
     * Видна {@code ActivityServiceImpl} (другой пакет) — он и есть прод-путь fail.
     */
    public void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries,
            String dispatchPhase, Integer dispatchIndex, String completionId, TokenExecutor executor) {
        if (failElementListenerPhase(serviceTaskId, errorMessage, retries)) {
            return;
        }
        if (ServiceTaskDispatchPhase.ELEMENT_START.equals(dispatchPhase)) {
            // PK-фазы уже нет (см. комментарий в completeServiceTask выше) —
            // игнор вместо orElseThrow лока ниже.
            log.info("Ignoring failure of element-listener phase {} with no phase row", serviceTaskId);
            bpmMetrics.activityTransitionIgnored("stale_phase");
            return;
        }
        // WO-REL-63: instance-lock ПЕРВЫМ (единый порядок захвата, см.
        // ElementSupport.lockInstanceFirst).
        Activity activity = elementSupport.lockInstanceFirst(serviceTaskId);
        if (!isFailureProcessable(serviceTaskId, activity)) {
            return;
        }
        String message = (errorMessage == null || errorMessage.isBlank()) ? "Service task failed" : errorMessage;
        if (dispatchPhase != null) {
            failPhased(serviceTaskId, message, retries, dispatchPhase, dispatchIndex, completionId, activity,
                executor);
            return;
        }
        // WO-C8-36 (red-team HOLD-2 — ОТКАЧЕНО, см. комментарий в completeServiceTask
        // выше): null-phase FAILED идёт legacy-хвостом без изменений — shared-budget
        // дизайн (WO-C8-21r2) и REST-семантика сохранены.
        // WO-C8-36 (M-3): видимость та же, что у completion — счётчик + WARN.
        bpmMetrics.legacyUnphasedCompletion();
        log.warn("WO-C8-36: service-task failure {} accepted WITHOUT call identifier "
                + "(dispatchPhase=null) — legacy fail-open path, CR-01 exact-match guard "
                + "does NOT apply (old worker or REST caller)",
            serviceTaskId);
        failLegacyTail(serviceTaskId, retries, message, activity, executor);
    }

    /**
     * WO-C8-36: legacy-хвост failServiceTask — побайтово вынесен из метода выше
     * (нулевой diff поведения). Сюда же делегирует phased-путь при совпадении.
     */
    private void failLegacyTail(UUID serviceTaskId, Integer retries, String message, Activity activity,
            TokenExecutor executor) {
        if (handleUserTaskListenerFailure(serviceTaskId, retries, message, activity)) {
            return;
        }
        failSharedBudget(serviceTaskId, retries, message, activity, executor);
    }

    /**
     * WO-C8-36: phased-маршрутизация failure (зеркало completePhased).
     * Start/end/real делят общий бюджет-ряд с in-flight фазой (он выставляется
     * listener-бюджетом при open/advance — см. WO-C8-21r2), поэтому при совпадении
     * идут в failSharedBudget; user-task фазы — в свои fail-ветки.
     */
    private void failPhased(UUID serviceTaskId, String message, Integer retries,
            String dispatchPhase, Integer dispatchIndex, String completionId, Activity activity,
            TokenExecutor executor) {
        switch (dispatchPhase) {
            case ServiceTaskDispatchPhase.START -> {
                Integer pending = dbService.getServiceTaskPendingListenerIndex(serviceTaskId);
                if (pending != null && pending.equals(dispatchIndex)) {
                    failSharedBudgetOnce(serviceTaskId, retries, message, activity, completionId, executor);
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, pending);
                }
            }
            case ServiceTaskDispatchPhase.END -> {
                Integer pending = dbService.getServiceTaskPendingEndListenerIndex(serviceTaskId);
                if (pending != null && pending.equals(dispatchIndex)) {
                    failSharedBudgetOnce(serviceTaskId, retries, message, activity, completionId, executor);
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, pending);
                }
            }
            case ServiceTaskDispatchPhase.CREATING -> failUserTaskPhase(serviceTaskId, message, retries,
                dispatchPhase, dispatchIndex, completionId, activity,
                dbService.getPendingCreatingListenerIndex(serviceTaskId), executor);
            case ServiceTaskDispatchPhase.COMPLETING -> failUserTaskPhase(serviceTaskId, message, retries,
                dispatchPhase, dispatchIndex, completionId, activity,
                dbService.getPendingCompletingListenerIndex(serviceTaskId), executor);
            case ServiceTaskDispatchPhase.ASSIGNING -> failUserTaskPhase(serviceTaskId, message, retries,
                dispatchPhase, dispatchIndex, completionId, activity,
                dbService.getPendingAssigningListenerIndex(serviceTaskId), executor);
            case ServiceTaskDispatchPhase.UPDATING -> failUserTaskPhase(serviceTaskId, message, retries,
                dispatchPhase, dispatchIndex, completionId, activity,
                dbService.getPendingUpdatingListenerIndex(serviceTaskId), executor);
            case ServiceTaskDispatchPhase.CANCELING -> failUserTaskPhase(serviceTaskId, message, retries,
                dispatchPhase, dispatchIndex, completionId, activity,
                dbService.getPendingCancelingListenerIndex(serviceTaskId), executor);
            case ServiceTaskDispatchPhase.REAL -> {
                ProcessInstance failPi = dbService.getProcessInstance(activity.getProcessInstanceId());
                BpmnProcessDefinitionModel failBpmn =
                    bpmnService.getProcessDefinitionModelById(failPi.getProcessDefinitionId());
                if (allListenerPhasesClosed(serviceTaskId, failBpmn.getElement(activity.getBpmnElementId()))) {
                    failSharedBudgetOnce(serviceTaskId, retries, message, activity, completionId, executor);
                } else {
                    ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, null);
                }
            }
            default ->
                ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, null);
        }
    }

    /**
     * WO-C8-36: общий exact-match для user-task fail-фаз — при совпадении та же
     * fail-ветка, что в legacy (перечитывает колонку, находит in-range).
     */
    private void failUserTaskPhase(UUID serviceTaskId, String message, Integer retries,
            String dispatchPhase, Integer dispatchIndex, String completionId, Activity activity, Integer pending,
            TokenExecutor executor) {
        if (activity.getType() == BpmnElementType.USER_TASK && pending != null && pending.equals(dispatchIndex)) {
            failSharedBudgetOnce(serviceTaskId, retries, message, activity, completionId, executor);
        } else {
            ignoreStaleCompletion(serviceTaskId, dispatchPhase, dispatchIndex, pending);
        }
    }

    /**
     * WO-C8-36 (red-team HOLD-1): дедуп дубликатов ОТКРЫТОЙ фазы по completionId.
     * Первый FAILED с этим id идёт в failSharedBudget (бюджет −1, редispatch —
     * НОВАЯ отправка воркера получит НОВЫЙ completionId, цикл не застревает);
     * повтор с тем же id — игнор. Null id = legacy без дедупа.
     *
     * <p>WO-C8-36 (H-2, правка раунда 3): маркер ЖИВЁТ В БД
     * ({@link com.zorrodev.bpm.engine.service.CompletionDedupStore}), а не в памяти
     * процесса — раньше здесь стояло «память — только in-process, рестарт движка
     * очищает сет», и это было верно для ПЕРВОЙ версии фикса, но уже не для текущей:
     * in-memory дедуп не работал на топологии N&gt;1 (проект гоняет три реплики на одном
     * PG в {@code docker-compose.multi.yml}), где каждый процесс держал своё множество.
     * Поле {@code processedCompletionIds} в дереве больше не существует — упоминание
     * осталось только здесь и было враньём для следующего читателя.
     *
     * <p>Подтверждено на живом PostgreSQL двумя реальными транзакциями
     * ({@code CompletionDedupClusterPgIT.criterionF1_twoRealTransactions_…}): ровно
     * один расход бюджета, обе транзакции целы.
     */
    private void failSharedBudgetOnce(UUID serviceTaskId, Integer retries, String message, Activity activity,
            String completionId, TokenExecutor executor) {
        // WO-C8-36 (H-2): захват решает БД (INSERT в ЭТОЙ транзакции), поэтому
        // откат транзакции убирает маркер сам — ручного remove не осталось
        // (это же и закрывало «red-team 1.4»).
        if (!completionDedupStore.claim(completionId)) {
            log.info("Ignoring duplicate failure of service task {} (completionId={} already processed)",
                serviceTaskId, completionId);
            bpmMetrics.activityTransitionIgnored("duplicate_completion");
            return;
        }
        failSharedBudget(serviceTaskId, retries, message, activity, executor);
    }

    /**
     * WO-DEBT-6 S2: finished-activity guard of {@link #failServiceTask} — an already
     * finished task ignores the failure (redelivered failure, boundary interruption).
     * Verbatim block, mechanical plumbing. Returns true when the failure may proceed.
     */
    private boolean isFailureProcessable(UUID serviceTaskId, Activity activity) {
        if (activity.getStatus() == ActivityStatus.COMPLETED || activity.getStatus() == ActivityStatus.CANCELLED) {
            // already finished (redelivered failure, or interrupted by a boundary) — ignore
            log.info("Ignoring failure of service task {} in status {}", serviceTaskId, activity.getStatus());
            return false;
        }
        return true;
    }

    /**
     * WO-DEBT-6 S2: element-listener phase-fail head of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when routed.
     */
    private boolean failElementListenerPhase(UUID serviceTaskId, String errorMessage, Integer retries) {
        // WO-C8-25: element-listener phase jobs carry no activity row — route by phase PK
        // FIRST (the lock below would orElseThrow). Absent phase = existing path below.
        if (elementListenerPhaseService.failPhaseListener(serviceTaskId, errorMessage, retries)) {
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: user-task listener-fail dispatcher of {@link #failServiceTask} — routes
     * to the per-phase budget leaf whose phase is open. New seam (leaves hold the verbatim
     * blocks); conditions moved unchanged, phases checked in original order. Returns true
     * when handled.
     */
    private boolean handleUserTaskListenerFailure(UUID serviceTaskId, Integer retries, String message,
            Activity activity) {
        // WO-C8-21r2: a failing creating-listener job draws from its own durable budget
        // (model value, default 3 — set at phase open/advance; there is no service_tasks
        // row for a user task, so the shared budget path below would orElseThrow).
        // Gated on the activity type first, so the common service-task fail path never
        // pays for the bpmn load below.
        if (activity.getType() == BpmnElementType.USER_TASK) {
            ProcessInstance failPi = dbService.getProcessInstance(activity.getProcessInstanceId());
            BpmnProcessDefinitionModel failBpmn = bpmnService.getProcessDefinitionModelById(failPi.getProcessDefinitionId());
            BpmnElementModel failElement = failBpmn.getElement(activity.getBpmnElementId());
            if (failCreatingListener(serviceTaskId, retries, message, activity, failElement)) {
                return true;
            }
            if (failCompletingListener(serviceTaskId, retries, message, activity, failElement)) {
                return true;
            }
            if (failAssigningListener(serviceTaskId, retries, message, activity, failElement)) {
                return true;
            }
            if (failUpdatingListener(serviceTaskId, retries, message, activity, failElement)) {
                return true;
            }
            if (failCancelingListener(serviceTaskId, retries, message, activity, failElement)) {
                return true;
            }
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: creating-listener fail budget of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean failCreatingListener(UUID serviceTaskId, Integer retries, String message,
            Activity activity, BpmnElementModel failElement) {
        if (!elementSupport.userTaskCreatingListeners(failElement).isEmpty()
            && dbService.getPendingCreatingListenerIndex(serviceTaskId) != null) {
            int remaining;
            if (retries != null) {
                // Camunda failJob semantics: explicit value sets the budget (0 → incident now).
                dbService.setCreatingListenerRetriesRemaining(serviceTaskId, retries);
                remaining = retries;
            } else {
                Integer budget = dbService.getCreatingListenerRetriesRemaining(serviceTaskId);
                remaining = (budget == null ? 0 : budget) - 1;
                dbService.setCreatingListenerRetriesRemaining(serviceTaskId, remaining);
            }
            if (remaining > 0) {
                log.info("{}/{}: User task creating listener {} failed ({} retries left), re-dispatching: {}",
                    activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                return true;
            }
            log.info("{}/{}: User task creating listener {} failed, retries exhausted — raising incident: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
            dbService.errorActivity(serviceTaskId);
            dbService.createIncident(serviceTaskId, message);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: completing-listener fail budget of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean failCompletingListener(UUID serviceTaskId, Integer retries, String message,
            Activity activity, BpmnElementModel failElement) {
        // WO-C8-24: same budget mechanics for an in-flight completing listener — the
        // task stays uncompleted, the token stays parked (WO step 6). Shared element
        // load above (failElement) is reused, not reloaded.
        if (!elementSupport.userTaskCompletingListeners(failElement).isEmpty()
            && dbService.getPendingCompletingListenerIndex(serviceTaskId) != null) {
            int remaining;
            if (retries != null) {
                dbService.setCompletingListenerRetriesRemaining(serviceTaskId, retries);
                remaining = retries;
            } else {
                Integer budget = dbService.getCompletingListenerRetriesRemaining(serviceTaskId);
                remaining = (budget == null ? 0 : budget) - 1;
                dbService.setCompletingListenerRetriesRemaining(serviceTaskId, remaining);
            }
            if (remaining > 0) {
                log.info("{}/{}: User task completing listener {} failed ({} retries left), re-dispatching: {}",
                    activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                return true;
            }
            log.info("{}/{}: User task completing listener {} failed, retries exhausted — raising incident: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
            dbService.errorActivity(serviceTaskId);
            dbService.createIncident(serviceTaskId, message);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: assigning-listener fail budget of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean failAssigningListener(UUID serviceTaskId, Integer retries, String message,
            Activity activity, BpmnElementModel failElement) {
        if (!elementSupport.userTaskAssigningListeners(failElement).isEmpty()
            && dbService.getPendingAssigningListenerIndex(serviceTaskId) != null) {
            int remaining;
            if (retries != null) {
                dbService.setAssigningListenerRetriesRemaining(serviceTaskId, retries);
                remaining = retries;
            } else {
                Integer budget = dbService.getAssigningListenerRetriesRemaining(serviceTaskId);
                remaining = (budget == null ? 0 : budget) - 1;
                dbService.setAssigningListenerRetriesRemaining(serviceTaskId, remaining);
            }
            if (remaining > 0) {
                log.info("{}/{}: User task assigning listener {} failed ({} retries left), re-dispatching: {}",
                    activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                return true;
            }
            log.info("{}/{}: User task assigning listener {} failed, retries exhausted — raising incident: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
            dbService.errorActivity(serviceTaskId);
            dbService.createIncident(serviceTaskId, message);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: updating-listener fail budget of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean failUpdatingListener(UUID serviceTaskId, Integer retries, String message,
            Activity activity, BpmnElementModel failElement) {
        if (!elementSupport.userTaskUpdatingListeners(failElement).isEmpty()
            && dbService.getPendingUpdatingListenerIndex(serviceTaskId) != null) {
            int remaining;
            if (retries != null) {
                dbService.setUpdatingListenerRetriesRemaining(serviceTaskId, retries);
                remaining = retries;
            } else {
                Integer budget = dbService.getUpdatingListenerRetriesRemaining(serviceTaskId);
                remaining = (budget == null ? 0 : budget) - 1;
                dbService.setUpdatingListenerRetriesRemaining(serviceTaskId, remaining);
            }
            if (remaining > 0) {
                log.info("{}/{}: User task updating listener {} failed ({} retries left), re-dispatching: {}",
                    activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                return true;
            }
            log.info("{}/{}: User task updating listener {} failed, retries exhausted — raising incident: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
            dbService.errorActivity(serviceTaskId);
            dbService.createIncident(serviceTaskId, message);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: canceling-listener fail budget of {@link #failServiceTask}.
     * Verbatim block, mechanical plumbing. Returns true when handled.
     */
    private boolean failCancelingListener(UUID serviceTaskId, Integer retries, String message,
            Activity activity, BpmnElementModel failElement) {
        if (!elementSupport.userTaskCancelingListeners(failElement).isEmpty()
            && dbService.getPendingCancelingListenerIndex(serviceTaskId) != null) {
            int remaining;
            if (retries != null) {
                dbService.setCancelingListenerRetriesRemaining(serviceTaskId, retries);
                remaining = retries;
            } else {
                Integer budget = dbService.getCancelingListenerRetriesRemaining(serviceTaskId);
                remaining = (budget == null ? 0 : budget) - 1;
                dbService.setCancelingListenerRetriesRemaining(serviceTaskId, remaining);
            }
            if (remaining > 0) {
                log.info("{}/{}: User task canceling listener {} failed ({} retries left), re-dispatching: {}",
                    activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
                serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
                return true;
            }
            log.info("{}/{}: User task canceling listener {} failed, retries exhausted — raising incident: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
            dbService.errorActivity(serviceTaskId);
            dbService.createIncident(serviceTaskId, message);
            return true;
        }
        return false;
    }

    /**
     * WO-DEBT-6 S2: shared retry-budget tail of {@link #failServiceTask} — Camunda failJob
     * semantics, re-dispatch while retries remain, incident when exhausted. Verbatim block
     * (void, zero-touch); called last.
     */
    private void failSharedBudget(UUID serviceTaskId, Integer retries, String message, Activity activity,
            TokenExecutor executor) {
        // Camunda failJob semantics: an explicit retries value sets the budget (0 -> incident now); otherwise -1
        int remaining;
        if (retries != null) {
            dbService.setServiceTaskRetries(serviceTaskId, retries);
            remaining = retries;
        } else {
            remaining = dbService.decrementServiceTaskRetries(serviceTaskId);
        }
        if (remaining > 0) {
            // retries left: re-dispatch the same job to a worker (the activity stays CREATED)
            log.info("{}/{}: Service task {} failed ({} retries left), re-dispatching: {}",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), remaining, message);
            serviceTaskEnqueueService.enqueueAfterCommit(serviceTaskId);
            return;
        }
        // retries exhausted: park the token and raise an incident carrying the worker's error message
        log.info("{}/{}: Service task {} failed, retries exhausted — raising incident: {}",
            activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), message);
        dbService.errorActivity(serviceTaskId);
        dbService.createIncident(serviceTaskId, message);
        // WO-C8-34 red-team B2: a compensation handler that exhausted its retries must
        // not leave its waitForCompletion thrower parked forever — ERROR + incident IS
        // the outcome, the operator sees it, the flow has to be released. failServiceTask
        // is not a completion tail, so the resume is requested here with this path's own
        // executor (a parameter, not a thread-local) and its own verdict passed in explicitly.
        if (executor != null) {
            resumeParkedCompensationThrowers(activity.getProcessInstanceId(), executor,
                java.util.Set.of(activity.getBpmnElementId()));
            // WO-C8-35 (CR-09, ШАГ 3/B2): the failed task is dead — if it was the last
            // deliverer of a parked inclusive-join, that join must be re-evaluated here too.
            ProcessInstance failedPi = dbService.getProcessInstance(activity.getProcessInstanceId());
            inclusiveGatewayHandler.resumeParkedInclusiveJoins(activity.getProcessInstanceId(),
                activity.getToken(),
                bpmnService.getProcessDefinitionModelById(failedPi.getProcessDefinitionId()), executor);
        }
    }

    /**
     * Resumes a token parked at a wait state (intermediate/message/timer catch event):
     * applies the given variables, completes the waiting activity and follows its outgoing flows.
     * Called by the timer scheduler and message-correlation subsystems.
     */
    public void signal(UUID activityId, List<ProcessVariable> variables, TokenExecutor executor) {
        // WO-REL-63: instance-lock ПЕРВЫМ (единый порядок захвата, см.
        // ElementSupport.lockInstanceFirst). Этот путь — ровно то, что зовёт
        // TimerJobExecutor.fire для промежуточного таймера; без instance-lock
        // первым он держал activity-lock и затем просил instance-row-lock в
        // completeProcessInstance — ABBA с отменой, воспроизведено на реальном
        // PG в Rel63RemainingAbbaDeadlockPgIT (5/5 раундов до фикса).
        Activity activity = elementSupport.lockInstanceFirst(activityId);
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            // only an active waiting element may be resumed — ignore a timer that fired twice, a
            // concurrently-correlated message, or an element superseded by incident-resolve (ERROR)
            log.info("Ignoring signal of {} in status {}", activityId, activity.getStatus());
            return;
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID tokenId = activity.getToken();

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(processInstanceId, variables);
        }
        dbService.completeActivity(activityId);

        log.info("{}/{}: Signalling {}: {}/{}", processInstanceId, tokenId, activity.getType(), activityId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        if (isBehindEventBasedGateway(bpmn, bpmnElement)) {
            // event-based gateway race: this catch won. Cancel the losing sibling catches still parked
            // on this token (the winner is already COMPLETED, so it is not cancelled). Any later trigger
            // for a cancelled sibling is ignored by the status guard above.
            dbService.cancelActiveActivitiesForToken(tokenId);
            log.info("{}/{}: event-based gateway: {} won, losing siblings cancelled", processInstanceId, tokenId, bpmnElement.getId());
        }

        flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, executor);
        triggerConditionalEvents(processInstanceId, executor);
    }

    /**
     * Re-evaluates the instance's conditional events after a variable change: any parked conditional catch
     * whose condition is now true is signalled, and any conditional boundary on an active host whose
     * condition is now true is fired. A thread-local guard stops a fired event's own continuation (which
     * runs through {@link #signal}/{@link EventTrigger#fireBoundary}) from recursively re-triggering this pass.
     *
     * <p>WO-C8-29: subscriptions declaring {@code zeebe:conditionalFilter} are re-evaluated
     * only when a recorded change matches the filter (see {@code ConditionalFilter});
     * subscriptions without a filter — and passes with no recorded change on this thread
     * (trigger without a preceding write) — evaluate exactly as before.
     */
    public void triggerConditionalEvents(UUID processInstanceId, TokenExecutor executor) {
        if (executionContext.isEvaluatingConditionals()) {
            return;
        }
        executionContext.setEvaluatingConditionals(true);
        try {
            ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
            if (pi.getCompletedAt() != null) {
                return;
            }
            BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());
            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            Map<String, String> changes = executionContext.consumeVariableChanges();
            for (Activity activity : dbService.getActiveActivities(processInstanceId)) {
                BpmnElementModel element = bpmn.getElement(activity.getBpmnElementId());
                if (element == null) {
                    continue;
                }
                if (element.getType() == BpmnElementType.CONDITIONAL_CATCH_EVENT) {
                    if (!conditionalFilterMatches(element, changes)) {
                        continue;
                    }
                    if (eventTrigger.conditionHolds(element, variables)) {
                        log.info("{}: Conditional catch {} satisfied, firing", processInstanceId, element.getId());
                        signal(activity.getId(), List.of(), executor);
                    }
                } else {
                    for (BpmnElementModel boundary : eventTrigger.findConditionalBoundaries(bpmn, element.getId())) {
                        if (!conditionalFilterMatches(boundary, changes)) {
                            continue;
                        }
                        if (eventTrigger.conditionHolds(boundary, variables)) {
                            log.info("{}: Conditional boundary {} satisfied, firing on host {}", processInstanceId, boundary.getId(), element.getId());
                            if (eventTrigger.fireBoundary(activity.getId(), boundary.getId(), List.of(), executor)) {
                                triggerConditionalEvents(processInstanceId, executor);
                            }
                        }
                    }
                }
            }
        } finally {
            executionContext.setEvaluatingConditionals(false);
        }
    }

    /**
     * WO-C8-29: consults the resolved {@code zeebe:conditionalFilter} of a conditional
     * element against the changes recorded since the last trigger pass. No filter, or
     * no recorded changes, means evaluate (current behavior — fail-open, never skips).
     */
    private boolean conditionalFilterMatches(BpmnElementModel element, Map<String, String> changes) {
        var filter = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getConditionalFilter)
            .orElse(null);
        return filter == null || filter.matches(changes);
    }
}
