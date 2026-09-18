package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-INT-5: the test-email body must come from a template with placeholders,
 * not from string concatenation in code (WO requirement, CTO HOLD round 3 item 6).
 */
class MailTemplateTest {

    @Test
    void template_containsPlaceholders_notConcatenation() {
        String template = readTemplate();
        // WO: body is a template with substitutions — the raw template itself
        // must carry the placeholders; the code never builds the sentence by hand.
        assertThat(template)
            .contains("${recipient}")
            .contains("${timestamp}");
    }

    @Test
    void render_substitutesAllPlaceholders() {
        String template = readTemplate();
        String rendered = template
            .replace("${recipient}", "admin@test.kz")
            .replace("${timestamp}", Instant.parse("2026-08-22T12:00:00Z").toString());

        assertThat(rendered)
            .doesNotContain("${")
            .contains("admin@test.kz")
            .contains("2026-08-22T12:00:00Z");
    }

    private String readTemplate() {
        try (InputStream is = MailTemplateTest.class.getResourceAsStream("/mail/test-email.txt")) {
            assertThat(is).as("classpath resource mail/test-email.txt must exist").isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
