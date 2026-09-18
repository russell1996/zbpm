package com.zorrodev.bpm.rest.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-17 M6: 500 error must not leak internal details.
 * The response body must contain a generic message, not ex.getMessage().
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // --- Criterion #1: 500 returns generic message ---

    @Test
    void handleGenericRuntime_returnsGenericMessage_notExceptionDetails() {
        RuntimeException ex = new RuntimeException("Secret DB connection string: jdbc:postgresql://prod:5432/db?password=xyz");

        ResponseEntity<Map<String, String>> response = handler.handleGenericRuntime(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("INTERNAL_ERROR");
        // The message must NOT contain the secret details
        assertThat(response.getBody().get("message"))
            .doesNotContain("jdbc:postgresql")
            .doesNotContain("password=xyz")
            .doesNotContain("Secret DB connection");
        // It must be a generic message
        assertThat(response.getBody().get("message")).isEqualTo("An unexpected error occurred");
    }

    @Test
    void handleGenericRuntime_nullMessage_alsoReturnsGeneric() {
        RuntimeException ex = new RuntimeException((String) null);

        ResponseEntity<Map<String, String>> response = handler.handleGenericRuntime(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("An unexpected error occurred");
    }

    // --- WO-BE-7: handleNotFound must not leak internal details ---

    @Test
    void handleNotFound_returnsGenericMessage_notExceptionDetails() {
        NoSuchElementException ex = new NoSuchElementException("Internal path: /data/db/user/123");

        ResponseEntity<Map<String, String>> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
        // The message must NOT contain the internal details
        assertThat(response.getBody().get("message"))
            .doesNotContain("/data/db/user/123")
            .doesNotContain("Internal path");
        // It must be the generic message
        assertThat(response.getBody().get("message")).isEqualTo("Resource not found");
    }

    @Test
    void handleNotFound_nullMessage_alsoReturnsGeneric() {
        NoSuchElementException ex = new NoSuchElementException();

        ResponseEntity<Map<String, String>> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Resource not found");
    }
}
