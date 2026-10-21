package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.db.ActivityDbOperations;
import com.zorrodev.bpm.engine.service.db.ProcessInstanceDbOperations;
import com.zorrodev.bpm.engine.service.db.TokenDbOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * WO-OPS-35: гашение живых compensation-handler'ов вместе с инстансом.
 *
 * <p><b>Зачем.</b> Компенсационный handler запускается броском компенсации и живёт
 * ПАРАЛЛЕЛЬНО с потоком, если бросок не ждёт его завершения
 * ({@code waitForCompletion=false}, CR-07 WO-C8-34). Camunda 8.9: «the compensation
 * throw event remains active until all invoked compensation handlers are completed»
 * (при {@code waitForCompletion=true}); при {@code false} поток идёт дальше, а handler
 * работает сам. Zeebe/Scope-правило: когда инстанс (scope) завершается, все оставшиеся
 * внутри него activity instances терминируются — handler не исключение.
 *
 * <p><b>Симптом (WO-OPS-35).</b> До правки handler оставался живой задачей в
 * ЗАВЕРШЁННОМ инстансе: его activity-строка читалась {@code CREATED}, а job уходил
 * воркеру по мёртвому инстансу (а его поздний completion продолжал поток в мёртвом
 * инстансе). Инвариант утечек (WO-OPS-26/32) ловил это как
 * {@code type=activity element=compHandler live activity} + {@code type=task element=compHandler open service_task}.
 *
 * <p><b>Что гасится.</b> Только activity-строки элементов, которые в модели объявлены
 * компенсационными handler'ами (элемент, на который ссылается
 * {@code compensationHandlerId} границы компенсации), ПЛЮС их scope-поддерево
 * (handler может быть подпроцессом — тогда его внутренние activity живут на scope-токене
 * и должны умереть вместе с ним). ERROR-строки НЕ трогаются: павший handler — видимый
 * результат (WO-C8-49), и бросок с {@code waitForCompletion=true} отпускает thrower
 * именно по факту такой строки.
 *
 * <p><b>Где вызывается.</b> {@code DBService.completeProcessInstance} — единая точка
 * терминального перехода «завершён». Путь отмены гасит ВСЕ живые activity сам
 * (WO-OPS-35, {@code DBService.cancelProcessInstance} → {@code cancelActiveActivities}),
 * поэтому этот сервис нужен только на завершении.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationHandlerTerminator {

    /** Глубина подъёма по цепочке токенов (защита от цикла в данных). */
    private static final int MAX_TOKEN_DEPTH = 32;

    private final ProcessInstanceDbOperations processInstanceDbOperations;
    private final BpmnService bpmnService;
    private final ActivityDbOperations activityDbOperations;
    private final TokenDbOperations tokenDbOperations;

    /**
     * Гасит живые activity компенсационных handler'ов инстанса.
     *
     * @return сколько activity-строк переведено в CANCELLED
     */
    public int terminateLiveCompensationHandlers(UUID processInstanceId) {
        List<Activity> live = activityDbOperations.getActiveActivities(processInstanceId);
        if (live.isEmpty()) {
            // Обычный случай: инстанс завершился без единой живой activity — модель
            // даже не читается (completion — горячий путь).
            return 0;
        }
        ProcessInstance pi = processInstanceDbOperations.getProcessInstance(processInstanceId);
        if (pi == null || pi.getProcessDefinitionId() == null) {
            return 0;
        }
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());
        if (bpmn == null) {
            return 0;
        }
        Set<String> handlerIds = compensationHandlerElementIds(bpmn);
        if (handlerIds.isEmpty()) {
            return 0;
        }
        Set<UUID> terminated = new LinkedHashSet<>();
        // Scope-контейнеры, чьё поддерево умирает вместе с ними (handler = подпроцесс).
        Set<UUID> deadScopes = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Activity activity : live) {
                if (terminated.contains(activity.getId())) {
                    continue;
                }
                boolean kill = handlerIds.contains(activity.getBpmnElementId())
                    || insideDeadScope(activity, deadScopes);
                if (!kill) {
                    continue;
                }
                activityDbOperations.cancelActivity(activity.getId());
                terminated.add(activity.getId());
                deadScopes.add(activity.getId());
                changed = true;
            }
        }
        if (!terminated.isEmpty()) {
            log.info("{}: terminated {} live compensation handler activity(ies) with the instance",
                processInstanceId, terminated.size());
        }
        return terminated.size();
    }

    /**
     * Элементы модели, объявленные компенсационными handler'ами (по
     * {@code compensationHandlerId} границ компенсации).
     */
    private Set<String> compensationHandlerElementIds(BpmnProcessDefinitionModel bpmn) {
        Set<String> ids = new LinkedHashSet<>();
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.COMPENSATION_BOUNDARY_EVENT) {
                continue;
            }
            String handlerId = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getCompensationHandlerId)
                .orElse(null);
            if (handlerId != null && !handlerId.isBlank()) {
                ids.add(handlerId);
            }
        }
        return ids;
    }

    /**
     * Живёт ли activity внутри уже погашенного scope-контейнера? Скоуп activity
     * определяется по цепочке токенов (scopeActivityId каждого звена) — так же, как
     * {@code ElementSupport.enclosingScopeChain}, только без слоя handler'ов.
     */
    private boolean insideDeadScope(Activity activity, Set<UUID> deadScopes) {
        if (deadScopes.isEmpty()) {
            return false;
        }
        UUID cursor = activity.getToken();
        int depth = 0;
        while (cursor != null && depth++ < MAX_TOKEN_DEPTH) {
            Token token = tokenDbOperations.findToken(cursor).orElse(null);
            if (token == null) {
                return false;
            }
            if (token.getScopeActivityId() != null && deadScopes.contains(token.getScopeActivityId())) {
                return true;
            }
            cursor = token.getParentId();
        }
        return false;
    }
}
