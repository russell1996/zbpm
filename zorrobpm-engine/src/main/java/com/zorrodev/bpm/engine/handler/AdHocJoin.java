package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.service.DBService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * WO-C8-32: shared join-bookkeeping names for ad-hoc subprocess scopes.
 * <p>
 * One layer of indirection so {@link AdHocSubProcessHandler} (entry) and
 * {@link FlowNavigator#handleAdHocArrival} (join) cannot diverge on variable/join-key
 * shapes. Mirrors the multi-instance {@code miId::batchUuid} scheme: the per-entry batch
 * UUID isolates bookkeeping when an ad-hoc subprocess sits inside a loop.
 */
public final class AdHocJoin {

    private AdHocJoin() {
    }

    /** Root variable holding the per-entry batch UUID of one ad-hoc scope activity. */
    public static String batchVariable(UUID adHocActivityId) {
        return "_adhoc_batch_" + adHocActivityId;
    }

    /** Root JSON variable holding the activated inner-element ids of one ad-hoc scope. */
    public static String activatedVariable(UUID adHocActivityId) {
        return "_adhoc_activated_" + adHocActivityId;
    }

    /** Generic expected/arrival counter key for one ad-hoc scope entry. */
    public static String joinKey(UUID adHocActivityId, String batchUuid) {
        return adHocActivityId + "::" + batchUuid;
    }

    /** Unique arrival marker (arrivals are a set — the marker must differ per arrival). */
    public static String arrivalMarker(String bpmnElementId) {
        return bpmnElementId + "::" + UUID.randomUUID();
    }

    /**
     * Per-scope state resolved at join time, or null when the scope has no bookkeeping
     * (scope entered parked, or bookkeeping removed) — the join hook then no-ops.
     */
    public record ScopeState(String batchUuid, Set<String> activatedIds) {
    }

    public static ScopeState resolve(DBService dbService, tools.jackson.databind.ObjectMapper objectMapper,
            UUID processInstanceId, UUID adHocActivityId) {
        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
        String batch = variables.stream()
            .filter(v -> batchVariable(adHocActivityId).equals(v.getName()))
            .findFirst()
            .map(ProcessVariable::getValue)
            .orElse(null);
        String activatedJson = variables.stream()
            .filter(v -> activatedVariable(adHocActivityId).equals(v.getName()))
            .findFirst()
            .map(ProcessVariable::getValue)
            .orElse(null);
        if (batch == null || activatedJson == null || activatedJson.isBlank()) {
            return null;
        }
        Object parsed = objectMapper.readValue(activatedJson, Object.class);
        if (!(parsed instanceof List<?> list)) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof String s) {
                ids.add(s);
            }
        }
        return new ScopeState(batch, ids);
    }
}
