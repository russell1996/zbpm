package com.zorrodev.bpm.client;

import com.zorrodev.bpm.client.configuration.ClientConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-API-2: timeout property parsing — Boot-style suffixes and ISO-8601,
 * fail-fast on garbage (a mistyped timeout must break startup, not silently
 * become infinite).
 */
class ClientTimeoutParsingTest {

    @ParameterizedTest
    @CsvSource({
        "500ms, 500",
        "5s, 5000",
        "1m, 60000",
        "2h, 7200000",
        "1d, 86400000",
        "PT30S, 30000",
        "PT0.5S, 500"
    })
    void parseDuration_acceptsBootStyleAndIso(String raw, long expectedMillis) {
        assertThat(ClientConfiguration.parseDuration(raw, "zbpm.client.read-timeout"))
            .isEqualTo(Duration.ofMillis(expectedMillis));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "soon", "5x", "-5s", "5.5s"})
    void parseDuration_rejectsGarbageFailFast(String raw) {
        assertThatThrownBy(() -> ClientConfiguration.parseDuration(raw, "zbpm.client.read-timeout"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("zbpm.client.read-timeout");
    }

    @Test
    void parseDuration_rejectsNull() {
        assertThatThrownBy(() -> ClientConfiguration.parseDuration(null, "zbpm.client.connect-timeout"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
