package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnConditionExpressionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ExclusiveGatewayExtensionModel;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.bpmn.model.BusinessRuleExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ScriptTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.dto.SignalSubscription;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.handler.ElementHandler;
import com.zorrodev.bpm.engine.handler.ExecutionContext;
import com.zorrodev.bpm.engine.handler.ExecutionCtx;
import com.zorrodev.bpm.engine.handler.FlowNavigator;
import com.zorrodev.bpm.engine.handler.HandlerRegistry;
import com.zorrodev.bpm.engine.handler.TokenExecutor;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import org.camunda.feel.api.FeelEngineApi;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService, TokenExecutor {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ScriptService scriptService;
    private final DmnService dmnService;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final FeelEngineApi feelEngineApi;
    private final ExecutionContext executionContext;
    private final HandlerRegistry handlerRegistry;
    private final com.zorrodev.bpm.engine.handler.MultiInstanceExecutor multiInstanceExecutor;
    private final com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;
    private final com.zorrodev.bpm.engine.handler.BoundaryScheduler boundaryScheduler;
    private FlowNavigator flowNavigator;

    private Map<BpmnElementType, ElementHandler> handlers;

    @PostConstruct
    void init() {
        flowNavigator = new FlowNavigator(dbService, bpmnService, scriptService);
        handlers = createHandlers();
        // Ensure extracted handler beans are available in the handlers map for test compatibility.
        // In production, HandlerRegistry auto-discovers them; in tests (mocked registry), they must be
        // registered directly so the handlers-map fallback works.
        registerExtractedHandlerBeans();
    }

    private void registerExtractedHandlerBeans() {
        for (var bean : List.of(
            new com.zorrodev.bpm.engine.handler.ExclusiveGatewayHandler(dbService, flowNavigator),
            new com.zorrodev.bpm.engine.handler.EventBasedGatewayHandler(dbService, flowNavigator),
            new com.zorrodev.bpm.engine.handler.ParallelGatewayHandler(dbService, flowNavigator),
            new com.zorrodev.bpm.engine.handler.InclusiveGatewayHandler(dbService, flowNavigator, scriptService),
            // Catch event handlers (WO-AUD-13)
            new com.zorrodev.bpm.engine.handler.WaitStateHandler(dbService),
            new com.zorrodev.bpm.engine.handler.MessageCatchHandler(dbService, this),
            new com.zorrodev.bpm.engine.handler.TimerCatchHandler(dbService),
            new com.zorrodev.bpm.engine.handler.SignalCatchHandler(dbService),
            new com.zorrodev.bpm.engine.handler.ConditionalCatchHandler(dbService, flowNavigator, scriptService),
            // Throw event handlers (WO-AUD-14)
            new com.zorrodev.bpm.engine.handler.IntermediateThrowEventHandler(dbService, flowNavigator),
            new com.zorrodev.bpm.engine.handler.MessageThrowHandler(dbService, flowNavigator, this),
            new com.zorrodev.bpm.engine.handler.SignalThrowHandler(dbService, flowNavigator, this),
            new com.zorrodev.bpm.engine.handler.LinkThrowHandler(dbService, this),
            new com.zorrodev.bpm.engine.handler.SendTaskHandler(this,
                new com.zorrodev.bpm.engine.handler.MessageThrowHandler(dbService, flowNavigator, this)),
            // SubProcess + CallActivity (WO-AUD-15)
            new com.zorrodev.bpm.engine.handler.SubProcessHandler(dbService),
            new com.zorrodev.bpm.engine.handler.CallActivityHandler(dbService, this),
            // UserTask handler (WO-AUD-18)
            new com.zorrodev.bpm.engine.handler.UserTaskHandler(dbService, elementSupport, multiInstanceExecutor, boundaryScheduler)
        )) {
            handlers.putIfAbsent(bean.elementType(), bean.handler());
        }
        // RECEIVE_TASK uses the same handler as MESSAGE_CATCH_EVENT
        handlers.putIfAbsent(BpmnElementType.RECEIVE_TASK, handlers.get(BpmnElementType.MESSAGE_CATCH_EVENT));
    }

    private Map<BpmnElementType, ElementHandler> createHandlers() {
        Map<BpmnElementType, ElementHandler> map = new EnumMap<>(BpmnElementType.class);
        map.put(BpmnElementType.START_EVENT, (ctx, bpmn, el) -> {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", ctx.processInstanceId(), ctx.tokenId(), el.getType(), activityId, el.getId());
            proceedToOutgoing(ctx.processInstanceId(), ctx.tokenId(), bpmn, el);
        });
        // message/timer/signal start events behave like a plain start: complete and continue
        map.put(BpmnElementType.MESSAGE_START_EVENT, map.get(BpmnElementType.START_EVENT));
        map.put(BpmnElementType.TIMER_START_EVENT, map.get(BpmnElementType.START_EVENT));
        map.put(BpmnElementType.SIGNAL_START_EVENT, map.get(BpmnElementType.START_EVENT));
        map.put(BpmnElementType.END_EVENT, (ctx, bpmn, el) -> {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", ctx.processInstanceId(), ctx.tokenId(), el.getType(), activityId, el.getId());
            finishBranch(ctx.processInstanceId(), ctx.tokenId(), bpmn);
        });
        map.put(BpmnElementType.TERMINATE_END_EVENT, (ctx, bpmn, el) -> {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Terminating instance at {}: {}/{}", ctx.processInstanceId(), ctx.tokenId(), el.getType(), activityId, el.getId());
            dbService.cancelActiveActivities(ctx.processInstanceId());
            dbService.completeProcessInstance(ctx.processInstanceId());
        });
        map.put(BpmnElementType.ERROR_END_EVENT, (ctx, bpmn, el) -> {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            String errorCode = Optional.ofNullable(el.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getCode)
                .orElse(null);
            log.info("{}/{}: Error end {} thrown (code={}) at {}", ctx.processInstanceId(), ctx.tokenId(), el.getId(), errorCode, activityId);
            boolean handled = throwError(ctx.processInstanceId(), ctx.tokenId(), errorCode);
            if (!handled) {
                dbService.errorActivity(activityId);
                dbService.createIncident(activityId, "Unhandled BPMN error" + (errorCode != null ? " '" + errorCode + "'" : ""));
            }
        });
        map.put(BpmnElementType.ESCALATION_END_EVENT, (ctx, bpmn, el) -> {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            String escalationCode = escalationCode(el);
            log.info("{}/{}: Escalation end {} thrown (code={}) at {}", ctx.processInstanceId(), ctx.tokenId(), el.getId(), escalationCode, activityId);
            boolean interrupted = throwEscalation(ctx.processInstanceId(), ctx.tokenId(), escalationCode);
            if (!interrupted) {
                finishBranch(ctx.processInstanceId(), ctx.tokenId(), bpmn);
            }
        });
        map.put(BpmnElementType.ESCALATION_THROW_EVENT, (ctx, bpmn, el) -> {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            String escalationCode = escalationCode(el);
            log.info("{}/{}: Escalation throw {} (code={}) at {}", ctx.processInstanceId(), ctx.tokenId(), el.getId(), escalationCode, activityId);
            boolean interrupted = throwEscalation(ctx.processInstanceId(), ctx.tokenId(), escalationCode);
            if (!interrupted) {
                proceedToOutgoing(ctx.processInstanceId(), ctx.tokenId(), bpmn, el);
            }
        });
        map.put(BpmnElementType.SERVICE_TASK, (ctx, bpmn, el) -> enterServiceTask(ctx.processInstanceId(), ctx.tokenId(), el));
        map.put(BpmnElementType.SCRIPT_TASK, (ctx, bpmn, el) -> {
            UUID processInstanceId = ctx.processInstanceId();
            UUID tokenId = ctx.tokenId();
            boolean jobWorker = Optional.ofNullable(el.getExtensions())
                .map(BpmnElementExtensionModel::getServiceTaskExtension)
                .isPresent();
            if (jobWorker) {
                enterServiceTask(processInstanceId, tokenId, el);
                return;
            }
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, el);
            ScriptTaskExtensionModel ext = Optional.ofNullable(el.getExtensions())
                .map(BpmnElementExtensionModel::getScriptTaskExtension)
                .orElseThrow(() -> new IllegalStateException("Script task '" + el.getId() + "' has no script"));
            String script = ext.getScript();
            if (script == null || script.isBlank()) {
                throw new IllegalStateException("Script task '" + el.getId() + "' has an empty script");
            }
            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            Object result = scriptService.evaluateExpression(script, variables);
            log.info("{}/{}: Script task {}: {}/{} evaluated to {}", processInstanceId, tokenId, el.getType(), activityId, el.getId(), result);
            String resultVariable = ext.getResultVariable();
            if (resultVariable != null && !resultVariable.isBlank()) {
                dbService.setVariables(processInstanceId, List.of(elementSupport.toProcessVariable(resultVariable, result)));
            }
            dbService.completeActivity(activityId);
            proceedToOutgoing(processInstanceId, tokenId, bpmn, el);
            triggerConditionalEvents(processInstanceId);
        });
        map.put(BpmnElementType.BUSINESS_RULE_TASK, (ctx, bpmn, el) -> {
            UUID processInstanceId = ctx.processInstanceId();
            UUID tokenId = ctx.tokenId();
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, el);
            BusinessRuleExtensionModel ext = Optional.ofNullable(el.getExtensions())
                .map(BpmnElementExtensionModel::getBusinessRuleExtension)
                .orElseThrow(() -> new IllegalStateException("Business rule task '" + el.getId() + "' has no decision or expression"));
            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            Object result;
            if (ext.getDecisionId() != null && !ext.getDecisionId().isBlank()) {
                result = dmnService.evaluate(ext.getDecisionId(), variables);
            } else if (ext.getExpression() != null && !ext.getExpression().isBlank()) {
                String expression = ext.getExpression().startsWith("=") ? ext.getExpression().substring(1) : ext.getExpression();
                result = scriptService.evaluateExpression(expression, variables);
            } else {
                throw new IllegalStateException("Business rule task '" + el.getId() + "' has neither a decision nor an expression");
            }
            log.info("{}/{}: Business rule task {}: {}/{} evaluated to {}", processInstanceId, tokenId, el.getType(), activityId, el.getId(), result);
            if (ext.getResultVariable() != null && !ext.getResultVariable().isBlank()) {
                dbService.setVariables(processInstanceId, List.of(elementSupport.toProcessVariable(ext.getResultVariable(), result)));
            }
            dbService.completeActivity(activityId);
            proceedToOutgoing(processInstanceId, tokenId, bpmn, el);
        });
        // Catch events are wait states: the token parks here until an external trigger
        // (timer fires / message correlated) resumes it via signal(...). Until the timer
        // and message subsystems land, these elements at least park cleanly with an active
        // activity instead of silently falling through to "Unsupported".
        map.put(BpmnElementType.LINK_CATCH_EVENT, map.get(BpmnElementType.START_EVENT));
        // Compensation throw runs the compensation handlers of completed compensation-bounded activities.
        map.put(BpmnElementType.COMPENSATION_THROW_EVENT, (ctx, bpmn, el) -> processCompensationThrow(ctx.processInstanceId(), ctx.tokenId(), bpmn, el));
        // Cancel end (inside a transaction) compensates the transaction and routes to its cancel boundary.
        map.put(BpmnElementType.CANCEL_END_EVENT, (ctx, bpmn, el) -> processCancelEnd(ctx.processInstanceId(), ctx.tokenId(), bpmn, el));

        return map;
    }

    /**
     * Follows every outgoing sequence flow of {@code element} unconditionally and executes the
     * target of each. Shared "continue from here" step used by start events, completed tasks,
     * signalled wait states and parent continuation after a subprocess/call activity ends.
     */
    public void proceedToOutgoing(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, element, this);
    }

    /**
     * Compensation throw: completes its own activity, then runs the compensation handler of every completed
     * compensation-bounded activity in the instance, in reverse order, before continuing down its outgoing
     * flow. Order is by activity creation time descending — for a sequential flow this is the reverse of the
     * completion order, as BPMN requires. Each handler (off the main flow, linked by an {@code <association>})
     * is executed synchronously on the throw's token.
     *
     * <p>Scope: "compensate all in the process scope" via an intermediate compensation throw, with
     * synchronously-executable handlers. Targeted (activityRef) compensation, compensation end events and
     * compensation within a subprocess scope are not yet supported.
     */
    private void processCompensationThrow(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        // activityRef targets a single activity to compensate; null = compensate every completed activity
        String activityRef = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getReference)
            .orElse(null);
        log.info("{}/{}: Compensation throw {} at {} (target {})", processInstanceId, tokenId, bpmnElement.getId(), activityId, activityRef == null ? "all" : activityRef);

        List<Activity> targets = dbService.getCompletedActivities(processInstanceId);
        if (activityRef != null) {
            targets = targets.stream().filter(a -> activityRef.equals(a.getBpmnElementId())).toList();
        }
        runCompensation(processInstanceId, tokenId, bpmn, targets);

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    /**
     * Runs the compensation handler of every candidate activity that has a compensation boundary, in
     * reverse order (by activity creation time descending — the reverse of completion order for a
     * sequential flow). Each handler runs synchronously on {@code runToken}. Shared by the compensation
     * throw event and transaction cancellation.
     */
    private void runCompensation(UUID processInstanceId, UUID runToken, BpmnProcessDefinitionModel bpmn, List<Activity> candidates) {
        List<Activity> completed = new ArrayList<>(candidates);
        completed.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        for (Activity activity : completed) {
            BpmnElementModel boundary = findCompensationBoundary(bpmn, activity.getBpmnElementId());
            if (boundary == null) {
                continue;
            }
            String handlerId = Optional.ofNullable(boundary.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getCompensationHandlerId)
                .orElse(null);
            BpmnElementModel handler = handlerId == null ? null : bpmn.getElement(handlerId);
            if (handler == null) {
                continue;
            }
            log.info("{}/{}: Compensating {} via handler {}", processInstanceId, runToken, activity.getBpmnElementId(), handlerId);
            execute(processInstanceId, runToken, bpmn, handler);
        }
    }

    /**
     * Cancel end event inside a transaction: compensates the transaction's completed activities (in reverse
     * order), cancels the transaction scope, and continues from the transaction's cancel boundary. A cancel
     * end outside a transaction scope falls back to a plain end.
     *
     * <p>Scope: cancel end + cancel boundary on a top-level transaction sub-process. Cancel propagation
     * across nested transactions is not yet supported.
     */
    private void processCancelEnd(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        Token endToken = dbService.getToken(tokenId);
        if (endToken == null || endToken.getScopeActivityId() == null) {
            log.info("{}/{}: Cancel end {} outside a transaction scope, ending branch", processInstanceId, tokenId, bpmnElement.getId());
            finishBranch(processInstanceId, tokenId, bpmn);
            return;
        }

        UUID scopeActivityId = endToken.getScopeActivityId();
        Activity scope = dbService.getActivity(scopeActivityId);
        BpmnElementModel transaction = bpmn.getElement(scope.getBpmnElementId());
        UUID parentToken = endToken.getParentId();
        log.info("{}/{}: Cancel end {} cancelling transaction {}", processInstanceId, tokenId, bpmnElement.getId(), transaction.getId());

        // compensate the transaction's completed activities (those carried by this scope token)
        List<Activity> scopeCompleted = dbService.getCompletedActivities(processInstanceId).stream()
            .filter(a -> tokenId.equals(a.getToken()))
            .toList();
        runCompensation(processInstanceId, tokenId, bpmn, scopeCompleted);

        // cancel the transaction scope, then continue from the (interrupting) cancel boundary
        dbService.cancelActiveActivitiesForToken(tokenId);
        dbService.cancelActivity(scopeActivityId);

        BpmnElementModel cancelBoundary = findCancelBoundary(bpmn, transaction.getId());
        if (cancelBoundary != null) {
            proceedToOutgoing(processInstanceId, parentToken, bpmn, cancelBoundary);
        } else {
            log.warn("{}/{}: Transaction {} cancelled but has no cancel boundary", processInstanceId, tokenId, transaction.getId());
        }
    }

    /** Finds the cancel boundary attached to {@code hostId} (a transaction), or null if none. */
    private BpmnElementModel findCancelBoundary(BpmnProcessDefinitionModel bpmn, String hostId) {
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.CANCEL_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (hostId.equals(attached)) {
                return element;
            }
        }
        return null;
    }

    /** Finds the compensation boundary attached to {@code hostId}, or null if none. */
    private BpmnElementModel findCompensationBoundary(BpmnProcessDefinitionModel bpmn, String hostId) {
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.COMPENSATION_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (hostId.equals(attached)) {
                return element;
            }
        }
        return null;
    }

    /** Evaluates a conditional event's FEEL condition against the given variables (a leading {@code =} is stripped). */
    private boolean conditionHolds(BpmnElementModel element, List<ProcessVariable> variables) {
        String expression = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getExpression)
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new EngineException("Conditional event " + element.getId() + " has no condition"));
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        Object result = scriptService.evaluateScript(expression, variables);
        return Boolean.TRUE.equals(result);
    }

    /**
     * Re-evaluates the instance's conditional events after a variable change: any parked conditional catch
     * whose condition is now true is signalled, and any conditional boundary on an active host whose
     * condition is now true is fired. A thread-local guard stops a fired event's own continuation (which
     * runs through {@link #signal}/{@link #fireBoundary}) from recursively re-triggering this pass.
     */
    public void triggerConditionalEvents(UUID processInstanceId) {
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
            for (Activity activity : dbService.getActiveActivities(processInstanceId)) {
                BpmnElementModel element = bpmn.getElement(activity.getBpmnElementId());
                if (element == null) {
                    continue;
                }
                if (element.getType() == BpmnElementType.CONDITIONAL_CATCH_EVENT) {
                    if (conditionHolds(element, variables)) {
                        log.info("{}: Conditional catch {} satisfied, firing", processInstanceId, element.getId());
                        signal(activity.getId(), List.of());
                    }
                } else {
                    for (BpmnElementModel boundary : findConditionalBoundaries(bpmn, element.getId())) {
                        if (conditionHolds(boundary, variables)) {
                            log.info("{}: Conditional boundary {} satisfied, firing on host {}", processInstanceId, boundary.getId(), element.getId());
                            fireBoundary(activity.getId(), boundary.getId(), List.of());
                        }
                    }
                }
            }
        } finally {
            executionContext.setEvaluatingConditionals(false);
        }
    }

    private List<BpmnElementModel> findConditionalBoundaries(BpmnProcessDefinitionModel bpmn, String hostId) {
        List<BpmnElementModel> boundaries = new ArrayList<>();
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.CONDITIONAL_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (hostId.equals(attached)) {
                boundaries.add(element);
            }
        }
        return boundaries;
    }

    /**
     * Parks the token at the failing element as an incident instead of propagating the exception
     * (which would roll back the whole process transaction). Marks the element's activity ERROR
     * and records an incident the operator can later resolve via
     * {@link #resolveIncident(UUID, List)}, which re-executes the element.
     */
    private void raiseIncident(UUID processInstanceId, UUID tokenId, BpmnElementModel element, Exception e) {
        String message = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        log.error("{}/{}: Incident at {} {}: {}", processInstanceId, tokenId, element.getType(), element.getId(), message, e);

        // the handler creates the element's activity before doing the risky work, so it is visible
        // to this same-transaction query; pick the most recent one for this token + element
        List<Activity> activities = dbService.getActivitiesByTokenAndBpmnElementId(tokenId, element.getId());
        if (activities.isEmpty()) {
            log.error("{}/{}: No activity found for failed element {}, incident not recorded", processInstanceId, tokenId, element.getId());
            return;
        }
        UUID activityId = activities.get(activities.size() - 1).getId();
        dbService.errorActivity(activityId);
        dbService.createIncident(activityId, message);
    }

    @Override
    public void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId) {
        ProcessInstance processInstanceEntity = dbService.getProcessInstance(processInstanceId);
        UUID processDefinitionId = processInstanceEntity.getProcessDefinitionId();
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnElementModel element = bpmn.getElement(bpmnElementId);

        execute(processInstanceId, tokenId, bpmn, element);
    }

    public void execute(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        if (element == null) {
            throw new IllegalStateException("Cannot execute a null element — a referenced element was not found in the process definition");
        }
        int depth = executionContext.enterDepth(element.getId(), processInstanceId);
        try {
            BpmnElementType type = element.getType();

            ElementHandler handler = handlerRegistry.get(type);
            if (handler == null) {
                handler = handlers.get(type);
            }
            if (handler == null) {
                // No handler for this element type: park the token as an incident instead of
                // silently dropping it (which would strand the process instance forever). An
                // operator can see the incident and decide how to proceed.
                log.error("{}/{}: Unsupported BpmnElementType {} at element {}, raising incident",
                    processInstanceId, tokenId, type, element.getId());
                UUID activityId = dbService.createActivity(processInstanceId, tokenId, element);
                dbService.errorActivity(activityId);
                dbService.createIncident(activityId, "Unsupported BPMN element type: " + type);
                return;
            }
            ExecutionCtx ctx = new ExecutionCtx(processInstanceId, tokenId, this, executionContext);
            try {
                handler.handle(ctx, bpmn, element);
            } catch (EngineException e) {
                // engine-level aborts (e.g. depth limit) propagate; they are not element failures
                throw e;
            } catch (Exception e) {
                // any element failure (bad FEEL result, variable conversion, service error, ...)
                // parks the token as an incident instead of rolling back the whole process
                raiseIncident(processInstanceId, tokenId, element, e);
            }
        } finally {
            executionContext.exitDepth(depth);
        }
    }



    /**
     * Event-based gateway: a pass-through that arms every outgoing catch event (message/timer/signal)
     * on the same token, letting them race. When the first one fires, {@link #signal} cancels the
     * losing siblings (see {@link #isBehindEventBasedGateway}).
     */

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

    public void enterServiceTask(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement) {
        if (multiInstanceExecutor.isMultiInstance(bpmnElement)) {
            multiInstanceExecutor.enter(processInstanceId, token, bpmnElement, this);
            return;
        }
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        dbService.createServiceTask(activityId, elementSupport.serviceTaskRetries(bpmnElement));
        elementSupport.applyIoMappings(processInstanceId, activityId, bpmnElement, true);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        serviceTaskEnqueueService.enqueueAfterCommit(activityId);
    }

    @Override
    public void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries) {
        Activity activity = lockAndReload(serviceTaskId);
        if (activity.getStatus() == ActivityStatus.COMPLETED || activity.getStatus() == ActivityStatus.CANCELLED) {
            // already finished (redelivered failure, or interrupted by a boundary) — ignore
            log.info("Ignoring failure of service task {} in status {}", serviceTaskId, activity.getStatus());
            return;
        }
        String message = (errorMessage == null || errorMessage.isBlank()) ? "Service task failed" : errorMessage;
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
    }

    /**
     * Reads the activity, takes a pessimistic write lock on its process instance, then re-reads the
     * activity under that lock. Serialises all execution touching one instance so concurrent async
     * branches cannot race on joins or double-advance a token; the re-read returns a status that is
     * consistent with the lock (a competing transaction has already committed by the time we hold it).
     */
    private Activity lockAndReload(UUID activityId) {
        Activity activity = dbService.getActivity(activityId);
        dbService.lockProcessInstance(activity.getProcessInstanceId());
        return dbService.getActivity(activityId);
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId, List<ProcessVariable> variables) {
        Activity activity = lockAndReload(serviceTaskId);
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            // only an active task may complete. Ignore anything else to avoid advancing the token twice:
            // a redelivered/late RabbitMQ completion (broker is at-least-once), a boundary-timer
            // interruption (CANCELLED), an already-COMPLETED task, or a task parked on an incident
            // (ERROR) that was superseded by incident-resolve re-execution.
            log.info("Ignoring completion of service task {} in status {}", serviceTaskId, activity.getStatus());
            return;
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID tokenId = activity.getToken();

        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(serviceTaskId);
        dbService.completeServiceTask(serviceTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, tokenId, activity.getType(), serviceTaskId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        elementSupport.applyIoMappings(processInstanceId, serviceTaskId, bpmnElement, false);
        multiInstanceExecutor.aggregateMultiInstanceOutput(processInstanceId, serviceTaskId, bpmnElement);
        dbService.deleteVariables(processInstanceId, serviceTaskId);
        if (multiInstanceExecutor.isMultiInstance(bpmnElement) && !multiInstanceExecutor.multiInstanceContinue(processInstanceId, tokenId, bpmnElement, serviceTaskId)) {
            // more instances are outstanding (parallel) or the next one was just started (sequential)
            return;
        }
        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
        triggerConditionalEvents(processInstanceId);
    }


    /**
     * Evaluates a message subscriber's correlation-key FEEL expression.
     */
    @Override
    public String evaluateCorrelationKey(BpmnElementModel element, UUID processInstanceId) {
        return elementSupport.evaluateCorrelationKey(element, processInstanceId);
    }

    @Override
    public void completeUserTask(UUID userTaskId, List<ProcessVariable> variables) {
        Activity activity = lockAndReload(userTaskId);
        if (activity.getStatus() != ActivityStatus.CREATED && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            // only an active task may complete — ignore a duplicate/late completion, a boundary-timer
            // interruption (CANCELLED) or a task superseded by incident-resolve (ERROR) to avoid double execution
            log.info("Ignoring completion of user task {} in status {}", userTaskId, activity.getStatus());
            return;
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID token = activity.getToken();

        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(userTaskId);
        dbService.completeUserTask(userTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, token, activity.getType(), userTaskId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        elementSupport.applyIoMappings(processInstanceId, userTaskId, bpmnElement, false);
        // multi-instance: append this instance's outputElement to the outputCollection before its scoped
        // variables (inputElement/loopCounter) are dropped
        multiInstanceExecutor.aggregateMultiInstanceOutput(processInstanceId, userTaskId, bpmnElement);
        dbService.deleteVariables(processInstanceId, userTaskId);
        if (multiInstanceExecutor.isMultiInstance(bpmnElement) && !multiInstanceExecutor.multiInstanceContinue(processInstanceId, token, bpmnElement, userTaskId)) {
            // more instances are outstanding (parallel) or the next one was just started (sequential)
            return;
        }
        proceedToOutgoing(processInstanceId, token, bpmn, bpmnElement);
        triggerConditionalEvents(processInstanceId);
    }

    @Override
    public void signal(UUID activityId, List<ProcessVariable> variables) {
        Activity activity = lockAndReload(activityId);
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

        proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement);
        triggerConditionalEvents(processInstanceId);
    }

    @Override
    public void fireBoundaryTimer(UUID hostActivityId, String boundaryElementId) {
        fireBoundary(hostActivityId, boundaryElementId, List.of());
    }

    @Override
    public void fireEventSubprocessTimer(UUID processInstanceId, String eventSubprocessId) {
        // the timer job is already marked fired (one-shot), so it never re-fires regardless of interrupting
        triggerEventSubprocess(processInstanceId, eventSubprocessId, List.of());
    }

    /**
     * Fires a boundary event (timer or message) on its host activity. Interrupting boundaries cancel
     * the host and continue the host's token from the boundary; non-interrupting ones leave the host
     * running and spawn a parallel branch on a new token. A no-op if the host already finished.
     */
    private void fireBoundary(UUID hostActivityId, String boundaryElementId, List<ProcessVariable> variables) {
        Activity host = lockAndReload(hostActivityId);
        if (host.getStatus() == ActivityStatus.COMPLETED || host.getStatus() == ActivityStatus.CANCELLED) {
            // host already finished before the boundary fired
            log.info("Boundary {} fired but host activity {} is {}, ignoring", boundaryElementId, hostActivityId, host.getStatus());
            return;
        }

        UUID processInstanceId = host.getProcessInstanceId();
        UUID tokenId = host.getToken();

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(processInstanceId, variables);
        }

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel boundary = bpmn.getElement(boundaryElementId);

        boolean interrupting = Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getBoundaryEventExtension)
            .map(BoundaryEventExtensionModel::isInterrupting)
            .orElse(true);

        if (interrupting) {
            dbService.cancelActivity(hostActivityId);
            log.info("{}/{}: Boundary {} interrupting host {}", processInstanceId, tokenId, boundaryElementId, host.getBpmnElementId());
            proceedToOutgoing(processInstanceId, tokenId, bpmn, boundary);
        } else {
            // non-interrupting: the host keeps running; the boundary spawns a parallel branch on a
            // new token (child of the host's token)
            Token branch = dbService.createToken(tokenId);
            log.info("{}/{}: Boundary {} firing non-interrupting on host {} (branch token {})", processInstanceId, tokenId, boundaryElementId, host.getBpmnElementId(), branch.getId());
            proceedToOutgoing(processInstanceId, branch.getId(), bpmn, boundary);
            rearmRepeatingBoundaryTimer(hostActivityId, boundary);
        }
        triggerConditionalEvents(processInstanceId);
    }

    /**
     * A repeating ({@code timeCycle} unbounded {@code R/<duration>} or cron) non-interrupting boundary timer
     * re-arms its next occurrence after firing, so it keeps firing while the host activity is active (a
     * "remind every N" pattern). When the host completes, the next firing finds it finished and is ignored.
     */
    private void rearmRepeatingBoundaryTimer(UUID hostActivityId, BpmnElementModel boundary) {
        if (boundary.getType() != BpmnElementType.BOUNDARY_TIMER_EVENT) {
            return;
        }
        TimerEventExtensionModel timer = Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null);
        if (timer == null || timer.getType() != com.zorrodev.bpm.engine.bpmn.model.TimerEventType.CYCLE) {
            return;
        }
        String expression = timer.getExpression();
        boolean infinite = com.zorrodev.bpm.engine.scheduler.TimerExpressions.isInfiniteCycle(expression);
        int repeatCount = com.zorrodev.bpm.engine.scheduler.TimerExpressions.repeatCount(expression);
        if (!infinite && repeatCount <= 0) {
            return; // one-shot or unsupported
        }
        Integer remaining = infinite ? null : repeatCount - 1;
        if (!infinite && remaining != null && remaining <= 0) {
            return; // done
        }
        dbService.createTimerJob(hostActivityId, elementSupport.computeDueAt(boundary), boundary.getId(), remaining);
    }

    @Override
    public void correlateMessage(String messageName, UUID processInstanceId, List<ProcessVariable> variables) {
        correlateMessage(messageName, null, processInstanceId, variables);
    }

    /**
     * Correlates a message, optionally by a correlation-key value. When {@code correlationKey} is given,
     * only subscriptions whose stored key matches are woken (targeted delivery — this disambiguates
     * multiple instances waiting on the same message name); otherwise the message correlates by name
     * (optionally narrowed to {@code processInstanceId}).
     */
    @Override
    public void correlateMessage(String messageName, String correlationKey, UUID processInstanceId, List<ProcessVariable> variables) {
        List<MessageSubscription> subscriptions;
        if (correlationKey != null) {
            subscriptions = dbService.findMessageSubscriptionsByKey(messageName, correlationKey);
            if (processInstanceId != null) {
                subscriptions = subscriptions.stream()
                    .filter(s -> processInstanceId.equals(s.getProcessInstanceId()))
                    .toList();
            }
        } else {
            subscriptions = dbService.findMessageSubscriptions(messageName, processInstanceId);
        }

        // untargeted correlation by name (no instance, no key) may also start new instances via message starts
        List<com.zorrodev.bpm.engine.dto.MessageStartSubscription> startSubscriptions =
            (processInstanceId == null && correlationKey == null) ? dbService.findMessageStartSubscriptions(messageName) : List.of();

        if (subscriptions.isEmpty() && startSubscriptions.isEmpty()) {
            log.info("No active subscription for message '{}' (instance {}, key {})", messageName, processInstanceId, correlationKey);
            return;
        }

        for (com.zorrodev.bpm.engine.dto.MessageStartSubscription start : startSubscriptions) {
            log.info("Message '{}' starting a new instance of {} at {}", messageName, start.getProcessDefinitionId(), start.getElementId());
            startProcessInstanceAt(null, start.getProcessDefinitionId(), start.getElementId(), variables);
        }

        for (MessageSubscription subscription : subscriptions) {
            if (subscription.getEventSubprocessId() != null) {
                // message-started event sub-process: start the handler within the subscribed instance. The
                // subscription is consumed only for interrupting handlers (a non-interrupting one keeps
                // listening and can fire again) — triggerEventSubprocess decides.
                log.info("Correlating message '{}' to event sub-process {} on instance {}", messageName, subscription.getEventSubprocessId(), subscription.getProcessInstanceId());
                boolean interrupting = triggerEventSubprocess(subscription.getProcessInstanceId(), subscription.getEventSubprocessId(), variables);
                if (interrupting) {
                    dbService.consumeMessageSubscription(subscription.getId());
                }
                continue;
            }
            dbService.consumeMessageSubscription(subscription.getId());
            if (subscription.getBoundaryElementId() != null) {
                // message boundary: fire the boundary (interrupt/non-interrupt the host)
                log.info("Correlating message '{}' to boundary {} on instance {} activity {}", messageName, subscription.getBoundaryElementId(), subscription.getProcessInstanceId(), subscription.getActivityId());
                fireBoundary(subscription.getActivityId(), subscription.getBoundaryElementId(), variables);
            } else {
                // message catch: signal the waiting activity
                log.info("Correlating message '{}' to instance {} activity {}", messageName, subscription.getProcessInstanceId(), subscription.getActivityId());
                signal(subscription.getActivityId(), variables);
            }
        }
    }

    /**
     * Starts a message-triggered event sub-process within {@code processInstanceId}. An interrupting event
     * sub-process consumes the subscription, cancels the instance's active activities (the main flow) and
     * runs the handler on a fresh token so its end event completes the instance. A non-interrupting one
     * keeps the subscription and the main flow, running the handler in its own subprocess scope (its end
     * completes only the scope). A no-op if the instance has already completed.
     *
     * <p>Scope: top-level, message-triggered event sub-processes. Non-message triggers and event
     * sub-processes nested inside an embedded subprocess are not yet supported.
     */
    private boolean triggerEventSubprocess(UUID processInstanceId, String eventSubprocessId, List<ProcessVariable> variables) {
        dbService.lockProcessInstance(processInstanceId);
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        if (pi.getCompletedAt() != null) {
            log.info("{}: Event sub-process {} trigger ignored, instance already completed", processInstanceId, eventSubprocessId);
            return false;
        }
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());
        BpmnElementModel eventSubProcess = bpmn.getElement(eventSubprocessId);
        SubProcessExtensionModel ext = Optional.ofNullable(eventSubProcess.getExtensions())
            .map(BpmnElementExtensionModel::getSubProcessExtension)
            .orElseThrow(() -> new EngineException("Event sub-process " + eventSubprocessId + " has no metadata"));

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(processInstanceId, variables);
        }

        if (ext.isInterrupting()) {
            // interrupting: cancel the main flow, then run the handler on a fresh (non-scope) token so its
            // end event completes the whole instance. The caller consumes the triggering subscription.
            dbService.cancelActiveActivities(processInstanceId);
            Token token = dbService.createToken(null);
            log.info("{}/{}: Interrupting event sub-process {} starting at {}", processInstanceId, token.getId(), eventSubprocessId, ext.getStartEventId());
            execute(processInstanceId, token.getId(), ext.getStartEventId());
            return true;
        }
        // non-interrupting: leave the subscription (it can fire again) and the main flow alone; run the
        // handler in its own subprocess scope so its end completes only the scope (see finishBranch),
        // not the instance
        Token branchToken = dbService.createToken(null);
        UUID containerActivityId = dbService.createActivity(processInstanceId, branchToken.getId(), eventSubProcess);
        Token scopeToken = dbService.createToken(branchToken.getId(), containerActivityId);
        log.info("{}/{}: Non-interrupting event sub-process {} starting at {} (scope {})", processInstanceId, scopeToken.getId(), eventSubprocessId, ext.getStartEventId(), containerActivityId);
        execute(processInstanceId, scopeToken.getId(), ext.getStartEventId());
        return false;
    }

    /**
     * Broadcasts a signal: wakes every active subscription with the matching name, across process
     * instances (1:N). Each waiting activity is signalled under its own per-instance lock (via
     * {@link #signal}), so concurrent broadcasts/correlations stay isolated per instance.
     */
    @Override
    public void broadcastSignal(String signalName, List<ProcessVariable> variables) {
        List<SignalSubscription> subscriptions = dbService.findSignalSubscriptions(signalName);
        List<com.zorrodev.bpm.engine.dto.SignalStartSubscription> startSubscriptions =
            dbService.findSignalStartSubscriptions(signalName);

        if (subscriptions.isEmpty() && startSubscriptions.isEmpty()) {
            log.info("No active subscription for signal '{}'", signalName);
            return;
        }

        // signal start: every subscribed definition starts a fresh instance (broadcast)
        for (com.zorrodev.bpm.engine.dto.SignalStartSubscription start : startSubscriptions) {
            log.info("Signal '{}' starting a new instance of {} at {}", signalName, start.getProcessDefinitionId(), start.getElementId());
            startProcessInstanceAt(null, start.getProcessDefinitionId(), start.getElementId(), variables);
        }

        for (SignalSubscription subscription : subscriptions) {
            if (subscription.getEventSubprocessId() != null) {
                // signal-started event sub-process: consume only for interrupting handlers (it can re-fire otherwise)
                log.info("Broadcasting signal '{}' to event sub-process {} on instance {}", signalName, subscription.getEventSubprocessId(), subscription.getProcessInstanceId());
                boolean interrupting = triggerEventSubprocess(subscription.getProcessInstanceId(), subscription.getEventSubprocessId(), variables);
                if (interrupting) {
                    dbService.consumeSignalSubscription(subscription.getId());
                }
                continue;
            }
            dbService.consumeSignalSubscription(subscription.getId());
            if (subscription.getBoundaryElementId() != null) {
                // signal boundary: fire the boundary (interrupt/non-interrupt the host)
                log.info("Broadcasting signal '{}' to boundary {} on instance {} activity {}", signalName, subscription.getBoundaryElementId(), subscription.getProcessInstanceId(), subscription.getActivityId());
                fireBoundary(subscription.getActivityId(), subscription.getBoundaryElementId(), variables);
            } else {
                // signal catch: signal the waiting activity
                log.info("Broadcasting signal '{}' to instance {} activity {}", signalName, subscription.getProcessInstanceId(), subscription.getActivityId());
                signal(subscription.getActivityId(), variables);
            }
        }
    }

    @Override
    public void resolveIncident(UUID incidentId, List<ProcessVariable> variables) {
        Incident incident = dbService.getIncident(incidentId);
        Activity activity = dbService.getActivity(incident.getActivityId());
        dbService.lockProcessInstance(activity.getProcessInstanceId());

        // Idempotency: already resolved → no-op
        if (incident.getCompletedAt() != null) {
            log.info("{}/{}: Incident {} already resolved, no-op", activity.getProcessInstanceId(), activity.getToken(), incidentId);
            return;
        }

        // Guard: if an active activity already exists for this (token, element), close incident without re-execution
        if (dbService.hasActiveActivityOnTokenAndElement(activity.getToken(), activity.getBpmnElementId())) {
            log.info("{}/{}: Active activity already exists for element {}, closing incident {} without re-execution",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), incidentId);
            dbService.completeIncident(incidentId);
            return;
        }

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(activity.getProcessInstanceId(), variables);
        }

        // Auto-close stale incidents for this (token, element) before re-execution
        List<Activity> sameElementActivities = dbService.getActivitiesByTokenAndBpmnElementId(activity.getToken(), activity.getBpmnElementId());
        List<UUID> staleActivityIds = sameElementActivities.stream().map(Activity::getId).toList();
        dbService.completeIncidentsByActivityIds(staleActivityIds);

        // Cancel the parked (ERROR) activity before re-executing: re-execution creates a fresh active
        // activity, and cancelling the old one ensures a late/duplicate worker completion of its in-flight
        // job is ignored (completeServiceTask only acts on active tasks) instead of advancing the token again.
        dbService.cancelActivity(incident.getActivityId());

        log.info("{}/{}: Resolving incident {} at {}: re-executing", activity.getProcessInstanceId(), activity.getToken(), incidentId, activity.getBpmnElementId());
        execute(activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId());
    }

    @Override
    public UUID startProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        if (bpmn.getStartEvent() == null) {
            throw new EngineException("Process definition " + processDefinitionId
                + " has no plain start event; it can only be started by a message or timer start event");
        }
        return startProcessInstanceAt(parentActivityId, processDefinitionId, bpmn.getStartEvent().getId(), variables);
    }

    @Override
    public UUID startProcessInstanceFromStartEvent(UUID processDefinitionId, String startElementId, List<ProcessVariable> variables) {
        return startProcessInstanceAt(null, processDefinitionId, startElementId, variables);
    }

    /**
     * Starts a process instance beginning at a specific start element (used by message/timer start
     * events, which begin at their own start node rather than the plain start).
     */
    private UUID startProcessInstanceAt(UUID parentActivityId, UUID processDefinitionId, String startEventId, List<ProcessVariable> variables) {
        UUID processInstanceId = dbService.createProcessInstance(parentActivityId, processDefinitionId, variables);

        dbService.setVariables(processInstanceId, variables);

        UUID parentTokenId = null;
        if (parentActivityId != null) {
            Activity activity = dbService.getActivity(parentActivityId);
            Token token = dbService.getToken(activity.getToken());
            parentTokenId = token.getId();
        }
        Token token = dbService.createToken(parentTokenId);

        // register triggers for event sub-processes before the main flow runs, so a message arriving
        // while the instance is active can start the handler
        subscribeEventSubprocesses(processInstanceId, processDefinitionId);

        execute(processInstanceId, token.getId(), startEventId);

        return processInstanceId;
    }

    /**
     * Registers an instance-scoped message subscription for every message-triggered event sub-process in
     * the definition. When such a message is later correlated to this instance the event sub-process is
     * started (see {@link #triggerEventSubprocess}). Non-message triggers are not yet supported.
     */
    private void subscribeEventSubprocesses(UUID processInstanceId, UUID processDefinitionId) {
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        for (BpmnElementModel element : bpmn.getEventSubProcesses()) {
            SubProcessExtensionModel ext = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getSubProcessExtension)
                .orElse(null);
            if (ext == null) {
                continue;
            }
            if (ext.getTriggerMessageName() != null) {
                dbService.createEventSubprocessMessageSubscription(processInstanceId, ext.getTriggerMessageName(), element.getId());
                log.info("{}: Event sub-process {} subscribed to message '{}'", processInstanceId, element.getId(), ext.getTriggerMessageName());
            } else if (ext.getTriggerSignalName() != null) {
                dbService.createEventSubprocessSignalSubscription(processInstanceId, ext.getTriggerSignalName(), element.getId());
                log.info("{}: Event sub-process {} subscribed to signal '{}'", processInstanceId, element.getId(), ext.getTriggerSignalName());
            } else if (ext.getTriggerTimer() != null) {
                Instant dueAt = elementSupport.computeDueAt(ext.getTriggerTimer(), element.getId());
                dbService.createEventSubprocessTimerJob(processInstanceId, dueAt, element.getId());
                log.info("{}: Event sub-process {} scheduled timer for {}", processInstanceId, element.getId(), dueAt);
            }
            // error-triggered event sub-processes need no subscription: they fire via throwError propagation
        }
    }

    public UUID processFlow(@NonNull UUID processInstanceId, @NonNull UUID tokenId, String flowId, @NonNull Boolean processExpression, Boolean defaultFlow) {
        return flowNavigator.processFlow(processInstanceId, tokenId, flowId, processExpression, defaultFlow);
    }

    /**
     * Ends the current branch at an end event: if the token is inside an embedded subprocess scope,
     * completes the container and continues the parent token from the subprocess's outgoing flows;
     * otherwise completes the process instance and continues the parent call activity (if any). Shared
     * by plain and escalation end events.
     */
    public void finishBranch(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn) {
        Token endToken = dbService.getToken(tokenId);
        if (endToken != null && endToken.getScopeActivityId() != null) {
            // end of an embedded subprocess scope: complete the container and continue the parent
            // token from the subprocess's outgoing flows; the process instance stays running
            UUID subProcessActivityId = endToken.getScopeActivityId();
            dbService.completeActivity(subProcessActivityId);
            Activity subProcessActivity = dbService.getActivity(subProcessActivityId);
            BpmnElementModel subProcessElement = bpmn.getElement(subProcessActivity.getBpmnElementId());
            UUID parentTokenId = endToken.getParentId();
            log.info("{}/{}: Completing {}: {}/{}", processInstanceId, parentTokenId, subProcessElement.getType(), subProcessActivityId, subProcessElement.getId());
            proceedToOutgoing(processInstanceId, parentTokenId, bpmn, subProcessElement);
            return;
        }

        dbService.completeProcessInstance(processInstanceId);

        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        UUID parentActivityId = pi.getParentActivityId();
        if (parentActivityId != null) {
            // a call activity finished: continuation mutates the *parent* instance, so lock it
            // (consistent child→parent ordering keeps this deadlock-free) before advancing it
            Activity parentActivity = dbService.getActivity(parentActivityId);
            dbService.lockProcessInstance(parentActivity.getProcessInstanceId());
            dbService.completeActivity(parentActivityId);

            UUID parentProcessInstanceId = parentActivity.getProcessInstanceId();
            ProcessInstance parentProcessInstance = dbService.getProcessInstance(parentActivity.getProcessInstanceId());
            UUID parentProcessDefinitionId = parentProcessInstance.getProcessDefinitionId();
            UUID parentToken = parentActivity.getToken();
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentProcessDefinitionId);
            BpmnElementModel parentBpmnElement = parentBpmn.getElement(parentActivity.getBpmnElementId());
            if (parentBpmnElement == null) {
                throw new IllegalStateException("Call activity element '" + parentActivity.getBpmnElementId() + "' not found in the parent process definition");
            }

            // Camunda 8: propagateAllChildVariables (default true) copies the child's variables up to the
            // parent; when explicitly false, the child's variables are not propagated.
            boolean propagate = Optional.ofNullable(parentBpmnElement)
                .map(BpmnElementModel::getExtensions)
                .map(BpmnElementExtensionModel::getCallActivityExtension)
                .map(ext -> ext.getPropagateAllChildVariables())
                .orElse(Boolean.TRUE);
            if (propagate) {
                dbService.setVariables(parentProcessInstanceId, dbService.getVariables(processInstanceId));
            }

            log.info("{}/{}: Completing {}: {}/{}", parentProcessInstanceId, parentToken, parentActivity.getType(), parentActivityId, parentActivity.getBpmnElementId());

            proceedToOutgoing(parentProcessInstanceId, parentToken, parentBpmn, parentBpmnElement);
        }
    }

    /**
     * Propagates a BPMN error from {@code tokenId} outward through the scope hierarchy, looking for
     * an interrupting error boundary that matches {@code errorCode} (a boundary without a code is a
     * catch-all). Search order: enclosing embedded subprocess scopes (innermost first), then — if the
     * instance is a called process — the call activity in the parent instance, recursively. When a
     * handler is found the interrupted scope is cancelled and flow continues from the boundary.
     *
     * @return {@code true} if an error boundary handled the error, {@code false} if it escaped unhandled.
     */
    public boolean throwError(UUID processInstanceId, UUID tokenId, String errorCode) {
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());

        // walk enclosing embedded-subprocess scopes, innermost first
        Token tok = dbService.getToken(tokenId);
        while (tok != null && tok.getScopeActivityId() != null) {
            Activity scope = dbService.getActivity(tok.getScopeActivityId());
            BpmnElementModel boundary = findErrorBoundary(bpmn, scope.getBpmnElementId(), errorCode);
            if (boundary != null) {
                dbService.cancelActiveActivitiesForToken(tok.getId());
                dbService.cancelActivity(scope.getId());
                log.info("{}: error '{}' caught by boundary {} on subprocess {}", processInstanceId, errorCode, boundary.getId(), scope.getBpmnElementId());
                proceedToOutgoing(processInstanceId, tok.getParentId(), bpmn, boundary);
                return true;
            }
            tok = tok.getParentId() != null ? dbService.getToken(tok.getParentId()) : null;
        }

        // a top-level error-triggered event sub-process handles the error here (interrupting the main flow)
        BpmnElementModel errorHandler = findEventSubprocessErrorHandler(bpmn, errorCode);
        if (errorHandler != null) {
            log.info("{}: error '{}' caught by event sub-process {}", processInstanceId, errorCode, errorHandler.getId());
            triggerEventSubprocess(processInstanceId, errorHandler.getId(), List.of());
            return true;
        }

        // reached the top of this instance: propagate to the parent instance via the call activity
        if (pi.getParentActivityId() != null) {
            Activity callActivity = dbService.getActivity(pi.getParentActivityId());
            UUID parentInstanceId = callActivity.getProcessInstanceId();
            dbService.lockProcessInstance(parentInstanceId);
            ProcessInstance parentPi = dbService.getProcessInstance(parentInstanceId);
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentPi.getProcessDefinitionId());
            BpmnElementModel boundary = findErrorBoundary(parentBpmn, callActivity.getBpmnElementId(), errorCode);

            // the error escapes this child instance regardless of whether the parent catches it
            dbService.cancelActiveActivities(processInstanceId);
            dbService.completeProcessInstance(processInstanceId);

            if (boundary != null) {
                dbService.cancelActivity(callActivity.getId());
                log.info("{}: error '{}' caught by boundary {} on call activity {}", parentInstanceId, errorCode, boundary.getId(), callActivity.getBpmnElementId());
                proceedToOutgoing(parentInstanceId, callActivity.getToken(), parentBpmn, boundary);
                return true;
            }
            // not caught on the call activity: keep propagating within the parent instance
            return throwError(parentInstanceId, callActivity.getToken(), errorCode);
        }

        return false;
    }

    /**
     * Finds an interrupting error boundary attached to {@code attachedToRef} whose error code matches
     * {@code errorCode}; a boundary with no code is a catch-all. Returns {@code null} if none.
     */
    private BpmnElementModel findErrorBoundary(BpmnProcessDefinitionModel bpmn, String attachedToRef, String errorCode) {
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.ERROR_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (!attachedToRef.equals(attached)) {
                continue;
            }
            String boundaryCode = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getCode)
                .orElse(null);
            if (boundaryCode == null || boundaryCode.equals(errorCode)) {
                return element;
            }
        }
        return null;
    }

    /** Finds a top-level error-triggered event sub-process matching {@code errorCode} (null code = catch-all). */
    private BpmnElementModel findEventSubprocessErrorHandler(BpmnProcessDefinitionModel bpmn, String errorCode) {
        for (BpmnElementModel element : bpmn.getEventSubProcesses()) {
            SubProcessExtensionModel ext = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getSubProcessExtension)
                .orElse(null);
            if (ext == null || !ext.isErrorTriggered()) {
                continue;
            }
            if (ext.getTriggerErrorCode() == null || ext.getTriggerErrorCode().equals(errorCode)) {
                return element;
            }
        }
        return null;
    }

    public String escalationCode(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getCode)
            .orElse(null);
    }

    /**
     * Propagates an escalation from {@code tokenId} outward through the scope hierarchy, looking for an
     * escalation boundary that matches {@code escalationCode} (a boundary without a code is a catch-all).
     * Mirrors {@link #throwError} but supports non-interrupting boundaries (the host scope keeps running
     * and a parallel branch is spawned) and never cancels the instance or raises an incident, because an
     * escalation is non-critical.
     *
     * @return {@code true} if an interrupting boundary on a scope enclosing {@code tokenId} fired
     *         (so the throwing path was cancelled and must not continue), {@code false} otherwise.
     */
    public boolean throwEscalation(UUID processInstanceId, UUID tokenId, String escalationCode) {
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());

        // walk enclosing embedded-subprocess scopes, innermost first
        Token tok = dbService.getToken(tokenId);
        while (tok != null && tok.getScopeActivityId() != null) {
            Activity scope = dbService.getActivity(tok.getScopeActivityId());
            BpmnElementModel boundary = findEscalationBoundary(bpmn, scope.getBpmnElementId(), escalationCode);
            if (boundary != null) {
                if (isInterrupting(boundary)) {
                    dbService.cancelActiveActivitiesForToken(tok.getId());
                    dbService.cancelActivity(scope.getId());
                    log.info("{}: escalation '{}' caught (interrupting) by boundary {} on subprocess {}", processInstanceId, escalationCode, boundary.getId(), scope.getBpmnElementId());
                    proceedToOutgoing(processInstanceId, tok.getParentId(), bpmn, boundary);
                    return true;
                }
                Token branch = dbService.createToken(tok.getParentId());
                log.info("{}: escalation '{}' caught (non-interrupting) by boundary {} on subprocess {} (branch token {})", processInstanceId, escalationCode, boundary.getId(), scope.getBpmnElementId(), branch.getId());
                proceedToOutgoing(processInstanceId, branch.getId(), bpmn, boundary);
                return false;
            }
            tok = tok.getParentId() != null ? dbService.getToken(tok.getParentId()) : null;
        }

        // reached the top of this instance: propagate to the parent instance via the call activity
        if (pi.getParentActivityId() != null) {
            Activity callActivity = dbService.getActivity(pi.getParentActivityId());
            UUID parentInstanceId = callActivity.getProcessInstanceId();
            dbService.lockProcessInstance(parentInstanceId);
            ProcessInstance parentPi = dbService.getProcessInstance(parentInstanceId);
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentPi.getProcessDefinitionId());
            BpmnElementModel boundary = findEscalationBoundary(parentBpmn, callActivity.getBpmnElementId(), escalationCode);

            if (boundary != null) {
                if (isInterrupting(boundary)) {
                    // interrupting escalation on a call activity cancels the child instance
                    dbService.cancelActiveActivities(processInstanceId);
                    dbService.completeProcessInstance(processInstanceId);
                    dbService.cancelActivity(callActivity.getId());
                    log.info("{}: escalation '{}' caught (interrupting) by boundary {} on call activity {}", parentInstanceId, escalationCode, boundary.getId(), callActivity.getBpmnElementId());
                    proceedToOutgoing(parentInstanceId, callActivity.getToken(), parentBpmn, boundary);
                    return true; // child instance was cancelled, so the throwing path is gone
                }
                // the child instance keeps running; a parallel branch is spawned in the parent
                Token branch = dbService.createToken(callActivity.getToken());
                log.info("{}: escalation '{}' caught (non-interrupting) by boundary {} on call activity {} (branch token {})", parentInstanceId, escalationCode, boundary.getId(), callActivity.getBpmnElementId(), branch.getId());
                proceedToOutgoing(parentInstanceId, branch.getId(), parentBpmn, boundary);
                return false;
            }
            // not caught on the call activity: keep propagating within the parent instance for handling,
            // but the child's throwing path is not interrupted by it
            throwEscalation(parentInstanceId, callActivity.getToken(), escalationCode);
            return false;
        }

        log.info("{}: escalation '{}' escaped unhandled (non-critical, ignored)", processInstanceId, escalationCode);
        return false;
    }

    private boolean isInterrupting(BpmnElementModel boundary) {
        return Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getBoundaryEventExtension)
            .map(BoundaryEventExtensionModel::isInterrupting)
            .orElse(true);
    }

    /**
     * Finds an escalation boundary attached to {@code attachedToRef} whose escalation code matches
     * {@code escalationCode}; a boundary with no code is a catch-all. Returns {@code null} if none.
     */
    private BpmnElementModel findEscalationBoundary(BpmnProcessDefinitionModel bpmn, String attachedToRef, String escalationCode) {
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.ESCALATION_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (!attachedToRef.equals(attached)) {
                continue;
            }
            String boundaryCode = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getCode)
                .orElse(null);
            if (boundaryCode == null || boundaryCode.equals(escalationCode)) {
                return element;
            }
        }
        return null;
    }

}
