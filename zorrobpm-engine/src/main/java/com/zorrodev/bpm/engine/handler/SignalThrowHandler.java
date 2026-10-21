package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Handler for SIGNAL_THROW_EVENT elements.
 * Completes the activity, broadcasts the signal, and continues.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 *
 * <p>WO-OPS-37: прерывающий event sub-process, пойманный на этот сигнал, должен
 * ПРЕРВАТЬ основной поток. Порядок действий поэтому такой: activity броска создаётся
 * ДО броска (чтобы прерывание её увидело и погасило) и завершается ПОСЛЕ него
 * (обычным {@code completeActivity} — если поток не прерван).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalThrowHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final FlowNavigator flowNavigator;
    private final ActivityService activityService;
    private final ElementSupport elementSupport;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.SIGNAL_THROW_EVENT; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        // WO-OPS-37: activity броска создаётся ДО броска сигнала — на момент броска она
        // ещё жива (CREATED), и прерывающий event sub-process гасит её наравне с
        // остальными activity прерванного скоупа. Это и есть признак «поток прерван».
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);

        String signalName = SignalCatchHandler.signalName(bpmnElement);
        log.info("{}/{}: Throwing signal '{}' at {}: {}/{}", processInstanceId, tokenId, signalName, bpmnElement.getType(), activityId, bpmnElement.getId());

        if (signalName != null) {
            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            activityService.broadcastSignal(signalName, variables);
        }

        // WO-OPS-37: прерывающий event sub-process ЗАМЕНИЛ основной поток (Zeebe 8.5:
        // поток после броска сигнала не продолжается). Если нашу activity погасили —
        // поток прерван, продолжать нельзя: иначе основной поток ушёл бы дальше в
        // прерванном, а затем и в завершённом инстансе (воркер забрал бы задачу
        // мёртвого инстанса).
        if (elementSupport.isActivityCancelled(activityId)) {
            // Элемент ОТРАБОТАЛ (сигнал брошен), и его собственное действие — причина
            // гашения, а не жертва (WO-AUDIT-11 F-09, тот же приём, что у error-end):
            // поэтому УЗКИЙ переход, разрешающий источник CANCELLED.
            dbService.completeActivityAfterHandledError(activityId);
            log.info("{}/{}: signal '{}' interrupted the main flow (interrupting event sub-process) — the flow does not continue",
                processInstanceId, tokenId, signalName);
            return;
        }

        dbService.completeActivity(activityId);
        flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, ctx.executor());
    }
}
