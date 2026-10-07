package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WO-C8-38: lexical scope containment over the flattened BPMN model.
 *
 * <p>The runtime model is FLAT: nested flow nodes live in the same element map as
 * process-level ones. The parser records each element's lexical parent container
 * ({@code BpmnElementModel.parentContainerElementId}, {@code null} at process level),
 * and this component answers the two questions the inclusive-join leftover fixes need:
 * <ol>
 *   <li>which inclusive-join elements live INSIDE scope container {@code S} (C38-2:
 *       their arrival rows die with the scope and must be cleared in the same
 *       transaction);</li>
 *   <li>which scope container an event sub-process is lexically nested in, if any
 *       (C38-3: a nested interrupting ESP cancels its parent scope, not the instance).</li>
 * </ol>
 *
 * <p>Pure structure, no DB, no runtime state — safe to call from any handler. Joins
 * reached THROUGH a nested event sub-process handler ({@code EVENT_SUB_PROCESS} on
 * the ancestor chain) are EXCLUDED from (1): an ESP handler runs in its own scope
 * instance with an independent lifecycle, and scope-confined cancellation does not
 * touch its rows (see {@code ElementSupport.enclosingScopeChain} — the ESP branch
 * token carries no scope id), so clearing its joins would strand ITS branches.
 * Whole-instance cancellation ({@code triggerEventSubprocess} top-level,
 * operator cancel) clears everything via
 * {@code DBService.clearAllParallelGatewayArrivals} instead and needs no containment.
 */
@Component
public class ScopeContainment {

    /**
     * Element ids of inclusive joins ({@code INCLUSIVE_GATEWAY}) lexically inside
     * scope container {@code scopeElementId}, excluding joins under a nested
     * event sub-process handler (independent lifecycle, see class javadoc).
     *
     * @return element ids in deterministic (sorted) order; empty if the scope holds no join
     */
    public List<String> inclusiveGatewayIdsInsideScope(BpmnProcessDefinitionModel bpmn, String scopeElementId) {
        if (bpmn == null || scopeElementId == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.INCLUSIVE_GATEWAY) {
                continue;
            }
            if (element.getId().equals(scopeElementId)) {
                continue;
            }
            if (isInsideScopeThroughNonEspPath(bpmn, element.getId(), scopeElementId)) {
                out.add(element.getId());
            }
        }
        List<String> sorted = new ArrayList<>(out);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /**
     * Nearest lexical scope container of an event sub-process: the closest ancestor
     * whose type is {@code SUB_PROCESS} (incl. transaction), {@code AD_HOC_SUB_PROCESS}
     * or {@code CALL_ACTIVITY}, skipping intermediate {@code EVENT_SUB_PROCESS} nodes.
     *
     * @return the container element id, or {@code null} for a process-level ESP
     *         (whole-instance cancellation applies) or when the nearest container is
     *         itself an event sub-process handler (ESP-in-ESP: exotic, no fixture —
     *         whole-instance fallback, documented in the WO report)
     */
    public String innermostScopeForEventSubprocess(BpmnProcessDefinitionModel bpmn, String espElementId) {
        if (bpmn == null || espElementId == null) {
            return null;
        }
        String cursor = parentOf(bpmn, espElementId);
        while (cursor != null) {
            BpmnElementModel container = bpmn.getElement(cursor);
            if (container == null) {
                return null;
            }
            BpmnElementType type = container.getType();
            if (type == BpmnElementType.SUB_PROCESS
                || type == BpmnElementType.AD_HOC_SUB_PROCESS
                || type == BpmnElementType.CALL_ACTIVITY) {
                return cursor;
            }
            if (type == BpmnElementType.EVENT_SUB_PROCESS) {
                return null;
            }
            cursor = parentOf(bpmn, cursor);
        }
        return null;
    }

    private boolean isInsideScopeThroughNonEspPath(BpmnProcessDefinitionModel bpmn,
            String elementId, String scopeElementId) {
        String cursor = parentOf(bpmn, elementId);
        while (cursor != null) {
            if (cursor.equals(scopeElementId)) {
                return true;
            }
            BpmnElementModel container = bpmn.getElement(cursor);
            if (container == null) {
                return false;
            }
            if (container.getType() == BpmnElementType.EVENT_SUB_PROCESS) {
                return false;
            }
            cursor = parentOf(bpmn, cursor);
        }
        return false;
    }

    private String parentOf(BpmnProcessDefinitionModel bpmn, String elementId) {
        BpmnElementModel element = bpmn.getElement(elementId);
        return element == null ? null : element.getParentContainerElementId();
    }
}
