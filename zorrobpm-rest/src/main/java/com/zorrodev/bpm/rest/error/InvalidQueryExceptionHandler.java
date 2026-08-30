package com.zorrodev.bpm.rest.error;

import com.zorrodev.bpm.contract.exception.InvalidQueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link InvalidQueryException} with a body, so that the caller learns which parameter was
 * rejected: the status alone does not say whether the field, the direction or the relation block
 * was at fault.
 *
 * <p>Deliberately narrow. It is bound to this module's own controllers and handles exactly one
 * exception type, so every other error keeps the response it had before.
 */
@RestControllerAdvice(basePackages = "com.zorrodev.bpm.rest")
public class InvalidQueryExceptionHandler {

    @ExceptionHandler(InvalidQueryException.class)
    public ProblemDetail handleInvalidQuery(InvalidQueryException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
