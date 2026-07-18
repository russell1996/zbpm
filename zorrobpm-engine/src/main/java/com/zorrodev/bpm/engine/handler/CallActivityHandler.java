package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler for CALL_ACTIVITY elements.
 * Invokes an external process definition: resolves the target, fetches variables from the
 * current instance, and starts the child process via ActivityService.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallActivityHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final ActivityService activityService;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.CALL_ACTIVITY; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);

        // null-safe: a malformed call activity (no zeebe:calledElement / processId) or an undeployed target
        // becomes an informative incident (a non-EngineException is parked by execute()'s handler) instead of
        // an NPE / NoSuchElementException — the operator can fix the model / deploy the child and retry.
        String key = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getCallActivityExtension)
            .map(ext -> ext.getProcessId())
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new IllegalStateException("Call activity '" + bpmnElement.getId() + "' has no zeebe:calledElement processId"));

        Integer version = dbService.getMaxProcessDefinitionVersionByKey(key);
        if (version == null || version == 0) {
            throw new IllegalStateException("Call activity '" + bpmnElement.getId() + "' references process '" + key + "' which has no deployed definition");
        }
        ProcessDefinition pd = dbService.getProcessDefinition(key, version);
        UUID processDefinitionId = pd.getId();

        activityService.startProcessInstance(activityId, processDefinitionId, variables);
    }
}
