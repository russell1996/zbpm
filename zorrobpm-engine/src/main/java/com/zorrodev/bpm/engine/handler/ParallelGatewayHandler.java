package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Handler for PARALLEL_GATEWAY elements.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParallelGatewayHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final FlowNavigator flowNavigator;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.PARALLEL_GATEWAY; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();
        TokenExecutor executor = ctx.executor();

        List<String> incomings = bpmnElement.getIncoming();
        List<String> outgoings = bpmnElement.getOutgoing();

        if (incomings.size() == 1) {
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

            Token newToken = dbService.createToken(tokenId);
            UUID newTokenId = newToken.getId();
            // WO-ENG-1 (durable): set pending branch count before any branch executes,
            // so finishBranch can decrement and only complete when all are consumed.
            dbService.setPendingBranches(newTokenId, outgoings.size());
            for (String outgoing : outgoings) {
                flowNavigator.processFlow(processInstanceId, newTokenId, outgoing, false, null);
                BpmnFlowModel flow = bpmn.getFlow(outgoing);
                String targetRef = flow.getTargetRef();
                BpmnElementModel target = bpmn.getElement(targetRef);
                executor.execute(processInstanceId, newTokenId, bpmn, target);
            }
        } else if (incomings.size() > 1) {
            Set<String> arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, bpmnElement.getId());
            boolean reached = arrived.containsAll(incomings);

            if (reached) {
                dbService.clearParallelGatewayArrivals(processInstanceId, bpmnElement.getId());
                Token token = dbService.getToken(tokenId);
                // WO-DIFF-4: a non-interrupting boundary forks on a CHILD token of the host's
                // token, so the host branch still runs on the (possibly ROOT) host token. When
                // that branch arrives LAST, getParentId() is null — collapsing to it would
                // continue the tail on a null token (DataIntegrityViolation on TOKEN NOT NULL).
                // A root token is its own survivor: continue on the arriving token itself.
                UUID oldTokenId = token.getParentId() != null ? token.getParentId() : tokenId;
                UUID activityId = dbService.createActivity(processInstanceId, oldTokenId, bpmnElement);
                dbService.completeActivity(activityId);
                log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());
                for (String outgoing : outgoings) {
                    flowNavigator.processFlow(processInstanceId, oldTokenId, outgoing, false, null);
                    BpmnFlowModel flow = bpmn.getFlow(outgoing);
                    String targetRef = flow.getTargetRef();
                    BpmnElementModel target = bpmn.getElement(targetRef);
                    executor.execute(processInstanceId, oldTokenId, bpmn, target);
                }
            } else {
                log.info("{}/{}: Parallel Gateway Not ready yet {}: {}", processInstanceId, tokenId, bpmnElement.getType(), bpmnElement.getId());
            }
        }
    }
}
