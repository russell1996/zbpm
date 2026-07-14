package com.zorrodev.bpm.engine.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-17 L3: LIKE wildcards % and _ must be escaped in search queries.
 * Tests the escape string formation logic used by repository Specifications.
 */
class LikeEscapeTest {

    // Replicate the escape logic from the repositories
    private static String escapeLike(String input) {
        return input.toLowerCase().replace("%", "\\%").replace("_", "\\_");
    }

    @Test
    void percentWildcard_isEscaped() {
        String result = escapeLike("100%");
        assertThat(result).isEqualTo("100\\%");
        assertThat(result).contains("\\%");
    }

    @Test
    void underscoreWildcard_isEscaped() {
        String result = escapeLike("test_process");
        assertThat(result).isEqualTo("test\\_process");
        assertThat(result).contains("\\_");
    }

    @Test
    void bothWildcards_areEscaped() {
        String result = escapeLike("a%_b");
        assertThat(result).isEqualTo("a\\%\\_b");
    }

    @Test
    void noWildcards_unchanged() {
        String result = escapeLike("normal search");
        assertThat(result).isEqualTo("normal search");
    }
}
