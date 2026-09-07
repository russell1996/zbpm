package com.zorrodev.bpm.engine.bpmn.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * WO-C8-29: resolved {@code zeebe:conditionalFilter} of a conditional event definition.
 *
 * <p>Semantics (docs, not the WO text — verified against
 * {@code docs.camunda.io}, "Conditional events" + "Conditionals", 8.9):
 * <ul>
 *   <li>{@code variableNames} — comma-separated allowlist of variable names; a change
 *   passes this dimension iff its name is listed. Empty/absent = unrestricted.
 *   (Schema + Modeler UI prove the attribute is live; the docs only elaborate
 *   {@code variableEvents}, hence this dimension is documented here, not skipped.)</li>
 *   <li>{@code variableEvents} — comma-separated allowlist of change kinds; a change
 *   passes iff its kind is listed. Supported values per docs: {@code create} and
 *   {@code update} only — any other token (e.g. {@code delete}) only ever matches if
 *   listed literally. Empty/absent = unrestricted.</li>
 * </ul>
 * A change passes the filter iff it passes every non-empty dimension. A change whose
 * name is unknown passes the names dimension (fail-open) and is still gated by
 * the events dimension.
 *
 * <p>Matching is pure and directly unit-tested (see {@code ConditionalFilterTest}).
 */
public record ConditionalFilter(Set<String> variableNames, Set<String> variableEvents) {

    public ConditionalFilter {
        variableNames = variableNames == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(variableNames));
        variableEvents = variableEvents == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(variableEvents));
    }

    /**
     * Parses the raw comma-separated attributes (docs example uses
     * {@code "create, update"} — with a space, so tokens are trimmed and empties
     * dropped). Null/blank attribute = unrestricted dimension (empty set).
     */
    public static ConditionalFilter parse(String variableNames, String variableEvents) {
        return new ConditionalFilter(splitList(variableNames), splitList(variableEvents));
    }

    private static Set<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * @param changes variable name to change kind ({@code "create"}/{@code "update"} —
     *                the only kinds the store records; deletes leave no trace).
     *                A {@code null} name (corrupt input) passes the names dimension
     *                fail-open.
     * @return true when the subscription must be re-evaluated; an empty change set
     * (trigger without a preceding write on this thread) also returns true —
     * no information, no filtering (current behavior — fail-open/safe).
     */
    public boolean matches(Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> change : changes.entrySet()) {
            String name = change.getKey();
            String kind = change.getValue();
            boolean namePasses = variableNames.isEmpty() || name == null || variableNames.contains(name);
            boolean kindPasses = variableEvents.isEmpty() || (kind != null && variableEvents.contains(kind));
            if (namePasses && kindPasses) {
                return true;
            }
        }
        return false;
    }
}
