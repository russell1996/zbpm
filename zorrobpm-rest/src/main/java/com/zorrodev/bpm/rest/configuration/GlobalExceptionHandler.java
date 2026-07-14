package com.zorrodev.bpm.rest.configuration;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.contract.exception.EngineException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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
        return ResponseEntity.badRequest().body(Map.of(
            "code", "PARSE_ERROR",
            "message", ex.getMessage() != null ? ex.getMessage() : "BPMN parse error"
        ));
    }

    @ExceptionHandler(EngineException.class)
    public ResponseEntity<Map<String, String>> handleEngineError(EngineException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
            "code", "ENGINE_ERROR",
            "message", ex.getMessage() != null ? ex.getMessage() : "Engine error"
        ));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "code", "NOT_FOUND",
            "message", ex.getMessage() != null ? ex.getMessage() : "Resource not found"
        ));
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

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleGenericRuntime(RuntimeException ex) {
        // WO-SEC-17 M6: log full details internally, return generic message to client
        log.error("Unhandled runtime exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "code", "INTERNAL_ERROR",
            "message", "An unexpected error occurred"
        ));
    }
}
