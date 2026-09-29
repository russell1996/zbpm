package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WO-C8-32: execution metadata for an ad-hoc subprocess (internal mode, phase 1).
 * FEEL attributes ({@code activeElementsCollection}, {@code outputElement},
 * {@code completionCondition}) are '='-stripped at parse like the multi-instance
 * ones — evaluated at entry/completion with the live variables, never at parse time.
 */
@Getter
@Setter
public class AdHocSubProcessExtensionModel {
    /** Raw {@code zeebe:adHoc/@activeElementsCollection} (may be null/blank = none). */
    private String activeElementsCollection;
    /** Raw {@code zeebe:adHoc/@outputCollection} (null/blank = no aggregation). */
    private String outputCollection;
    /** Raw {@code zeebe:adHoc/@outputElement} (evaluated per completed inner flow). */
    private String outputElement;
    /** Raw {@code <bpmn:completionCondition>} (null/blank = complete when all done). */
    private String completionCondition;
    /**
     * Parsed {@code cancelRemainingInstances} (null in XML = docs default {@code true}).
     * Kept nullable so "absent" stays distinguishable in logs/tests; resolve the
     * default at use ({@code Boolean.FALSE.equals(...) == false} means cancel).
     */
    private Boolean cancelRemainingInstances;
    /**
     * Ids of the directly nested executable elements (tasks, gateways, catch/throw
     * events, call activities, nested sub/transactions/ad-hoc). Collected at parse
     * from the container's child lists. {@code activeElementsCollection} values must
     * be members — anything else (unknown id or an id from OUTSIDE this container)
     * raises an incident at entry, per the Camunda docs. Boundary events, flows and
     * associations are deliberately NOT members (not directly executable).
     */
    private Set<String> innerElementIds = new LinkedHashSet<>();
    /**
     * WO-C8-33: per-element metadata for the {@code adHocSubProcessElements} scope
     * variable (job-worker mode) — harvested at parse time because documentation and
     * {@code zeebe:properties} do not survive into the resolved element model.
     * Order follows the container's child lists (stable, declaration order).
     */
    private java.util.List<AdHocElementMetadata> elementsMetadata = new java.util.ArrayList<>();
}
