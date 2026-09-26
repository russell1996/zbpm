package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Handler for LINK_THROW_EVENT elements.
 * Intra-process goto: completes the throw, then jumps to the matching link catch.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LinkThrowHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final ActivityService activityService;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.LINK_THROW_EVENT; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        String linkName = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getName)
            .orElse(null);

        BpmnElementModel catchEvent = findLinkCatch(bpmn, linkName);
        if (catchEvent == null) {
            throw new IllegalStateException("Link throw '" + bpmnElement.getId()
                + "' has no matching link catch for link '" + linkName + "'");
        }
        log.info("{}/{}: Link throw {} -> catch {} (link '{}')", processInstanceId, tokenId, bpmnElement.getId(), catchEvent.getId(), linkName);

        activityService.execute(processInstanceId, tokenId, bpmn, catchEvent);
    }

    private BpmnElementModel findLinkCatch(BpmnProcessDefinitionModel bpmn, String linkName) {
        if (linkName == null) {
            return null;
        }
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.LINK_CATCH_EVENT) {
                continue;
            }
            String name = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getName)
                .orElse(null);
            if (linkName.equals(name)) {
                return element;
            }
        }
        return null;
    }
}
