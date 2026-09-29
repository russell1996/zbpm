package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.CallActivityExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler for CALL_ACTIVITY elements.
 * Invokes an external process definition: resolves the target, computes the child's initial
 * variable set (WO-ENG-11: all parent variables by default, or only Input-mapping results when
 * {@code propagateAllParentVariables="false"}), and starts the child process via ActivityService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallActivityHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final ActivityService activityService;
    private final ElementSupport elementSupport;
    private final ExecutionContext executionContext;

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

        List<ProcessVariable> variables = resolveChildInitialVariables(bpmnElement, processInstanceId);

        // null-safe: a malformed call activity (no zeebe:calledElement / processId) or an undeployed target
        // becomes an informative incident (a non-EngineException is parked by execute()'s handler) instead of
        // an NPE / NoSuchElementException — the operator can fix the model / deploy the child and retry.
        String rawProcessId = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getCallActivityExtension)
            .map(ext -> ext.getProcessId())
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new IllegalStateException("Call activity '" + bpmnElement.getId() + "' has no zeebe:calledElement processId"));

        // WO-C8-2: processId may be a FEEL expression (="..." per Camunda 8) — resolve AFTER the
        // structural blank-check above ("model named no processId at all") and BEFORE the version
        // lookup. A null/blank resolve is the same incident path as "no deployed definition",
        // with a message that names the failed expression instead of a missing attribute.
        String key = elementSupport.resolveExpression(rawProcessId, processInstanceId);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Call activity '" + bpmnElement.getId() + "' processId expression '" + rawProcessId + "' resolved to null/blank — check instance variables and FEEL syntax");
        }

        // WO-REL-29: recursion/depth guard — must run BEFORE any DB/version lookup so a cycle
        // never reaches the synchronous startProcessInstance recursion. Throws
        // IllegalStateException so ActivityServiceImpl parks it as an incident (managed error).
        executionContext.enterCall(key, bpmnElement.getId(), processInstanceId);
        try {
            Integer version;
            String bindingType = Optional.ofNullable(bpmnElement.getExtensions())
                .map(BpmnElementExtensionModel::getCallActivityExtension)
                .map(CallActivityExtensionModel::getBindingType)
                .orElse(null);
            if ("versionTag".equals(bindingType)) {
                // WO-C8-3: resolve the latest version carrying the requested tag (NOT latest overall).
                String tag = Optional.ofNullable(bpmnElement.getExtensions())
                    .map(BpmnElementExtensionModel::getCallActivityExtension)
                    .map(CallActivityExtensionModel::getVersionTag)
                    .filter(s -> !s.isBlank())
                    .orElseThrow(() -> new IllegalStateException("Call activity '" + bpmnElement.getId() + "' has bindingType=\"versionTag\" but no versionTag attribute"));
                version = dbService.getMaxProcessDefinitionVersionByKeyAndVersionTag(key, tag);
                if (version == null || version == 0) {
                    throw new IllegalStateException("Call activity '" + bpmnElement.getId() + "' references process '" + key + "' with versionTag '" + tag + "' which has no matching deployed version");
                }
            } else if ("deployment".equals(bindingType)) {
                // WO-C8-3b: resolve the child version laid down TOGETHER WITH the currently running
                // parent version (shared deployment_id from POST /deployments) — NOT latest overall.
                // A singly-deployed parent (deployment_id NULL) or a child missing from the deployment
                // is an explicit incident, never a silent latest fallback (same philosophy as WO-C8-17).
                UUID parentPdId = dbService.getProcessInstance(processInstanceId).getProcessDefinitionId();
                UUID deploymentId = dbService.getDeploymentIdByProcessDefinitionId(parentPdId);
                if (deploymentId == null) {
                    throw new IllegalStateException("Call activity '" + bpmnElement.getId() + "' has bindingType=\"deployment\" but its process version was laid down singly (no deployment) — redeploy parent and child together via POST /deployments");
                }
                version = dbService.getMaxProcessDefinitionVersionByKeyAndDeploymentId(key, deploymentId);
                if (version == null || version == 0) {
                    throw new IllegalStateException("Call activity '" + bpmnElement.getId() + "' references process '" + key + "' which has no version deployed together with deployment '" + deploymentId + "' (bindingType=\"deployment\") — deploy parent and child together via POST /deployments");
                }
            } else {
                // "latest" (default) — latest overall wins, historical behaviour.
                version = dbService.getMaxProcessDefinitionVersionByKey(key);
                if (version == null || version == 0) {
                    throw new IllegalStateException("Call activity '" + bpmnElement.getId() + "' references process '" + key + "' which has no deployed definition");
                }
            }
            ProcessDefinition pd = dbService.getProcessDefinition(key, version);
            UUID processDefinitionId = pd.getId();

            activityService.startProcessInstance(activityId, processDefinitionId, variables);
        } finally {
            executionContext.exitCall();
        }
    }

    /**
     * WO-ENG-11: parent→child variables. Default (flag absent or true) copies every parent-instance
     * variable — historical behaviour. With {@code propagateAllParentVariables="false"} ONLY the
     * Input mappings of this call activity seed the child instance (evaluated against the parent's
     * variables); without mappings the child starts with an empty set. Deliberately NOT
     * {@code ElementSupport.applyIoMappings}: that writes into the CURRENT instance at activity scope,
     * while here we build the seed set for a child instance that does not exist yet.
     */
    private List<ProcessVariable> resolveChildInitialVariables(BpmnElementModel bpmnElement, UUID processInstanceId) {
        Boolean propagateParent = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getCallActivityExtension)
            .map(CallActivityExtensionModel::getPropagateAllParentVariables)
            .orElse(Boolean.TRUE);
        if (!Boolean.FALSE.equals(propagateParent)) {
            return dbService.getVariables(processInstanceId);
        }
        List<IoMappingExtensionModel.Mapping> inputs = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getIoMappingExtension)
            .map(IoMappingExtensionModel::getInputs)
            .orElse(null);
        if (inputs == null || inputs.isEmpty()) {
            log.info("{}: propagateAllParentVariables=false without Input mappings — child starts with no variables", processInstanceId);
            return List.of();
        }
        List<ProcessVariable> parentVariables = dbService.getVariables(processInstanceId);
        List<ProcessVariable> seed = new ArrayList<>();
        for (IoMappingExtensionModel.Mapping mapping : inputs) {
            ProcessVariable result = elementSupport.evaluateMapping(mapping, parentVariables);
            if (result != null) {
                seed.add(result);
            }
        }
        log.info("{}: propagateAllParentVariables=false — seeding child from {} Input mapping(s)", processInstanceId, seed.size());
        return seed;
    }
}
