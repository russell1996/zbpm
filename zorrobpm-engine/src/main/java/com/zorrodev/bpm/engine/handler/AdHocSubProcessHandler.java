package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.AdHocSubProcessExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WO-C8-32: handler for AD_HOC_SUB_PROCESS (internal mode, phase 1).
 * <p>
 * Enters an ad-hoc subprocess: records the container activity on the INCOMING token,
 * applies input mappings, evaluates {@code activeElementsCollection} once (docs: "evaluated
 * only on entering the subprocess") and dispatches every activated inner element via the
 * executor — all on the SAME shared token (mirrors {@code MultiInstanceExecutor}, which
 * likewise never forks tokens for its instances). The join lives in
 * {@link FlowNavigator#handleAdHocArrival} and reuses the same generic
 * expected/arrival counters as multi-instance.
 * <p>
 * Entry outcomes:
 * <ul>
 *   <li>no {@code zeebe:adHoc} / blank collection / empty list — nothing is activated and
 *       the scope "remains active" (docs, verbatim): the activity stays parked, no join
 *       bookkeeping is recorded (a null expected can never complete);</li>
 *   <li>any value that is not an id of a DIRECTLY nested element — incident on the ad-hoc
 *       activity (docs, verbatim), nothing is executed;</li>
 *   <li>otherwise every activated id is dispatched on the shared token and the join is told
 *       to expect exactly that many arrivals.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdHocSubProcessHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final ScriptService scriptService;
    private final ElementSupport elementSupport;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.AD_HOC_SUB_PROCESS; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        // Docs order: input mappings apply on entering, BEFORE the collection is evaluated.
        elementSupport.applyIoMappings(processInstanceId, activityId, bpmnElement, true);

        AdHocSubProcessExtensionModel ext = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getAdHocSubProcessExtension)
            .orElse(null);
        String raw = ext == null ? null : ext.getActiveElementsCollection();
        if (raw == null || raw.isBlank()) {
            log.info("{}/{}: Ad-hoc subprocess {} has no activeElementsCollection — no element activated, scope remains active",
                processInstanceId, tokenId, bpmnElement.getId());
            return;
        }

        List<String> activated = resolveActivatedIds(processInstanceId, activityId, bpmnElement, ext, raw);
        if (activated == null) {
            return; // incident already raised
        }
        if (activated.isEmpty()) {
            log.info("{}/{}: Ad-hoc subprocess {} collection evaluated to an empty list — no element activated, scope remains active",
                processInstanceId, tokenId, bpmnElement.getId());
            return;
        }

        // WO-ENG-7 pattern: per-entry batch UUID isolates join bookkeeping between
        // iterations (ad-hoc nested in a loop reuses its element id every iteration).
        String batchUuid = UUID.randomUUID().toString();
        ProcessVariable batchVar = new ProcessVariable();
        batchVar.setName(AdHocJoin.batchVariable(activityId));
        batchVar.setType(ProcessVariableType.STRING);
        batchVar.setValue(batchUuid);
        ProcessVariable activatedVar = new ProcessVariable();
        activatedVar.setName(AdHocJoin.activatedVariable(activityId));
        activatedVar.setType(ProcessVariableType.JSON);
        activatedVar.setValue(objectMapper.writeValueAsString(new ArrayList<>(activated)));
        dbService.setVariables(processInstanceId, List.of(batchVar, activatedVar));
        dbService.recordInclusiveExpected(processInstanceId, AdHocJoin.joinKey(activityId, batchUuid), activated.size());

        for (String id : activated) {
            BpmnElementModel inner = bpmn.getElement(id);
            if (inner == null) {
                raiseIncident(processInstanceId, activityId, bpmnElement.getId(),
                    "Ad-hoc subprocess '" + bpmnElement.getId() + "' references inner element '" + id
                        + "' which is not in the process definition");
                return;
            }
            ctx.executor().execute(processInstanceId, tokenId, bpmn, inner);
        }
        log.info("{}/{}: Ad-hoc subprocess {} activated {} element(s) batch={}", processInstanceId, tokenId, bpmnElement.getId(), activated.size(), batchUuid);
    }

    /**
     * Evaluates the collection and validates every id. Returns the activated ids, an empty
     * list for "evaluated but nothing" (parked scope), or null when an incident was raised.
     */
    private List<String> resolveActivatedIds(UUID processInstanceId, UUID activityId,
            BpmnElementModel bpmnElement, AdHocSubProcessExtensionModel ext, String raw) {
        Object evaluated;
        try {
            // '='-stripped at parse (like the MI inputCollection); straight to the engine.
            evaluated = scriptService.evaluateExpression(raw, dbService.getVariables(processInstanceId));
        } catch (Exception e) {
            raiseIncident(processInstanceId, activityId, bpmnElement.getId(),
                "Ad-hoc subprocess '" + bpmnElement.getId() + "' activeElementsCollection failed to evaluate: " + e.getMessage());
            return null;
        }
        Object javaValue = elementSupport.toJavaStructure(evaluated);
        if (!(javaValue instanceof List<?> list)) {
            raiseIncident(processInstanceId, activityId, bpmnElement.getId(),
                "Ad-hoc subprocess '" + bpmnElement.getId() + "' activeElementsCollection did not evaluate to a list: " + evaluated);
            return null;
        }
        List<String> activated = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String id)) {
                raiseIncident(processInstanceId, activityId, bpmnElement.getId(),
                    "Ad-hoc subprocess '" + bpmnElement.getId() + "' activeElementsCollection must return a list of strings, got: " + item);
                return null;
            }
            // Validate ALL ids before executing ANY (atomic entry): unknown ids and ids
            // from OUTSIDE this container both raise an incident, per the docs ("other
            // values than inner element IDs" — outer ids are exactly that).
            if (!ext.getInnerElementIds().contains(id)) {
                raiseIncident(processInstanceId, activityId, bpmnElement.getId(),
                    "Ad-hoc subprocess '" + bpmnElement.getId() + "' cannot activate '" + id
                        + "': not an inner element of this ad-hoc subprocess");
                return null;
            }
            activated.add(id);
        }
        return activated;
    }

    /** Entry-incident pattern mirrored from MultiInstanceExecutor.spawnMiInstance: mark + incident, execute nothing. */
    private void raiseIncident(UUID processInstanceId, UUID activityId, String elementId, String message) {
        log.warn("{}/{}: {}", processInstanceId, activityId, message);
        dbService.errorActivity(activityId);
        dbService.createIncident(activityId, message);
    }
}
