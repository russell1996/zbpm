package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.exception.EngineException;

/**
 * WO-QW-9 (NEW4-08): self-registration throttled — a temporary client-side
 * condition, not a bad request. The client should retry later (HTTP 429 +
 * {@code Retry-After}), not fix its payload (422).
 *
 * <p>Mirrors {@link ScriptOverloadException} (same shape, same handler style
 * in {@code GlobalExceptionHandler}): extends {@link EngineException} so every
 * existing {@code catch (EngineException)} keeps catching it, while the
 * dedicated handler answers 429 by exact type. The retry delay is the IP/email
 * window of the exhausted bucket.
 */
public class RegistrationRateLimitException extends EngineException {

    private final int retryAfterSeconds;

    public RegistrationRateLimitException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Сколько секунд клиенту ждать перед повтором (значение для Retry-After). */
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
