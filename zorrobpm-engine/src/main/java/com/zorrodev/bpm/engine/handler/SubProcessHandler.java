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

    @Override
    public BpmnElementType elementType() { return BpmnElementType.SUB_PROCESS; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);

        String startEventId = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getSubProcessExtension)
            .map(SubProcessExtensionModel::getStartEventId)
            .orElseThrow(() -> new EngineException("Subprocess " + bpmnElement.getId() + " has no start event"));

        Token childToken = dbService.createToken(tokenId, activityId);
        log.info("{}/{}: Entering {}: {}/{} (scope token {})", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), childToken.getId());

        // WO-DIFF-1 п.1: seed the sub scope with the container's input mappings
        // (ElementSupport evaluates them against the parent context; write scope is
        // the container activity — the same activity-scoped write task handlers use).
        // Runs BEFORE the nested start so inner elements already see seeded values.
        elementSupport.applyIoMappings(processInstanceId, activityId, bpmnElement, true);

        ctx.executor().execute(processInstanceId, childToken.getId(), bpmn, bpmn.getElement(startEventId));
    }
}
