package com.zorrodev.bpm.engine.handler;

import java.util.UUID;

/**
 * Immutable execution context passed to every {@link ElementHandler}.
 * <p>
 * Carries the current process instance, token, and the executor callback.
 * The executor is a <em>parameter</em> (not injected) so handlers avoid Spring
 * circular dependencies and {@code @Lazy} hacks.
 */
public record ExecutionCtx(
    UUID processInstanceId,
    UUID tokenId,
    TokenExecutor executor,
    ExecutionContext executionContext
) {
}
