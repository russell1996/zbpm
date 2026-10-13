package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Handler for SUB_PROCESS elements.
 * Enters an embedded subprocess: records the subprocess container activity, creates a child
 * token scoped to it, and starts the subprocess's nested start event.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubProcessHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final ElementSupport elementSupport;
    private final BoundaryScheduler boundaryScheduler;
    private final MultiInstanceExecutor multiInstanceExecutor;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.SUB_PROCESS; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        // WO-C8-40: MI-подпроцесс входит через MultiInstanceExecutor (N инстансов,
        // привязка inputElement/loopCounter, join и агрегация на выходе) — зеркало
        // ServiceTaskHandler/UserTaskHandler. Одиночный вход ниже без изменений.
        if (multiInstanceExecutor.isMultiInstance(bpmnElement)) {
            multiInstanceExecutor.enter(ctx.processInstanceId(), ctx.tokenId(), bpmnElement, ctx.executor());
            return;
        }
        enterSingle(ctx, bpmn, bpmnElement);
    }

    /**
     * Одиночный вход в подпроцесс (до WO-C8-40 — всё тело {@link #handle}).
     * Поведение одиночных подпроцессов НЕ меняется (критерий 4): та же
     * последовательность «строка → токен → flat seed (root+own) → границы → старт».
     */
    public void enterSingle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(ctx.processInstanceId(), ctx.tokenId(), bpmnElement);
        startNestedFlow(ctx.processInstanceId(), ctx.tokenId(), activityId, bpmn, bpmnElement, ctx.executor(), false);
        // WO-C8-34 (CR-04): boundary events attached to the subprocess container
        // itself are armed on entry, like UserTaskHandler/ServiceTaskHandler do
        // for task hosts — same trio, same relative order (right after the host
        // row exists). Teardown rides the host row: fireBoundary ignores jobs
        // whose host already finished, and scope/confine paths cancel the host.
        boundaryScheduler.scheduleBoundaryTimers(ctx.processInstanceId(), activityId, bpmnElement);
        boundaryScheduler.scheduleMessageBoundaries(ctx.processInstanceId(), activityId, bpmnElement);
        boundaryScheduler.scheduleSignalBoundaries(ctx.processInstanceId(), activityId, bpmnElement);
    }

    /**
     * WO-C8-40: общий хвост входа в подпроцесс для одиночного входа
     * ({@link #enterSingle}) и MI-спауна инстансов ({@code MultiInstanceExecutor}):
     * дочерний scope-токен + seed скоупа + старт внутреннего start event.
     * Строка контейнера уже создана вызывающим (одиночный — выше, MI — в спауне
     * после привязки цикла), boundary-расписание — у вызывающих (у MI-инстанса
     * свой порядок: границы после старта nested flow, как у одиночного).
     *
     * @param chainedSeed одиночный вход — flat seed (root+own, WO-DIFF-1 без
     *                    изменений); MI-инстанс — chain seed (цепочка внешних
     *                    скоупов + собственный, цикл-переменная уже привязана).
     */
    public void startNestedFlow(UUID processInstanceId, UUID tokenId, UUID activityId,
            BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement, TokenExecutor executor, boolean chainedSeed) {
        String startEventId = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getSubProcessExtension)
            .map(SubProcessExtensionModel::getStartEventId)
            .orElseThrow(() -> new EngineException("Subprocess " + bpmnElement.getId() + " has no start event"));

        Token childToken = dbService.createToken(tokenId, activityId);
        log.info("{}/{}: Entering {}: {}/{} (scope token {})", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), childToken.getId());

        // WO-DIFF-1 п.1: seed скоупа входными маппингами контейнера — ДО старта
        // nested flow, чтобы внутренние элементы уже видели seed-значения.
        if (chainedSeed) {
            elementSupport.applyChainedIoInputs(processInstanceId, activityId, bpmnElement);
        } else {
            elementSupport.applyIoMappings(processInstanceId, activityId, bpmnElement, true);
        }

        executor.execute(processInstanceId, childToken.getId(), bpmn, bpmn.getElement(startEventId));
    }
}
