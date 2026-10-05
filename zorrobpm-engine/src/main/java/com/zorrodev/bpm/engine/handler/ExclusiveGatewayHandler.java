package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler for EXCLUSIVE_GATEWAY elements.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExclusiveGatewayHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final FlowNavigator flowNavigator;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.EXCLUSIVE_GATEWAY; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID token = ctx.tokenId();
        TokenExecutor executor = ctx.executor();

        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        List<String> outgoings = bpmnElement.getOutgoing();
        List<String> incoming = bpmnElement.getIncoming();

        if (outgoings.size() > 1 && incoming.size() == 1) {
            // null-safe: a gateway without a <default> attribute has no extensions at all
            String defaultFlowId = Optional.ofNullable(bpmnElement.getExtensions())
                .map(BpmnElementExtensionModel::getExclusiveGatewayExtension)
                .map(ExclusiveGatewayExtensionModel::getDefaultFlowId)
                .orElse(null);

            List<com.zorrodev.bpm.contract.model.ProcessVariable> cachedVariables = dbService.getVariables(processInstanceId);
            String matchedOutgoing = null;
            // WO-DIFF-2 (Raxon находка №2, WO-014 S-016): при нескольких одновременно
            // истинных условиях настоящий Zeebe берёт ПОСЛЕДНИЙ по документному порядку
            // (reverse document order — тот же механизм, что fork-take в parallel gateway,
            // Raxon WO-013). Список outgoings идёт в документном порядке (JAXB сохраняет
            // порядок повторяющихся <outgoing>), поэтому оцениваем его с конца.
            // WO-C8-35 (CR-10 ч.2), решение CTO: этот reverse-document-order оставлен
            // СОЗНАТЕЛЬНО и не является ошибкой. Camunda 8.9 в письменной документации
            // обещает «первый истинный поток в порядке XML», наш код расходится с ней в
            // пользу эмпирического diff-прогона против ЖИВОГО Zeebe (Raxon, WO-DIFF-2) —
            // для этого проекта источник истины при расхождении с документацией: прогон,
            // а не доки (исторически документация Camunda неточна в деталях порядка).
            // Проверено Diff2ExclusiveGatewayOrderTest с POF. Если появится доступ к живому
            // Zeebe для повторной сверки — переоткрыть ОТДЕЛЬНЫМ WO с новым прогоном,
            // не «починить» по документации.
            // Переиспользуемой утилиты порядка в Zorro нет (ParallelGatewayHandler идёт
            // вперёд; fork-take WO-013 — код Raxon-трека, не этого репозитория), копия
            // обязательна: модель парса кэшируется, разворот на месте отравил бы кэш.
            List<String> evaluationOrder = new ArrayList<>(outgoings);
            Collections.reverse(evaluationOrder);
            for (String outgoing : evaluationOrder) {
                Boolean defaultFlow = Objects.equals(outgoing, defaultFlowId);
                UUID flowActivityId = flowNavigator.processFlow(processInstanceId, token, outgoing, true, defaultFlow, cachedVariables);
                if (flowActivityId != null) {
                    matchedOutgoing = outgoing;
                    break;
                }
            }

            if (matchedOutgoing == null) {
                if (defaultFlowId == null) {
                    // BPMN: no outgoing condition evaluated true and no default flow is defined. Raise
                    // an incident (not an NPE) so an operator can fix the data and re-run the gateway.
                    throw new IllegalStateException("Exclusive gateway '" + bpmnElement.getId()
                        + "' could not be evaluated: no outgoing sequence flow condition was true and no default flow is defined");
                }
                matchedOutgoing = defaultFlowId;
                flowNavigator.processFlow(processInstanceId, token, matchedOutgoing, false, null);
            }

            // routing decided successfully: the gateway is a pass-through, mark it completed
            dbService.completeActivity(activityId);
            BpmnFlowModel flow = bpmn.getFlow(matchedOutgoing);
            String targetRef = flow.getTargetRef();
            BpmnElementModel target = bpmn.getElement(targetRef);
            executor.execute(processInstanceId, token, bpmn, target);
        } else if (outgoings.size() == 1 && incoming.size() == 1) {
            // WO-C8-35 (CR-10 ч.1): degenerate 1-in/1-out is an UNCONDITIONAL
            // pass-through — with nothing to evaluate and nothing to merge, the gateway
            // is a no-op in the model and must behave like one in the engine. Before this
            // it fell into the `else` and threw IllegalStateException, i.e. an incident on
            // a legal (if pointless) diagram. Same shape as the PGW 1/1 pass-through
            // (WO-C8-34 crit 7) and the inclusive handler's fallthrough.
            //
            // A conditionExpression ON this single outgoing flow is deliberately NOT
            // evaluated (the G-H red-team flagged the inconsistency and the answer is a
            // decision, not a patch): the WO asks for an unconditional pass-through «как
            // если бы шлюза не было», and a 1/1 gateway is exactly that. So the general
            // rule "conditions are evaluated on flows out of exclusive/inclusive gateways"
            // has this one documented exception — written down in README ("Чего честно
            // нет") and pinned by
            // ExclusiveGatewayUnsupportedShapeIntegrationTests.degenerateExclusiveGateway_
            // oneInOneOut_withFalseCondition_stillPassesThrough.
            //
            // WO-C8-35 (CR-10 ч.1, MINOR-1 красного red-team — зафиксировано, дефекта НЕТ):
            // этот ветк связан с depth-guard'ом асимметрией у ParallelGatewayHandler
            // (тот способно отказывает isSelfLoop и разбора pass-through только для одного
            // исхода): здесь executor.execute вызывается ВНУТРИ счётчика guard'а, а там — через
            // proceedToOutgoing, те ниже счётчика. Разница работает чисто (EngineException по границе
            // 1000, а не StackOverflow), редкировано верификацией red-team самоповерка
            // xor1 с 1/1 self-loop — но расхождение невидимо и при чуже бы обратно внимание.
            String outgoing = outgoings.get(0);
            flowNavigator.processFlow(processInstanceId, token, outgoing, false, null);
            dbService.completeActivity(activityId);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
            executor.execute(processInstanceId, token, bpmn, target);
        } else if (outgoings.size() == 1 && incoming.size() > 1) {
            String outgoing = outgoings.get(0);
            flowNavigator.processFlow(processInstanceId, token, outgoing, false, null);
            dbService.completeActivity(activityId);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            String targetRef = flow.getTargetRef();
            BpmnElementModel target = bpmn.getElement(targetRef);
            executor.execute(processInstanceId, token, bpmn, target);
        } else {
            throw new IllegalStateException("Exclusive gateway '" + bpmnElement.getId()
                + "' has an unsupported incoming/outgoing shape: N=" + incoming.size() + "/M=" + outgoings.size());
        }
    }
}
