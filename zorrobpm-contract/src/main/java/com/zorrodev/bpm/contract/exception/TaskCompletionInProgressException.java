package com.zorrodev.bpm.contract.exception;

/**
 * WO-C8-24: a {@code complete} call for a user task whose {@code completing} listener
 * phase is already in flight. The task is neither completable again nor silently
 * ignorable — the REST layer maps this to HTTP 409 (mirrors the claim path, which maps
 * {@code IllegalStateException} to 409). A dedicated type (not a plain
 * {@code IllegalStateException}) so the mapping never masks unrelated failures.
 */
public class TaskCompletionInProgressException extends IllegalStateException {

    public TaskCompletionInProgressException(String message) {
        super(message);
    }
}
