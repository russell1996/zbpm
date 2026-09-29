package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Handler for SIGNAL_CATCH_EVENT elements.
 * Parks the token and registers a signal subscription.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalCatchHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.SIGNAL_CATCH_EVENT; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        String signalName = signalName(bpmnElement);
        if (signalName == null) {
            throw new EngineException("Signal catch event " + bpmnElement.getId() + " has no signal name");
        }
        dbService.createSignalSubscription(processInstanceId, activityId, signalName);
        log.info("{}/{}: Subscribed to signal '{}' at {}: {}/{}", processInstanceId, tokenId, signalName, bpmnElement.getType(), activityId, bpmnElement.getId());
    }

    public static String signalName(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getName)
            .orElse(null);
    }
}
