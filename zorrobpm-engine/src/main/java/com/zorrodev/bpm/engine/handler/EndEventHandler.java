package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handlers for terminal end-event BPMN elements.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 * Each inner class handles one element type and registers via {@link TypedElementHandler}.
 */
@Slf4j
@Component
public class EndEventHandler {

    @Component
    @RequiredArgsConstructor
    public static class EndEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final FlowNavigator flowNavigator;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.END_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", ctx.processInstanceId(), ctx.tokenId(), el.getType(), activityId, el.getId());
            // WO-C8-37 (C37-1): a plain end inside a LIVE ad-hoc scope is a chain-end
            // arrival, not a scope exit — the ad-hoc join owns completion (counter +
            // quiescence) and consumes the branch when the scope actually finished.
            // A scope that just finished routes the END THROUGH the join (arrival
            // recorded, SCOPE_FINISHED consumed) so the join's incoming-token
            // continuation — not a scope-token re-entry — carries the branch.
            // Otherwise (no live ad-hoc scope on this token — the common linear
            // case, and any end on a scope token whose scope already closed) the
            // historical finishBranch path ends the branch/instance, exactly as before.
            Token token = dbService.findToken(ctx.tokenId()).orElse(null);
            if (token != null && token.getScopeActivityId() != null) {
                Activity scope = dbService.getActivity(token.getScopeActivityId());
                if (scope != null
                    && scope.getType() == BpmnElementType.AD_HOC_SUB_PROCESS
                    && (scope.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED
                        || scope.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.IN_PROGRESS)) {
                    if (flowNavigator.handleAdHocArrival(ctx.processInstanceId(), ctx.tokenId(), bpmn, el, ctx.executor())
                        == ArrivalOutcome.SCOPE_FINISHED) {
                        return;
                    }
                    // Live scope, join not finished: the end element's own chain is
                    // over — park silently (the scope join will consume the scope
                    // when its counter/quiescence says done). Flowing into
                    // finishBranch on the scope token would mis-close the scope
                    // (SUB-path completes the container off the join's control).
                    return;
                }
            }
            flowNavigator.finishBranch(ctx.processInstanceId(), ctx.tokenId(), bpmn, ctx.executor());
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class TerminateEndEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final FlowNavigator flowNavigator;
        private final ElementSupport elementSupport;
        private final ScopeContainment scopeContainment;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.TERMINATE_END_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Terminating instance at {}: {}/{}", ctx.processInstanceId(), ctx.tokenId(), el.getType(), activityId, el.getId());
            // WO-C8-34 (CR-02): terminate is scope-confined. In the ROOT scope the
            // whole instance ends, as before. Inside an embedded subprocess only
            // this scope's activities are cancelled and the subprocess container
            // is closed, then the parent flow continues from its outgoing flows —
            // parallel branches OUTSIDE the scope survive (external review repro).
            Token token = dbService.findToken(ctx.tokenId()).orElse(null);
            if (token == null || token.getScopeActivityId() == null) {
                dbService.cancelActiveActivities(ctx.processInstanceId());
                // WO-C8-49: terminate — это kill (как cancel), а не нормальное
                // завершение: гасим ERROR-активности и закрываем открытые
                // инциденты ДО completedAt. Нормальный end event так НЕ делает —
                // там ERROR падшего compensation-хендлера — видимый результат
                // (WO-C8-34), и финализация на completeProcessInstance была
                // регрессией (maintainer HOLD раунда 2).
                dbService.finalizeFinishedInstance(ctx.processInstanceId());
                dbService.completeProcessInstance(ctx.processInstanceId());
                return;
            }
            UUID scopeActivityId = token.getScopeActivityId();
            List<Activity> inScope = elementSupport.filterActivitiesInScope(
                ctx.processInstanceId(), dbService.getActiveActivities(ctx.processInstanceId()), scopeActivityId);
            // WO-C8-40: terminate внутри инстанса MI-подпроцесса гасит СОСЕДНИЕ
            // инстансы того же MI-контейнера и их внутренности: иначе сосед висит
            // CREATED без scope, процесс RUNNING навсегда (критерий 8).
            // Соседи — строки с тем же bpmnElementId в инстансе (входящий токен
            // контейнера у них общий); внутренности каждого соседа — по его
            // scope-цепочке (тот же обход, что ElementSupport.enclosingScopeChain).
            // Обычный scope — как раньше, плюс MI-соседи.
            Activity scope = dbService.getActivity(scopeActivityId);
            inScope = new java.util.ArrayList<>(inScope);
            BpmnElementModel scopeElement = scope == null ? null
                : bpmn.getElement(scope.getBpmnElementId());
            if (scopeElement != null && scopeElement.getExtensions() != null
                && scopeElement.getExtensions().getMultiInstanceExtension() != null) {
                UUID containerToken = scope.getToken();
                java.util.Set<UUID> miScopes = new java.util.HashSet<>();
                miScopes.add(scopeActivityId);
                for (Activity sibling : dbService.getActiveActivities(ctx.processInstanceId())) {
                    if (sibling == null || sibling.getId() == null
                        || sibling.getId().equals(scopeActivityId)) {
                        continue;
                    }
                    if (scope.getBpmnElementId().equals(sibling.getBpmnElementId())
                        && containerToken != null && containerToken.equals(sibling.getToken())) {
                        miScopes.add(sibling.getId());
                        if (inScope.stream().noneMatch(a -> a.getId().equals(sibling.getId()))) {
                            inScope.add(sibling);
                        }
                    }
                }
                // Внутренности соседних инстансов: scope-цепочка содержит их контейнер.
                for (Activity live : dbService.getActiveActivities(ctx.processInstanceId())) {
                    if (live == null || live.getId() == null
                        || inScope.stream().anyMatch(a -> a.getId().equals(live.getId()))) {
                        continue;
                    }
                    if (chainContains(ctx.processInstanceId(), live, miScopes)
                        && !live.getId().equals(scopeActivityId)
                        && !miScopes.contains(live.getId())) {
                        inScope.add(live);
                    }
                }
            }
            for (Activity active : inScope) {
                dbService.cancelActivity(active.getId());
            }
            // WO-C8-41 (F5, oracle S7-2): terminate гасит MI-тело целиком —
            // предсозданный outputCollection не выходит наружу (entry token =
            // токен scope-строки; только собственные переменные тела).
            if (scopeElement != null && scopeElement.getExtensions() != null
                && scopeElement.getExtensions().getMultiInstanceExtension() != null
                && scope != null && scope.getToken() != null) {
                elementSupport.deleteMiBodyState(ctx.processInstanceId(), scope.getToken(),
                    scopeElement);
            }
            // WO-C8-37 (C37-4): terminate closes the scope like a normal exit —
            // the container's output mappings are promoted to root and the scope
            // locals are dropped, exactly as FlowNavigator.finishBranch does
            // (same gate: SUB_PROCESS with an ioMapping extension; otherwise zero
            // behaviour change). Row status stays CANCELLED (terminate semantic).
            BpmnElementModel scopeContainerElement = bpmn.getElement(scope.getBpmnElementId());
            if (scopeContainerElement != null
                && scopeContainerElement.getType() == BpmnElementType.SUB_PROCESS
                && scopeContainerElement.getExtensions() != null
                && scopeContainerElement.getExtensions().getIoMappingExtension() != null) {
                elementSupport.applyIoMappings(ctx.processInstanceId(), scopeActivityId, scopeContainerElement, false);
                dbService.deleteVariables(ctx.processInstanceId(), scopeActivityId);
            }
            dbService.cancelActivity(scopeActivityId);
            // WO-C8-38 (C38-2): чистка arrived-строк join'ов ВНУТРИ отменяемого scope —
            // та же транзакция, без резюма (воскрешать join отменённого scope = выполнить
            // хвост отменённого потока, контрпример раунда 4).
            dbService.clearParallelGatewayArrivalsInJoins(ctx.processInstanceId(),
                scopeContainment.inclusiveGatewayIdsInsideScope(bpmn, scope.getBpmnElementId()));
            flowNavigator.proceedToOutgoing(ctx.processInstanceId(), token.getParentId(), bpmn, scopeContainerElement, ctx.executor());
        }

        /**
         * WO-C8-40: scope-цепочка активности содержит один из scopeId — тот же обход
         * токенов, что {@code ElementSupport.enclosingScopeChain} (локальная копия:
         * этот класс — static handler-группа без ElementSupport в части конструкторов).
         */
        private boolean chainContains(UUID processInstanceId, Activity activity, java.util.Set<UUID> scopeIds) {
            java.util.Optional<Token> token = dbService.findToken(activity.getToken());
            while (token.isPresent()) {
                UUID scopeId = token.get().getScopeActivityId();
                if (scopeId != null && scopeIds.contains(scopeId)) {
                    return true;
                }
                UUID parentId = token.get().getParentId();
                token = parentId == null ? java.util.Optional.empty() : dbService.findToken(parentId);
            }
            return false;
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class ErrorEndEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final ActivityService activityService;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.ERROR_END_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);

            String errorCode = Optional.ofNullable(el.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getCode)
                .orElse(null);

            log.info("{}/{}: Error end {} thrown (code={}) at {}", ctx.processInstanceId(), ctx.tokenId(), el.getId(), errorCode, activityId);

            // WO-AUDIT-11 (F-01): сначала БРОСОК, потом решение по статусу элемента.
            // Раньше элемент завершался (COMPLETED) ДО throwError, и тогда:
            //   — errorActivity (условный переход, WO-ENG-23) отказывался переворачивать
            //     терминальную строку («row is not active — refusing to flip it to ERROR»);
            //   — инцидент уезжал на COMPLETED-активити;
            //   — resolveIncident видел COMPLETED и закрывал инцидент БЕЗ re-execution
            //     (IncidentService: «COMPLETED → closing incident without re-execution»);
            //   — итог: инстанс RUNNING без единой активной активити и без единого
            //     элемента, на который можно повлиять, — навсегда.
            // Живой Zeebe 8.5 (оракул, docs/analysis/c8-oracle/scenarios/a11-errend-unhandled):
            // необработанная ошибка поднимает ИНЦИДЕНТ на элементе (+1 created / +1 pending
            // в счётчиках брокера), инстанс остаётся живым, поток на элементе стоит.
            // Тот же контракт, что у ручного броска из service task (throwServiceTaskError):
            // не поймали → элемент в ERROR + открытый инцидент.
            boolean handled = activityService.throwError(ctx.processInstanceId(), ctx.tokenId(), errorCode);
            if (handled) {
                // Граница (или event-subprocess) поймала ошибку: элемент отработал — его
                // завершаем (как раньше: COMPLETED). Гашение скоупа при этом уже прошло
                // внутри throwError и эту строку не тронуло (оно гасит только CREATED/IN_PROGRESS).
                dbService.completeActivity(activityId);
            } else {
                // Никто не поймал: элемент паркуется в ERROR, на него — открытый инцидент.
                // resolve такого инцидента ПЕРЕИСПОЛНЯЕТ элемент (а не закрывает молча).
                dbService.errorActivity(activityId);
                dbService.createIncident(activityId, "Unhandled BPMN error" + (errorCode != null ? " '" + errorCode + "'" : ""));
            }
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class EscalationEndEvent implements ElementHandler, TypedElementHandler {
        private final DBService dbService;
        private final ActivityService activityService;

        @Override
        public BpmnElementType elementType() { return BpmnElementType.ESCALATION_END_EVENT; }

        @Override
        public ElementHandler handler() { return this; }

        @Override
        public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel el) {
            UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), el);
            dbService.completeActivity(activityId);

            String escalationCode = activityService.escalationCode(el);
            log.info("{}/{}: Escalation end {} thrown (code={}) at {}", ctx.processInstanceId(), ctx.tokenId(), el.getId(), escalationCode, activityId);

            boolean interrupted = activityService.throwEscalation(ctx.processInstanceId(), ctx.tokenId(), escalationCode);
            if (!interrupted) {
                activityService.finishBranch(ctx.processInstanceId(), ctx.tokenId(), bpmn);
            }
        }
    }
}
