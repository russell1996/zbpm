package com.zorrodev.bpm.contract.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when query parameters are inconsistent or not supported. Raised before the query reaches
 * the data layer, so both REST callers and in-process callers of the query service are rejected
 * the same way.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidQueryException extends EngineException {

    public InvalidQueryException(String message) {
        super(message);
    }
}
