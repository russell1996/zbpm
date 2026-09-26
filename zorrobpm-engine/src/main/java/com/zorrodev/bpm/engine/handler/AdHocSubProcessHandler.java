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
 * WO-C8-32: handler for AD_HOC_SUB_PROCESS (internal mode). WO-C8-33 adds the
 * job-worker mode behind the same type: a {@code zeebe:taskDefinition} on the container
 * switches entry to job delivery instead of direct activation (the join primitives in
 * {@link FlowNavigator#handleAdHocArrival} are shared, the decision source differs).
 * <p>
 * Internal mode: records the container activity on the INCOMING token, applies input
 * mappings, evaluates {@code activeElementsCollection} once (docs: "evaluated only on
 * entering the subprocess") and dispatches every activated inner element via the
 * executor — all on the SAME shared token (mirrors {@code MultiInstanceExecutor}, which
 * likewise never forks tokens for its instances).
 * <p>
 * Internal entry outcomes:
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
    private final com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService serviceTaskEnqueueService;

    /** One element to activate, with optional element-scoped variables (job-worker results). */
    public record ActivationRequest(String elementId, List<ProcessVariable> variables) {
    }

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
        String jobType = elementSupport.serviceTaskJob(bpmnElement);
        if (jobType != null && !jobType.isBlank()) {
            // WO-C8-33: job-worker mode — no collection evaluation, no direct activation.
            // A present taskDefinition wins over a present activeElementsCollection (the
            // worker owns all activation decisions from here on); the collection, if any,
            // is ignored (logged, never evaluated).
            enterJobMode(ctx, bpmn, bpmnElement, activityId, ext, jobType);
            return;
        }
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
        // The activated set + expectation are written by activateInnerElements (shared
        // with the job-worker path); only the batch seed is written here.
        String batchUuid = UUID.randomUUID().toString();
        ProcessVariable batchVar = new ProcessVariable();
        batchVar.setName(AdHocJoin.batchVariable(activityId));
        batchVar.setType(ProcessVariableType.STRING);
        batchVar.setValue(batchUuid);
        dbService.setVariables(processInstanceId, List.of(batchVar));

        List<ActivationRequest> requests = activated.stream()
            .map(id -> new ActivationRequest(id, List.of()))
            .toList();
        activateInnerElements(ctx, bpmn, bpmnElement, activityId, requests, true);
        log.info("{}/{}: Ad-hoc subprocess {} activated {} element(s) batch={}", processInstanceId, tokenId, bpmnElement.getId(), activated.size(), batchUuid);
    }

    /**
     * WO-C8-33: job-worker entry — delivers one job on the scope itself instead of
     * activating anything. Writes the {@code adHocSubProcessElements} metadata variable
     * (scoped to the scope activity: no collision between nested scopes, still inside
     * the job's variable snapshot), the first job-generation token, and empty join
     * bookkeeping (uniform shape for {@link AdHocJoin#resolve}; the counter stays
     * unused in this mode — the worker decides, see {@link FlowNavigator}).
     */
    private void enterJobMode(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement,
            UUID scopeActivityId, AdHocSubProcessExtensionModel ext, String jobType) {
        UUID processInstanceId = ctx.processInstanceId();
        List<java.util.Map<String, Object>> elements = new ArrayList<>();
        if (ext != null && ext.getElementsMetadata() != null) {
            for (com.zorrodev.bpm.engine.bpmn.model.AdHocElementMetadata meta : ext.getElementsMetadata()) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("elementId", meta.getElementId());
                row.put("elementName", meta.getElementName());
                row.put("documentation", meta.getDocumentation());
                row.put("properties", meta.getProperties() == null ? new java.util.LinkedHashMap<>() : meta.getProperties());
                // AI-agent parameters (fromAi): out of project scope — always null (WO-C8-33 boundary).
                row.put("parameters", null);
                elements.add(row);
            }
        }
        ProcessVariable elementsVar = elementSupport.toProcessVariable("adHocSubProcessElements", elements);
        dbService.setVariables(processInstanceId, scopeActivityId, List.of(elementsVar));
        // Single shared job-issue implementation (entry and every recreation cannot diverge).
        AdHocJoin.issueScopeJob(dbService, elementSupport, serviceTaskEnqueueService,
            processInstanceId, scopeActivityId, bpmnElement);
        // Uniform bookkeeping shape (batch + empty activated set) so AdHocJoin.resolve works;
        // recordInclusiveExpected is deliberately NOT called — no counter in this mode.
        String batchUuid = UUID.randomUUID().toString();
        ProcessVariable batchVar = new ProcessVariable();
        batchVar.setName(AdHocJoin.batchVariable(scopeActivityId));
        batchVar.setType(ProcessVariableType.STRING);
        batchVar.setValue(batchUuid);
        ProcessVariable activatedVar = new ProcessVariable();
        activatedVar.setName(AdHocJoin.activatedVariable(scopeActivityId));
        activatedVar.setType(ProcessVariableType.JSON);
        activatedVar.setValue("[]");
        dbService.setVariables(processInstanceId, List.of(batchVar, activatedVar));
        log.info("{}/{}: Ad-hoc subprocess {} entered job-worker mode (job={}, batch={})",
            processInstanceId, ctx.tokenId(), bpmnElement.getId(), jobType, batchUuid);
    }

    /**
     * WO-C8-32/33 shared activation core: validates ALL requested ids before executing
     * ANY (atomic — unknown/outer ids raise an incident and nothing runs), merges the
     * round into the scope's activated set, optionally records join expectations
     * (internal mode only — job-worker mode never counts), dispatches each element on
     * the shared token and binds its element-scoped variables to the created activity.
     *
     * @return true when activation proceeded (false = incident raised, caller must stop)
     */
    public boolean activateInnerElements(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel scopeElement, UUID scopeActivityId, List<ActivationRequest> requests,
            boolean recordExpected) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();
        AdHocSubProcessExtensionModel ext = Optional.ofNullable(scopeElement.getExtensions())
            .map(BpmnElementExtensionModel::getAdHocSubProcessExtension)
            .orElse(null);
        if (ext == null) {
            raiseIncident(processInstanceId, scopeActivityId, scopeElement.getId(),
                "Ad-hoc subprocess '" + scopeElement.getId() + "' has no ad-hoc metadata");
            return false;
        }
        for (ActivationRequest req : requests) {
            if (!ext.getInnerElementIds().contains(req.elementId())) {
                raiseIncident(processInstanceId, scopeActivityId, scopeElement.getId(),
                    "Ad-hoc subprocess '" + scopeElement.getId() + "' cannot activate '" + req.elementId()
                        + "': not an inner element of this ad-hoc subprocess");
                return false;
            }
            if (bpmn.getElement(req.elementId()) == null) {
                raiseIncident(processInstanceId, scopeActivityId, scopeElement.getId(),
                    "Ad-hoc subprocess '" + scopeElement.getId() + "' references inner element '" + req.elementId()
                        + "' which is not in the process definition");
                return false;
            }
        }
        // Merge this round into the scope's activated set (union, stable order).
        AdHocJoin.ScopeState state = AdHocJoin.resolve(dbService, objectMapper, processInstanceId, scopeActivityId);
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (state != null) {
            merged.addAll(state.activatedIds());
        }
        for (ActivationRequest req : requests) {
            merged.add(req.elementId());
        }
        ProcessVariable activatedVar = new ProcessVariable();
        activatedVar.setName(AdHocJoin.activatedVariable(scopeActivityId));
        activatedVar.setType(ProcessVariableType.JSON);
        activatedVar.setValue(objectMapper.writeValueAsString(new ArrayList<>(merged)));
        dbService.setVariables(processInstanceId, List.of(activatedVar));
        if (recordExpected && !requests.isEmpty()) {
            AdHocJoin.ScopeState fresh = AdHocJoin.resolve(dbService, objectMapper, processInstanceId, scopeActivityId);
            String batch = fresh == null ? scopeActivityId.toString() : fresh.batchUuid();
            dbService.recordInclusiveExpected(processInstanceId, AdHocJoin.joinKey(scopeActivityId, batch), requests.size());
        }
        for (ActivationRequest req : requests) {
            ctx.executor().execute(processInstanceId, tokenId, bpmn, bpmn.getElement(req.elementId()));
            bindElementVariables(processInstanceId, tokenId, scopeElement.getId(), req);
        }
        return true;
    }

    /**
     * WO-C8-33: binds a worker result's element-scoped variables to the just-created
     * activity (MI-style scope; existing completion tails drop them automatically via
     * their usual {@code deleteVariables}). Wait-state elements only: an instant
     * element's expressions already evaluated during dispatch, so there is no live
     * activity to bind to — skipped with a warning (documented, not silent).
     */
    private void bindElementVariables(UUID processInstanceId, UUID tokenId, String scopeElementId,
            ActivationRequest req) {
        if (req.variables() == null || req.variables().isEmpty()) {
            return;
        }
        UUID elementActivityId = dbService.getActiveActivities(processInstanceId).stream()
            .filter(a -> tokenId.equals(a.getToken()) && req.elementId().equals(a.getBpmnElementId()))
            .max(java.util.Comparator.comparing(com.zorrodev.bpm.engine.dto.Activity::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
            .map(com.zorrodev.bpm.engine.dto.Activity::getId)
            .orElse(null);
        if (elementActivityId == null) {
            log.warn("{}/{}: Ad-hoc subprocess {} ignoring element-scoped variables for '{}': no live activity (instant element?)",
                processInstanceId, tokenId, scopeElementId, req.elementId());
            return;
        }
        dbService.setVariables(processInstanceId, elementActivityId, new ArrayList<>(req.variables()));
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
