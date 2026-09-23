package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * WO-SEC-75 (N11): length caps (256/4096) do not stop ReDoS — short patterns
 * with nested ambiguous quantifiers (e.g. {@code (a+)+b}) fit the caps freely
 * and used to backtrack on the calling request thread.
 *
 * <p>Both tests go through the public {@link FormValidator#validate} end to
 * end (schema JSON in, error list out) — no copy of the matching logic, no
 * direct call into any guard. If the guards are removed from
 * {@code FormValidator}, the adversarial test hangs past its fuse and goes
 * RED; that is the POF link (G-N).
 *
 * <p>On the fixed code every adversarial case is rejected in microseconds
 * (static shape reject or match fuse), so the 10&nbsp;s preemptive timeout is
 * headroom, not calibration (P-10): a regression hangs the worker, the
 * timeout fires, the test fails — it cannot go green-by-luck.
 */
class FormValidatorReDoSTests {

    private final FormValidator validator = new FormValidator(new ObjectMapper());

    private static String schemaFor(String javaPattern) {
        // Escape for JSON string embedding (backslashes only; the patterns
        // below contain no double quotes).
        String jsonPattern = javaPattern.replace("\\", "\\\\");
        return "{\"components\":[{\"key\":\"code\",\"type\":\"textfield\",\"validate\":{\"pattern\":\""
            + jsonPattern + "\"}}]}";
    }

    private static ProcessVariable var(String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName("code");
        v.setValue(value);
        return v;
    }

    @Test
    void adversarialPatterns_rejectedQuickly_failClosed() {
        // Both pairs fit the documented length caps (pattern <= 256,
        // value <= 4096), so only the SHAPE can save or hang the match —
        // the caps cannot mask this RED. Each pair was measured solo on the
        // unfixed engine (JDK 21, `Pattern.compile(p).matcher(v).find()`):
        // (1) backreference forces exhaustive partition search —
        // n=28 measured 3.2–3.5s and doubling every ~2 chars, n=40 hangs
        // past any CI budget (Probe7 case "A": no output within 60s);
        // (2) grows 37ms (n=20) → 217ms (n=24) → 904ms (n=26) →
        // 3.5s (n=28). On fixed code both are rejected in microseconds
        // (static shape reject), so the 10s preemptive timeout is headroom,
        // not calibration (P-10): a regression hangs the worker, the
        // timeout fires, the test fails — it cannot go green-by-luck.
        // NOTE on P-43: this test uses the wall-clock fuse by design
        // (a hang has no bytecode to grep); the fuse only fires on the
        // unfixed engine — on fixed code the match never starts.
        String[][] cases = {
            {"^((a+)*)+$", "a".repeat(40) + "!"},
            {"^(a+)+\\1$", "a".repeat(28) + "!"},
        };
        for (String[] c : cases) {
            String schema = schemaFor(c[0]);
            List<ProcessVariable> vars = List.of(var(c[1]));
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThat(validator.validate(schema, vars)).isNotEmpty(),
                "adversarial pattern must be rejected quickly, not hang: " + c[0]);
        }
    }

    @Test
    void legitPatterns_stillValidate() {
        // {pattern, matching value, non-matching value} — everyday form-js
        // formats that must keep working after the ReDoS fix.
        String[][] cases = {
            {"^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$", "user@example.com", "not-an-email"},
            {"^\\+?[0-9()\\- ]{7,20}$", "+1 (555) 123-4567", "abc"},
            {"^\\d{5}(-\\d{4})?$", "12345-6789", "1234"},
            {"^[A-Z]{2}[0-9]{4}$", "AB1234", "ab12"},
            {"^(red|green|blue)$", "green", "yellow"},
            {"^(\\d{3})-(\\d{4})$", "555-1234", "55-12"},
            {"^(\\d{3})+$", "123456", "12a456"},
            {"^[A-Z0-9]+$", "AB12", "ab!!"},
        };
        for (String[] c : cases) {
            String schema = schemaFor(c[0]);
            assertThat(validator.validate(schema, List.of(var(c[1]))))
                .as("legit pattern must accept its value: " + c[0])
                .isEmpty();
            assertThat(validator.validate(schema, List.of(var(c[2]))))
                .as("legit pattern must still reject a non-match: " + c[0])
                .isNotEmpty();
        }
    }
}
