package com.zorrodev.bpm.contract.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * WO-ACL-12: a machine-readable API error with a STABLE code and structured params, so the
 * frontend can map the code to a locale string and substitute the params (the {@code message}
 * stays as-is for logs and API clients). Thrown on the process-submission path; rendered by
 * {@code GlobalExceptionHandler} as {@code {code, params, message}}.
 *
 * <p>The code is an explicit literal — never derived from the message text — so that clients
 * can rely on it staying stable across rewording (criterion 8 of WO-ACL-12).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> params;

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> params) {
        super(message);
        this.status = status;
        this.code = code;
        this.params = params;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** Structured substitution values for the frontend's localized message, e.g. {@code {processKey: "x"}}. */
    public Map<String, Object> getParams() {
        return params;
    }
}
