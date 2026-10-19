package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles propagation of BPMN errors and escalations through the scope hierarchy.
 * Extracted from ActivityServiceImpl (WO-AUD-23).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorEscalationThrower {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final FlowNavigator flowNavigator;
    private final EventTrigger eventTrigger;
    /**
     * WO-C8-38 (C38-2): arrived-строки join'ов ВНУТРИ отменяемого scope умирают вместе
     * со scope в ТОЙ ЖЕ транзакции отмены (без запуска хвоста). Иначе
     * {@code getGatewaysWithOpenArrivals} возвращает мёртвый id, и
     * {@code hasParkedJoinReaching} через {@code canReach(мёртвый join, соседний join)}
     * удерживает СОСЕДНИЙ join вечно. Резюма здесь НЕТ — тем же контрпримером
     * раунда 4 (воскрешать join отменённого scope = выполнить хвост отменённого
     * потока).
     */
    private final ScopeContainment scopeContainment;
    /**
     * WO-C8-35 раунд 5 (BLOCKER-4): перепроверка припаркованных inclusive-join'ов после смерти
     * последнего возможного доставщика. Раньше она стояла вручную на шести точках деактивации
     * (CompletionService, EventTrigger, InclusiveGatewayHandler) и НИ ОДНОЙ из них не было здесь,
     * хотя этот класс — единственное место, где умирает хост с собственной ERROR/ESCALATION-границей.
     * Цикла зависимостей нет: {@code InclusiveGatewayHandler} не знает ни про этот класс,
     * ни про {@code EventTrigger}, который уже Depends on него.
     */
    private final InclusiveGatewayHandler inclusiveGatewayHandler;
    /**
     * WO-C8-41 (F5): снос состояния отменённого MI-тела (deleteMiBodyState).
     * ПОРЯДОК ПОЛЕЙ: последним — ручные new в ScopeJoinCleanupWiringTest
     * дописаны тем же коммитом.
     */
    private final ElementSupport elementSupport;

    /**
     * Propagates a BPMN error from {@code tokenId} outward through the scope hierarchy, looking for
     * an interrupting error boundary that matches {@code errorCode} (a boundary without a code is a
     * catch-all). Search order: the throwing activity's OWN boundary first (WO-DIFF-5: a service
     * task reuses its incoming token, so without this self-check a boundary attached directly to
     * the throwing task — e.g. Raxon S-046 {@code s046-guarded} — is never examined), then enclosing
     * embedded subprocess scopes (innermost first), then — if the instance is a called process —
     * the call activity in the parent instance, recursively. When a handler is found the
     * interrupted scope is cancelled and flow continues from the boundary.
     *
     * <p>For the pre-existing end-event path pass {@code throwingActivityBpmnElementId == null}:
     * an end event cannot carry a boundary (BPMN forbids attaching to end events), so the old
     * walk is byte-identical. The recursive parent-propagation call below also passes null —
     * the call activity's own boundary is checked explicitly once at the propagation site,
     * and re-checking it in the recursion would fire the boundary twice.
     *
     * @return {@code true} if an error boundary handled the error, {@code false} if it escaped unhandled.
     */
    public boolean throwError(UUID processInstanceId, UUID tokenId, String errorCode,
            String throwingActivityBpmnElementId, TokenExecutor executor) {
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());

        // WO-DIFF-5 self-check: the throwing activity's own boundary first. Only the throwing
        // activity is cancelled (never the whole token: sibling branches of a parallel fork
        // share one token — cancelling for the token would kill the sibling too).
        // Residual edge (documented, not handled): an error boundary on a multi-instance task
        // itself — cancelling one instance while siblings continue diverges from Zeebe
        // (which terminates all); the pre-existing subprocess-scoped walk is unchanged.
        Token throwingTok = dbService.getToken(tokenId);
        if (throwingActivityBpmnElementId != null && throwingTok != null) {
            Activity throwing = dbService.getActivitiesByTokenAndBpmnElementId(tokenId, throwingActivityBpmnElementId)
                .stream()
                .filter(a -> processInstanceId.equals(a.getProcessInstanceId())
                    && (a.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED
                        || a.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.IN_PROGRESS))
                .findFirst()
                .orElse(null);
            if (throwing != null) {
                BpmnElementModel boundary = findErrorBoundary(bpmn, throwingActivityBpmnElementId, errorCode);
                if (boundary != null) {
                    dbService.cancelActivity(throwing.getId());
                    log.info("{}: error '{}' caught by boundary {} on throwing activity {}", processInstanceId, errorCode, boundary.getId(), throwingActivityBpmnElementId);
                    flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, boundary, executor);
                    inclusiveGatewayHandler.resumeParkedInclusiveJoins(processInstanceId, tokenId, bpmn, executor);
                    // WO-C8-35 раунд 5 (BLOCKER-4): хост ТОЛЬКО ЧТО умер и был последним
                    // возможным доставщиком ветви в припаркованный join — перечитываем правило
                    // готовности. Без этого join спит вечно: инстанс RUNNING, taskAfter не создан,
                    // инцидента нет (живой прогон security review, InclusiveJoinDeactivationWakeupIntegrationTests).
                    // Порядок — ПОСЛЕ proceedToOutgoing, как на всех остальных хвостах: собственное
                    // продолжение границы ещё может донести ветвь.
                    return true;
                }
            }
        }

        // walk enclosing embedded-subprocess scopes, innermost first
        //
        // WO-AUDIT-11 (F-09): обход идёт по ВСЕЙ цепочке токенов и НЕ обрывается на
        // первом токене без scope. Форк-токен (ParallelGatewayHandler.createToken(tokenId),
        // как и неявный форк в FlowNavigator.proceedToOutgoing) создаётся с
        // scopeActivityId = null — «вне скоупа» — хотя ветка ФАКТИЧЕСКИ живёт внутри
        // объемлющего SUB_PROCESS. Прежний цикл `while (tok != null && tok.getScopeActivityId() != null)`
        // на таком токене обрывался, и error boundary объемлющего подпроцесса терялся:
        // ошибка из ветки параллельного форка уходила дальше (top-level ESP / call activity /
        // инцидент) вместо того, чтобы быть пойманной границей. Живой Zeebe 8.5 (оракул,
        // docs/analysis/c8-oracle/scenarios/a11-fork-scope-boundary): граница объемлющего
        // подпроцесса ловит ошибку из ветки форка, инцидентов 0, сестринская ветка отменена.
        Token tok = dbService.getToken(tokenId);
        while (tok != null) {
            UUID scopeActivityId = tok.getScopeActivityId();
            if (scopeActivityId != null) {
                Activity scope = dbService.getActivity(scopeActivityId);
                BpmnElementModel boundary = findErrorBoundary(bpmn, scope.getBpmnElementId(), errorCode);
                if (boundary != null) {
                    // WO-C8-40: MI-подпроцесс — scope с N строками инстансов на одном
                    // токене: гасим ВСЕ инстансы и их внутренности ПРЯМЫМИ вызовами
                    // в ветке (helper — только чистое вычисление id; см.
                    // miSubProcessInstanceIds/miSubProcessInnerIds). Обычный scope —
                    // исторический путь без изменений.
                    if (isMiSubProcessScope(scope, bpmn)) {
                        java.util.Set<UUID> instanceIds = miSubProcessInstanceIds(processInstanceId, scope);
                        for (UUID instanceId : instanceIds) {
                            dbService.cancelActivity(instanceId);
                        }
                        for (UUID innerId : miSubProcessInnerIds(processInstanceId, instanceIds)) {
                            dbService.cancelActivity(innerId);
                        }
                        // WO-C8-41 (F5, oracle S7-2): как в boundary-отмене —
                        // предсозданный outputCollection прерванного тела не живёт
                        // дальше (entry token = токен строки scope-инстанса).
                        elementSupport.deleteMiBodyState(processInstanceId, scope.getToken(),
                            bpmn.getElement(scope.getBpmnElementId()));
                        dbService.cancelActivity(scope.getId());
                    } else {
                        // WO-AUDIT-11 (F-09): гасим ВЕСЬ скоуп, а не только активити на
                        // scope-токене. Отмена по токену не видит ветви форка (они живут
                        // на дочерних токенах) — сестринская ветка осталась бы жить,
                        // а инстанс висел бы с активной задачей. Тот же механизм, что у
                        // terminate-в-подпроцессе (EndEventHandler) и у ESP: filterActivitiesInScope.
                        cancelScopeActivities(processInstanceId, scope);
                        dbService.cancelActivity(scope.getId());
                    }
                    // WO-C8-38 (C38-2): чистка arrived-строк join'ов ВНУТРИ отменяемого scope —
                    // та же транзакция, без резюма (см. поле scopeContainment).
                    dbService.clearParallelGatewayArrivalsInJoins(processInstanceId,
                        scopeContainment.inclusiveGatewayIdsInsideScope(bpmn, scope.getBpmnElementId()));
                    log.info("{}: error '{}' caught by boundary {} on subprocess {}", processInstanceId, errorCode, boundary.getId(), scope.getBpmnElementId());
                    flowNavigator.proceedToOutgoing(processInstanceId, tok.getParentId(), bpmn, boundary, executor);
                    // БЕЗ перепроверки припаркованных join'ов, и это НЕ пропуск, а решение (белый
                    // список охранного теста DeactivationWakeupCoverageTest): погашен ВЕСЬ scope целиком,
                    // то есть умерли и все его внутренние ветви. Воскрешать join этого scope значило бы
                    // выполнить хвост ОТМЕНЁННОГО потока — ровно тот контрпример, на котором
                    // reviewer раунда 4 поймал регресс (POF-2, EventSubProcessInclusiveJoinIntegrationTests).
                    return true;
                }
            }
            tok = tok.getParentId() != null ? dbService.getToken(tok.getParentId()) : null;
        }

        // a top-level error-triggered event sub-process handles the error here (interrupting the main flow)
        BpmnElementModel errorHandler = findEventSubprocessErrorHandler(bpmn, errorCode);
        if (errorHandler != null) {
            log.info("{}: error '{}' caught by event sub-process {}", processInstanceId, errorCode, errorHandler.getId());
            eventTrigger.triggerEventSubprocess(processInstanceId, errorHandler.getId(), List.of(), executor);
            // Тот же белый список: прерывающий event-subprocess по BPMN ЗАМЕЩАЕТ основной поток,
            // а его запуск гасит activity всего инстанса (EventTrigger.cancelActiveActivities).
            // Просыпать тут припаркованный join = пускать хвост отменённого scope.
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
            // WO-C8-49: дочерний инстанс КИЛЛИРУЕТСЯ — та же финализация, что и cancel
            // (ERROR→CANCELLED + закрыть инциденты), до пометки completion.
            dbService.finalizeFinishedInstance(processInstanceId);
            dbService.completeProcessInstance(processInstanceId);

            if (boundary != null) {
                dbService.cancelActivity(callActivity.getId());
                log.info("{}: error '{}' caught by boundary {} on call activity {}", parentInstanceId, errorCode, boundary.getId(), callActivity.getBpmnElementId());
                flowNavigator.proceedToOutgoing(parentInstanceId, callActivity.getToken(), parentBpmn, boundary, executor);
                // WO-C8-35 раунд 5 (BLOCKER-4): в РОДИТЕЛЕ только что умерла строка call activity —
                // последний возможный доставщик в его припаркованный join. Перечитываем под
                // РОДИТЕЛЬСКИМ instance-lock (он уже взят выше, dbService.lockProcessInstance).
                inclusiveGatewayHandler.resumeParkedInclusiveJoins(parentInstanceId,
                    callActivity.getToken(), parentBpmn, executor);
                return true;
            }
            // not caught on the call activity: keep propagating within the parent instance
            return throwError(parentInstanceId, callActivity.getToken(), errorCode, null, executor);
        }

        return false;
    }

    /**
     * WO-AUDIT-11 (F-09): гасит ВСЕ живые активити скоупа — не только те, что лежат на
     * scope-токене.
     *
     * <p>Почему не {@code cancelActiveActivitiesForToken(scopeTokenId)}: ветви
     * parallel/inclusive-форка внутри скоупа живут на ДОЧЕРНИХ токенах (у форк-токена
     * {@code scopeActivityId = null}), и отмена по scope-токену их не видит — сестринская
     * ветка оставалась жить, инстанс висел с активной задачей. Тот же механизм «погасить
     * скоуп целиком», что у terminate-в-подпроцессе ({@code EndEventHandler}) и у
     * event-subprocess ({@code EventTrigger}): {@code ElementSupport.filterActivitiesInScope}
     * (обход цепочки токенов, а не сравнение токенов).
     *
     * <p>Контейнер скоупа в результат НЕ входит (его собственный скоуп — родительский),
     * его гасит вызывающий ({@code cancelActivity(scope.getId())}).
     */
    private void cancelScopeActivities(UUID processInstanceId, Activity scope) {
        for (Activity inScope : elementSupport.filterActivitiesInScope(
                processInstanceId, dbService.getActiveActivities(processInstanceId), scope.getId())) {
            dbService.cancelActivity(inScope.getId());
        }
    }

    /**
     * Finds an interrupting error boundary attached to {@code attachedToRef} whose error code matches
     * {@code errorCode}; a boundary without a code is a catch-all. Returns {@code null} if none.
     * WO-C8-4: a specific code always wins over catch-all, regardless of XML order.
     */
    private BpmnElementModel findErrorBoundary(BpmnProcessDefinitionModel bpmn, String attachedToRef, String errorCode) {
        BpmnElementModel catchAll = null;
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
            if (boundaryCode != null && boundaryCode.equals(errorCode)) {
                return element; // WO-C8-4: specific code wins regardless of iteration order
            }
            if (boundaryCode == null && catchAll == null) {
                catchAll = element; // remember the first catch-all as fallback
            }
        }
        return catchAll;
    }

    /**
     * Finds a top-level error-triggered event sub-process matching {@code errorCode}
     * (null code = catch-all). WO-C8-4: a specific code always wins over catch-all.
     */
    private BpmnElementModel findEventSubprocessErrorHandler(BpmnProcessDefinitionModel bpmn, String errorCode) {
        BpmnElementModel catchAll = null;
        for (BpmnElementModel element : bpmn.getEventSubProcesses()) {
            SubProcessExtensionModel ext = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getSubProcessExtension)
                .orElse(null);
            if (ext == null || !ext.isErrorTriggered()) {
                continue;
            }
            if (ext.getTriggerErrorCode() != null && ext.getTriggerErrorCode().equals(errorCode)) {
                return element; // WO-C8-4: specific code wins regardless of iteration order
            }
            if (ext.getTriggerErrorCode() == null && catchAll == null) {
                catchAll = element; // remember the first catch-all as fallback
            }
        }
        return catchAll;
    }

    /**
     * WO-C8-40: id инстансов MI-подпроцесса для гашения при error/escalation-границе
     * (чистое вычисление — НЕ деактивация: сами cancel-вызовы стоят ПРЯМО в ветках
     * throwError/throwEscalation, потому что охранный тест DeactivationWakeupCoverageTest
     * считает сайты по месту вызова, и вынос гашения в helper прятал бы их за одной
     * записью; прецедент C8-39 — замена вызов-на-вызов в той же ветке).
     * Строки ищутся по ИНСТАСУ, а не по токену бросания: токен бросания — scope-токен
     * ОДНОГО инстанса, а инстансы делят входящий токен контейнера. Инстанс бросания
     * (scope из walk) — тоже инстанс: его id берём из scope.getId().
     */
    private java.util.Set<UUID> miSubProcessInstanceIds(UUID processInstanceId, Activity scope) {
        java.util.Set<UUID> instanceIds = new java.util.HashSet<>();
        instanceIds.add(scope.getId());
        for (Activity sibling : dbService.getActiveActivities(processInstanceId)) {
            if (sibling == null || sibling.getId() == null) {
                continue;
            }
            if (scope.getBpmnElementId().equals(sibling.getBpmnElementId())
                && (sibling.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED
                    || sibling.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.IN_PROGRESS)) {
                instanceIds.add(sibling.getId());
            }
        }
        return instanceIds;
    }

    /**
     * WO-C8-40: внутренности инстансов MI-подпроцесса (activity, чья scope-цепочка
     * содержит один из инстансов) — чистое вычисление для прямых cancel-циклов
     * в ветках throwError/throwEscalation (см. выше, почему не helper с cancel).
     */
    private java.util.List<UUID> miSubProcessInnerIds(UUID processInstanceId, java.util.Set<UUID> instanceIds) {
        java.util.List<UUID> inner = new java.util.ArrayList<>();
        for (Activity live : dbService.getActiveActivities(processInstanceId)) {
            if (live == null || live.getId() == null || instanceIds.contains(live.getId())) {
                continue;
            }
            if (chainContains(processInstanceId, live, instanceIds)) {
                inner.add(live.getId());
            }
        }
        return inner;
    }

    /**
     * WO-C8-40: isMultiInstance-контейнер ли scope (лексика модели, не runtime).
     */
    private boolean isMiSubProcessScope(Activity scope, BpmnProcessDefinitionModel bpmn) {
        BpmnElementModel scopeElement = bpmn.getElement(scope.getBpmnElementId());
        return scopeElement != null
            && scopeElement.getType() == BpmnElementType.SUB_PROCESS
            && scopeElement.getExtensions() != null
            && scopeElement.getExtensions().getMultiInstanceExtension() != null;
    }

    /**
     * WO-C8-40: scope-цепочка активности содержит один из scopeId — тот же обход
     * токенов, что {@code ElementSupport.enclosingScopeChain} (локальная копия:
     * зависимость ElementSupport сюда тянуть нельзя — см. выше).
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
    public boolean throwEscalation(UUID processInstanceId, UUID tokenId, String escalationCode, TokenExecutor executor) {
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());

        // walk enclosing embedded-subprocess scopes, innermost first
        // WO-AUDIT-11 (F-09): тот же обход по ВСЕЙ цепочке токенов, что и в throwError —
        // форк-токен (scope = null) не обрывает обход, иначе escalation-граница
        // объемлющего подпроцесса терялась после параллельного форка.
        Token tok = dbService.getToken(tokenId);
        while (tok != null) {
            UUID scopeActivityId = tok.getScopeActivityId();
            if (scopeActivityId != null) {
                Activity scope = dbService.getActivity(scopeActivityId);
                BpmnElementModel boundary = findEscalationBoundary(bpmn, scope.getBpmnElementId(), escalationCode);
                if (boundary != null) {
                    if (isInterrupting(boundary)) {
                        // WO-C8-40: та же MI-отмена, что в error-пути выше (прямые
                        // вызовы в ветке, helper — только вычисление id).
                        if (isMiSubProcessScope(scope, bpmn)) {
                            java.util.Set<UUID> instanceIds = miSubProcessInstanceIds(processInstanceId, scope);
                            for (UUID instanceId : instanceIds) {
                                dbService.cancelActivity(instanceId);
                            }
                            for (UUID innerId : miSubProcessInnerIds(processInstanceId, instanceIds)) {
                                dbService.cancelActivity(innerId);
                            }
                            // WO-C8-41 (F5, oracle S7-2): как в error-пути выше.
                            elementSupport.deleteMiBodyState(processInstanceId, scope.getToken(),
                                bpmn.getElement(scope.getBpmnElementId()));
                            dbService.cancelActivity(scope.getId());
                        } else {
                            // WO-AUDIT-11 (F-09): гасим весь скоуп (включая ветви форка) —
                            // тот же механизм, что в error-пути.
                            cancelScopeActivities(processInstanceId, scope);
                            dbService.cancelActivity(scope.getId());
                        }
                        // WO-C8-38 (C38-2): чистка arrived-строк join'ов ВНУТРИ отменяемого scope —
                        // та же транзакция, без резюма (см. поле scopeContainment).
                        dbService.clearParallelGatewayArrivalsInJoins(processInstanceId,
                            scopeContainment.inclusiveGatewayIdsInsideScope(bpmn, scope.getBpmnElementId()));
                        log.info("{}: escalation '{}' caught (interrupting) by boundary {} on subprocess {}", processInstanceId, escalationCode, boundary.getId(), scope.getBpmnElementId());
                        flowNavigator.proceedToOutgoing(processInstanceId, tok.getParentId(), bpmn, boundary, executor);
                        // Белый список охранного теста: погашен весь scope вместе с его внутренними
                        // ветвями — воскрешать его join значит выполнить хвост отменённого потока
                        // (контрпример раунда 4).
                        return true;
                    }
                    Token branch = dbService.createToken(tok.getParentId());
                    log.info("{}: escalation '{}' caught (non-interrupting) by boundary {} on subprocess {} (branch token {})", processInstanceId, escalationCode, boundary.getId(), scope.getBpmnElementId(), branch.getId());
                    flowNavigator.proceedToOutgoing(processInstanceId, branch.getId(), bpmn, boundary, executor);
                    return false;
                }
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
                    // WO-C8-49: дочерний инстанс КИЛЛИРУЕТСЯ — та же финализация, что и cancel
                    // (ERROR→CANCELLED + закрыть инциденты), до пометки completion.
                    dbService.finalizeFinishedInstance(processInstanceId);
                    dbService.completeProcessInstance(processInstanceId);
                    dbService.cancelActivity(callActivity.getId());
                    log.info("{}: escalation '{}' caught (interrupting) by boundary {} on call activity {}", parentInstanceId, escalationCode, boundary.getId(), callActivity.getBpmnElementId());
                    flowNavigator.proceedToOutgoing(parentInstanceId, callActivity.getToken(), parentBpmn, boundary, executor);
                    // WO-C8-35 раунд 5 (BLOCKER-4): то же для ESCALATION-семейства — строка call
                    // activity в родителе умерла, его припаркованный join обязан проснуться
                    // (иначе он спит вечно, инцидента нет).
                    inclusiveGatewayHandler.resumeParkedInclusiveJoins(parentInstanceId,
                        callActivity.getToken(), parentBpmn, executor);
                    return true; // child instance was cancelled, so the throwing path is gone
                }
                // the child instance keeps running; a parallel branch is spawned in the parent
                Token branch = dbService.createToken(callActivity.getToken());
                log.info("{}: escalation '{}' caught (non-interrupting) by boundary {} on call activity {} (branch token {})", parentInstanceId, escalationCode, boundary.getId(), callActivity.getBpmnElementId(), branch.getId());
                flowNavigator.proceedToOutgoing(parentInstanceId, branch.getId(), parentBpmn, boundary, executor);
                return false;
            }
            // not caught on the call activity: keep propagating within the parent instance for handling,
            // but the child's throwing path is not interrupted by it
            throwEscalation(parentInstanceId, callActivity.getToken(), escalationCode, executor);
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
     * WO-C8-4: a specific code always wins over catch-all, regardless of XML order.
     */
    private BpmnElementModel findEscalationBoundary(BpmnProcessDefinitionModel bpmn, String attachedToRef, String escalationCode) {
        BpmnElementModel catchAll = null;
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
            if (boundaryCode != null && boundaryCode.equals(escalationCode)) {
                return element; // WO-C8-4: specific code wins regardless of iteration order
            }
            if (boundaryCode == null && catchAll == null) {
                catchAll = element; // remember the first catch-all as fallback
            }
        }
        return catchAll;
    }
}
