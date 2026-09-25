package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.exception.EngineException;

/**
 * WO-ENG-24: перегрузка FEEL/script-пула — временное состояние, не ошибка
 * процесса и не ошибка запроса. Клиенту стоит повторить позже (HTTP 503 +
 * {@code Retry-After}, не 422).
 *
 * <p>Наследуется от {@link EngineException}, поэтому все существующие
 * {@code catch (EngineException)} продолжают его ловить (обратная
 * совместимость: ни один старый путь не падает по-новому). Пути, где
 * перегрузка должна отвечать 503, либо не ловят {@code EngineException}
 * вообще (старт процесса → {@code GlobalExceptionHandler}), либо явно
 * пробрасывают этот тип дальше (DMN-evaluate) до 503-хендлера.
 */
public class ScriptOverloadException extends EngineException {

    private final int retryAfterSeconds;

    public ScriptOverloadException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ScriptOverloadException(String message, Throwable cause, int retryAfterSeconds) {
        super(message, cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Сколько секунд клиенту ждать перед повтором (значение для Retry-After). */
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
