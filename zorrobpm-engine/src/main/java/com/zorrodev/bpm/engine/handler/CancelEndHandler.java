package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler for CANCEL_END_EVENT elements (inside a transaction).
 * Compensates the transaction's completed activities, cancels the scope,
 * and continues from the transaction's cancel boundary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelEndHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final FlowNavigator flowNavigator;
    private final ActivityService activityService;
    private final CompensationThrowHandler compensationThrowHandler;
    private final ElementSupport elementSupport;
    /**
     * WO-C8-39: цепочка scope для cancel-конца на форк-токене (у него нет
     * {@code scopeActivityId}) + scope-фильтры компенсации/отмены (прецедент —
     * {@code ElementSupport.filterActivitiesInScope} в terminate-пути и
     * compensation-throw). Цикла нет: ElementSupport не зависит от хендлеров.
     */
    /**
     * WO-C8-38 (C38-2): containment для чистки arrived-строк join'ов ВНУТРИ
     * отменяемой транзакции (та же транзакция, без резюма — воскрешать join
     * отменённого scope = выполнить хвост отменённого потока, контрпример раунда 4).
     */
    private final ScopeContainment scopeContainment;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.CANCEL_END_EVENT; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        processCancelEnd(ctx.processInstanceId(), ctx.tokenId(), bpmn, bpmnElement, ctx.executor());
    }

    private void processCancelEnd(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement, TokenExecutor executor) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        Token endToken = dbService.getToken(tokenId);
        // WO-C8-39: cancel-конец на форк-ветви внутри транзакции несёт токен БЕЗ
        // scopeActivityId (форк: ParallelGatewayHandler.createToken(tokenId)) —
        // смотреть только свой токен здесь нельзя ("outside a transaction scope",
        // тихое no-op по отмене). Транзакция — ближайший scope-контейнер по
        // цепочке scope (тот же обход, что ElementSupport.enclosingScopeChain);
        // линейный и ad-hoc случаи дают тот же контейнер, что раньше.
        UUID scopeActivityId = resolveTransactionScope(processInstanceId, activityId, endToken);
        if (scopeActivityId == null) {
            log.info("{}/{}: Cancel end {} outside a transaction scope, ending branch", processInstanceId, tokenId, bpmnElement.getId());
            activityService.finishBranch(processInstanceId, tokenId, bpmn);
            return;
        }

        Activity scope = dbService.getActivity(scopeActivityId);
        BpmnElementModel transaction = bpmn.getElement(scope.getBpmnElementId());
        // Продолжение cancel-границы идёт ВНЕ отменённого scope: на родителе
        // scope-токена (в линейном случае — ровно endToken.getParentId(), как
        // раньше; на форк-ветви — корень, а не scope-токен отменённой транзакции).
        UUID parentToken = parentOfScopeToken(endToken, scopeActivityId);
        log.info("{}/{}: Cancel end {} cancelling transaction {}", processInstanceId, tokenId, bpmnElement.getId(), transaction.getId());

        // compensate the transaction scope's completed activities (BPMN 2.0: гасятся
        // ВСЕ активные исполнения scope, затем компенсация в scope транзакции —
        // CIB seven transaction-subprocess; фильтр — тот же scope-confined, что
        // terminate-путь и compensation-throw, а не равенство токена: иначе
        // соседи по форку не компенсируются и не отменяются).
        List<Activity> scopeCompleted = elementSupport.filterActivitiesInScope(
            processInstanceId, dbService.getCompletedActivities(processInstanceId), scopeActivityId);
        compensationThrowHandler.runCompensation(processInstanceId, tokenId, bpmn, scopeCompleted, executor);

        // cancel the transaction scope, then continue from the (interrupting) cancel boundary
        List<Activity> inScope = elementSupport.filterActivitiesInScope(
            processInstanceId, dbService.getActiveActivities(processInstanceId), scopeActivityId);
        for (Activity active : inScope) {
            dbService.cancelActivity(active.getId());
        }
        dbService.cancelActivity(scopeActivityId);
        // WO-C8-38 (C38-2): чистка arrived-строк join'ов ВНУТРИ отменяемой транзакции.
        dbService.clearParallelGatewayArrivalsInJoins(processInstanceId,
            scopeContainment.inclusiveGatewayIdsInsideScope(bpmn, transaction.getId()));

        BpmnElementModel cancelBoundary = findCancelBoundary(bpmn, transaction.getId());
        if (cancelBoundary != null) {
            flowNavigator.proceedToOutgoing(processInstanceId, parentToken, bpmn, cancelBoundary, executor);
        } else {
            log.warn("{}/{}: Transaction {} cancelled but has no cancel boundary", processInstanceId, tokenId, transaction.getId());
        }
    }

    /**
     * WO-C8-39: ближайший scope-контейнер cancel-конца по цепочке scope, или null
     * вне любого scope (корневой токен — прежнее тихое завершение ветви). Свой
     * токен со scope — как раньше; форк-токен без scope — innermost цепочки
     * (цепочка outermost-first, берём последний). Контейнер НЕ проверяется на
     * «транзакционность»: парсер маппит {@code <transaction>} в SUB_PROCESS,
     * различие стёрто, а ad-hoc уже принимается как transaction (прецедент C8-38).
     */
    private UUID resolveTransactionScope(UUID processInstanceId, UUID cancelActivityId, Token endToken) {
        if (endToken != null && endToken.getScopeActivityId() != null) {
            return endToken.getScopeActivityId();
        }
        List<UUID> chain = elementSupport.enclosingScopeChain(processInstanceId, cancelActivityId);
        if (chain.isEmpty()) {
            return null;
        }
        return chain.get(chain.size() - 1);
    }

    /**
     * WO-C8-39: родитель scope-токена отменённой транзакции — на нём продолжается
     * cancel-граница (вне отменённого scope). Scope-токен — первый токен вверх по
     * цепочке, несущий {@code scopeActivityId}; в линейном случае это сам
     * endToken, и результат совпадает со старым {@code endToken.getParentId()}.
     */
    private UUID parentOfScopeToken(Token endToken, UUID scopeActivityId) {
        Token cursor = endToken;
        while (cursor != null) {
            if (scopeActivityId.equals(cursor.getScopeActivityId())) {
                return cursor.getParentId();
            }
            cursor = cursor.getParentId() == null
                ? null
                : dbService.findToken(cursor.getParentId()).orElse(null);
        }
        return endToken == null ? null : endToken.getParentId();
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
}
