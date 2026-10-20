package com.zorrodev.bpm.rest.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-14 (F-17): the error envelope's {@code code} must be a stable key
 * the client can branch on — an auth failure ({@code 401}) must not look like
 * a server crash, and a throttled request ({@code 429}) must not look like a
 * server crash either.
 *
 * <p>Before the fix, {@code GlobalExceptionHandler.handleResponseStatus} knew
 * only 400/403/404/409 and mapped EVERYTHING else — 401, 405, 415, 429 — to
 * {@code INTERNAL_ERROR}, and the catch-all branch said {@code INTERNAL_ERROR}
 * for any other status as well. The HTTP status itself was correct; only the
 * {@code code} key lied.
 *
 * <p>This test pins the mapping table itself. The end-to-end counterpart
 * (real request through the real filter chain) is
 * {@code GlobalExceptionHandlerHttpStatusLiveIT}.
 */
class GlobalExceptionHandlerHttpStatusCodeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * The contract table: one row per status the application can produce.
     * 401/405/415/429 are the F-17 rows (were {@code INTERNAL_ERROR});
     * 400/403/404/409 are pinned too, so the fix cannot silently move them.
     */
    @ParameterizedTest(name = "HTTP {0} -> code {1}")
    @CsvSource({
        "400, VALIDATION_ERROR",
        "401, UNAUTHORIZED",
        "403, FORBIDDEN",
        "404, NOT_FOUND",
        "405, METHOD_NOT_ALLOWED",
        "409, CONFLICT",
        "415, UNSUPPORTED_MEDIA_TYPE",
        "429, RATE_LIMITED"
    })
    void responseStatusException_mapsToStableCode(int status, String expectedCode) {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.valueOf(status), "boom");

        ResponseEntity<Map<String, String>> response = handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code"))
            .as("error-envelope code for HTTP %s", status)
            .isEqualTo(expectedCode);
    }

    /**
     * F-17: the fallback must be NEUTRAL — {@code HTTP_ERROR}, not
     * {@code INTERNAL_ERROR} (which would claim "our fault" for a status the
     * server did not fail on).
     */
    @ParameterizedTest(name = "unmapped HTTP {0} -> neutral HTTP_ERROR")
    @ValueSource(ints = {502, 503, 504, 507})
    void unmappedStatus_fallsBackToNeutralHttpError(int status) {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.valueOf(status), "boom");

        ResponseEntity<Map<String, String>> response = handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("HTTP_ERROR");
    }

    /**
     * Negative control: the REAL internal error (an unhandled exception, 500)
     * keeps {@code INTERNAL_ERROR}. The neutral fallback must not swallow the
     * distinction between "we crashed" and "the request was refused".
     */
    @Test
    void unhandledException_keepsInternalError() {
        ResponseEntity<Map<String, String>> response =
            handler.handleGenericRuntime(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("INTERNAL_ERROR");
    }
}
