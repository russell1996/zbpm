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
    private final ElementSupport elementSupport;

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

            // WO-C8-35 (CR-09): the partner must CONVERGE this split's branches — the old
            // BFS took the first inclusive with several incomings it happened to walk into,
            // so an unrelated join on one branch's path swallowed the counter (criterion 2).
            String joinId = elementSupport.findConvergentInclusiveJoin(bpmn, bpmnElement);
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

            // WO-C8-35 (CR-09): readiness without a static counter. `expected` is written
            // by only three branchers (inclusive split, MI, ad-hoc), so from an XOR /
            // parallel fork / implicit AND-fork / subprocess exit it is null and the old
            // `expected != null && …` was false forever — a silent wait-forever. The join is
            // ready when nobody else in the instance can still deliver a branch to it
            // (ElementSupport.hasOtherLiveExecutionReaching). NOT the tempting `expected=1`:
            // that would fire a neighbouring join early under any other topology.
            boolean ready = expected != null
                ? arrived.size() >= expected
                : !elementSupport.hasOtherLiveExecutionReaching(processInstanceId, bpmn, bpmnElement);
            if (ready) {
                dbService.clearParallelGatewayArrivals(processInstanceId, bpmnElement.getId());
                Token token = dbService.getToken(tokenId);
                // WO-DIFF-4: same null-parent guard as ParallelGatewayHandler — a
                // non-interrupting boundary fork leaves the host branch on the (possibly
                // ROOT) host token; collapsing to a null parent would continue on null.
                UUID oldTokenId = token.getParentId() != null ? token.getParentId() : tokenId;
                UUID activityId = dbService.createActivity(processInstanceId, oldTokenId, bpmnElement);
                dbService.completeActivity(activityId);
                log.info("{}/{}: Entering and completing {}: {}/{} (all branches arrived: {} of {} expected)"
                        + (expected == null ? ", no counter — decided by live reachability" : ""),
                    processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(),
                    arrived.size(), expected == null ? "?" : String.valueOf(expected));
                for (String outgoing : outgoings) {
                    flowNavigator.processFlow(processInstanceId, oldTokenId, outgoing, false, null);
                    BpmnFlowModel flow = bpmn.getFlow(outgoing);
                    BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
                    executor.execute(processInstanceId, oldTokenId, bpmn, target);
                }
            } else {
                log.info("{}/{}: Inclusive gateway join not ready yet {}: {} of {} branches arrived"
                        + (expected == null ? " (no counter; another live execution can still reach it)" : ""),
                    processInstanceId, tokenId, bpmnElement.getId(), arrived.size(),
                    expected == null ? "?" : String.valueOf(expected));
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

}
