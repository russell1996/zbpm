package com.zorrodev.bpm.engine.handler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WO-ENG-1: Shared (static) tracker for pending outgoing branches of tokens created by
 * parallel gateways.
 * <p>
 * Needed because {@link ActivityServiceImpl} creates its own {@link FlowNavigator} instance
 * (via {@code @PostConstruct}) rather than using the Spring-managed bean, so any map stored
 * on the bean would not be visible from {@link FlowNavigator#finishBranch}. A static map
 * is visible from all instances.
 * <p>
 * Key = tokenId created by the parallel gateway (shared by all its outgoing flows),
 * value = number of those flows that have not yet reached a completing end event.
 * When all branches have been consumed the token's entry is removed and the process instance
 * may complete.
 */
public final class TokenBranchTracker {

    private static final Map<UUID, Integer> pendingBranches = new ConcurrentHashMap<>();

    private TokenBranchTracker() {
        // utility class
    }

    /**
     * Records that {@code tokenId} has {@code count} outgoing branches that must complete
     * before the process instance can finish. Only tracked when {@code count > 1}.
     */
    public static void setPendingBranches(UUID tokenId, int count) {
        if (count > 1) {
            pendingBranches.put(tokenId, count);
        }
    }

    /**
     * Returns the number of remaining pending branches for the given token, or {@code null}
     * if the token is not tracked (linear process or single-branch flow).
     */
    public static Integer getPendingBranches(UUID tokenId) {
        return pendingBranches.get(tokenId);
    }

    /**
     * Decrements the pending branch count for the given token. When it reaches 1 the entry
     * is removed (the last call to decrement will see {@code null} or the caller should
     * check {@link #getPendingBranches} and then call {@link #removeEntry}.
     */
    public static void decrement(UUID tokenId) {
        pendingBranches.computeIfPresent(tokenId, (k, v) -> v > 1 ? v - 1 : null);
    }

    /**
     * Explicitly removes the entry for the given token (defensive cleanup).
     */
    public static void removeEntry(UUID tokenId) {
        pendingBranches.remove(tokenId);
    }
}
