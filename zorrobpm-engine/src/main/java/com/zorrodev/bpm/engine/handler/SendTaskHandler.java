package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.ActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handler for SEND_TASK elements.
 * Camunda-8 send task with zeebe:taskDefinition runs as a job worker;
 * BPMN-standard send task (messageRef) is a message throw.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendTaskHandler implements ElementHandler, TypedElementHandler {

    private final ActivityService activityService;
    private final MessageThrowHandler messageThrowHandler;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.SEND_TASK; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        boolean jobWorker = java.util.Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .isPresent();
        if (jobWorker) {
            activityService.enterServiceTask(processInstanceId, tokenId, bpmnElement);
        } else {
            messageThrowHandler.handle(ctx, bpmn, bpmnElement);
        }
    }
}
