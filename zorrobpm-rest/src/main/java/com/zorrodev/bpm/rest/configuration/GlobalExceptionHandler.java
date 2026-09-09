package com.zorrodev.bpm.rest.configuration;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.exception.FormValidationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FormValidationException.class)
    public ResponseEntity<Map<String, Object>> handleFormValidation(FormValidationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "VALIDATION_ERROR");
        List<Map<String, String>> fieldErrors = ex.getErrors().stream()
            .map(err -> {
                Map<String, String> error = new LinkedHashMap<>();
                error.put("field", err.field());
                error.put("message", err.message());
                return error;
            })
            .toList();
        body.put("errors", fieldErrors);
        body.put("message", "Form validation failed");
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "VALIDATION_ERROR");
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> {
                Map<String, String> error = new LinkedHashMap<>();
                error.put("field", fe.getField());
                error.put("message", fe.getDefaultMessage());
                return error;
            })
            .toList();
        body.put("errors", fieldErrors);
        body.put("message", "Validation failed");
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(BpmnParseException.class)
    public ResponseEntity<Map<String, String>> handleParseError(BpmnParseException ex) {
        // WO-SEC-33: generic message + correlationId; full details in log only
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] BPMN parse error: {}", correlationId, ex.getMessage(), ex);
        return ResponseEntity.badRequest().body(Map.of(
            "code", "PARSE_ERROR",
            "message", "BPMN parse error",
            "correlationId", correlationId
        ));
    }

    @ExceptionHandler(EngineException.class)
    public ResponseEntity<Map<String, String>> handleEngineError(EngineException ex) {
        // WO-SEC-33: generic message + correlationId; full details in log only
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] Engine error: {}", correlationId, ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
            "code", "ENGINE_ERROR",
            "message", "Engine execution error",
            "correlationId", correlationId
        ));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
        log.warn("NoSuchElementException (returning 404): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "code", "NOT_FOUND",
            "message", "Resource not found"
        ));
    }

    /**
     * WO-ACL-12: {code, params, message} — code is a stable key for the frontend locale,
     * params carry the substitution values, message stays for logs and API clients.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("params", ex.getParams());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String code = switch (ex.getStatusCode().value()) {
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 400 -> "VALIDATION_ERROR";
            case 409 -> "CONFLICT";
            default -> "INTERNAL_ERROR";
        };
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
            "code", code,
            "message", ex.getReason() != null ? ex.getReason() : "Error"
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of(
            "code", "MALFORMED_REQUEST",
            "message", "Request body is malformed or has wrong field types"
        ));
    }

    /**
     * WO-DB-1: a human-written {@code RAISE EXCEPTION} from a DB trigger carries
     * SQLState {@code P0001} — it is a deliberate, human-readable rule text, not a
     * raw error, so it surfaces as {@code 409 DATABASE_RULE_VIOLATION} with that
     * text instead of vanishing into the generic 500 below. Anything else (other
     * SQLStates, no {@code PSQLException} in the chain) is delegated to the
     * same generic response the pre-existing catch-all produces — WO-SEC-17 M6
     * stays in force for all of it.
     */
    @ExceptionHandler(JpaSystemException.class)
    public ResponseEntity<Map<String, String>> handleJpaSystem(JpaSystemException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof PSQLException psql && "P0001".equals(psql.getSQLState())) {
                ServerErrorMessage serverError = psql.getServerErrorMessage();
                String message = serverError != null ? serverError.getMessage() : null;
                if (message == null) {
                    message = psql.getMessage();
                }
                if (message == null) {
                    return genericInternalError(ex);
                }
                log.warn("Database rule violation (RAISE EXCEPTION): {}", message);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "DATABASE_RULE_VIOLATION",
                    "message", message
                ));
            }
            cause = cause.getCause();
        }
        // NOT rethrown: a throw from an @ExceptionHandler method does not
        // re-dispatch to handleGenericRuntime — it escapes as a bare
        // ServletException (proven by GlobalExceptionHandlerDbRuleTest RED).
        // Delegating directly keeps the observable behavior byte-identical
        // to "falling into the catch-all" (same 500 + INTERNAL_ERROR body).
        return genericInternalError(ex);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleGenericRuntime(RuntimeException ex) {
        return genericInternalError(ex);
    }

    private ResponseEntity<Map<String, String>> genericInternalError(RuntimeException ex) {
        // WO-SEC-17 M6: log full details internally, return generic message to client
        log.error("Unhandled runtime exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "code", "INTERNAL_ERROR",
            "message", "An unexpected error occurred"
        ));
    }
}
