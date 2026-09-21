package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handler for INCLUSIVE_GATEWAY elements.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InclusiveGatewayHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final FlowNavigator flowNavigator;
    private final ScriptService scriptService;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.INCLUSIVE_GATEWAY; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();
        TokenExecutor executor = ctx.executor();

        List<String> incomings = bpmnElement.getIncoming();
        List<String> outgoings = bpmnElement.getOutgoing();

        if (outgoings.size() > 1 && incomings.size() == 1) {
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
            dbService.completeActivity(activityId);

            String defaultFlowId = Optional.ofNullable(bpmnElement.getExtensions())
                .map(BpmnElementExtensionModel::getExclusiveGatewayExtension)
                .map(ExclusiveGatewayExtensionModel::getDefaultFlowId)
                .orElse(null);

            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            List<String> activated = new ArrayList<>();
            for (String outgoing : outgoings) {
                if (outgoing.equals(defaultFlowId)) {
                    continue;
                }
                if (isFlowActive(bpmn, outgoing, variables)) {
                    activated.add(outgoing);
                }
            }
            if (activated.isEmpty() && defaultFlowId != null) {
                activated.add(defaultFlowId);
            }

            log.info("{}/{}: Entering and completing {}: {}/{} (activating {} of {} branches)", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), activated.size(), outgoings.size());

            String joinId = findInclusiveJoin(bpmn, bpmnElement);
            if (joinId != null) {
                dbService.recordInclusiveExpected(processInstanceId, joinId, activated.size());
            }

            Token newToken = dbService.createToken(tokenId);
            UUID newTokenId = newToken.getId();
            // WO-ENG-1 (durable): set pending branch count before any branch executes.
            // Use activated.size() (only condition-passing branches), not outgoings.size().
            dbService.setPendingBranches(newTokenId, activated.size());
            for (String outgoing : activated) {
                flowNavigator.processFlow(processInstanceId, newTokenId, outgoing, false, null);
                BpmnFlowModel flow = bpmn.getFlow(outgoing);
                BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
                executor.execute(processInstanceId, newTokenId, bpmn, target);
            }
        } else if (incomings.size() > 1) {
            Integer expected = dbService.getInclusiveExpected(processInstanceId, bpmnElement.getId());
            Set<String> arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, bpmnElement.getId());

            if (expected != null && arrived.size() >= expected) {
                dbService.clearParallelGatewayArrivals(processInstanceId, bpmnElement.getId());
                Token token = dbService.getToken(tokenId);
                // WO-DIFF-4: same null-parent guard as ParallelGatewayHandler — a
                // non-interrupting boundary fork leaves the host branch on the (possibly
                // ROOT) host token; collapsing to a null parent would continue on null.
                UUID oldTokenId = token.getParentId() != null ? token.getParentId() : tokenId;
                UUID activityId = dbService.createActivity(processInstanceId, oldTokenId, bpmnElement);
                dbService.completeActivity(activityId);
                log.info("{}/{}: Entering and completing {}: {}/{} (all {} branches arrived)", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), expected);
                for (String outgoing : outgoings) {
                    flowNavigator.processFlow(processInstanceId, oldTokenId, outgoing, false, null);
                    BpmnFlowModel flow = bpmn.getFlow(outgoing);
                    BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
                    executor.execute(processInstanceId, oldTokenId, bpmn, target);
                }
            } else {
                log.info("{}/{}: Inclusive gateway join not ready yet {}: {} of {} branches arrived", processInstanceId, tokenId, bpmnElement.getId(), arrived.size(), expected);
            }
        } else {
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());
            flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, executor);
        }
    }

    private boolean isFlowActive(BpmnProcessDefinitionModel bpmn, String flowId, List<ProcessVariable> variables) {
        BpmnFlowModel flow = bpmn.getFlow(flowId);
        String expression = Optional.ofNullable(flow)
            .map(BpmnFlowModel::getConditionExpression)
            .map(BpmnConditionExpressionModel::getExpression)
            .filter(str -> !str.isEmpty())
            .map(str -> str.substring(1))
            .orElse(null);
        if (expression == null) {
            return true;
        }
        Boolean test = (Boolean) scriptService.evaluateScript(expression, variables);
        return Boolean.TRUE.equals(test);
    }

    private String findInclusiveJoin(BpmnProcessDefinitionModel bpmn, BpmnElementModel split) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String outgoing : split.getOutgoing()) {
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            if (flow != null) {
                queue.add(flow.getTargetRef());
            }
        }
        while (!queue.isEmpty()) {
            String elementId = queue.poll();
            if (elementId == null || !visited.add(elementId)) {
                continue;
            }
            BpmnElementModel element = bpmn.getElement(elementId);
            if (element == null) {
                continue;
            }
            if (element.getType() == BpmnElementType.INCLUSIVE_GATEWAY
                    && element.getIncoming() != null && element.getIncoming().size() > 1) {
                return element.getId();
            }
            if (element.getOutgoing() != null) {
                for (String outgoing : element.getOutgoing()) {
                    BpmnFlowModel flow = bpmn.getFlow(outgoing);
                    if (flow != null) {
                        queue.add(flow.getTargetRef());
                    }
                }
            }
        }
        return null;
    }
}
