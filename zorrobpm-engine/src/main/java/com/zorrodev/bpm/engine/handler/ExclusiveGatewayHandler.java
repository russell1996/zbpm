package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
            for (String outgoing : outgoings) {
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
