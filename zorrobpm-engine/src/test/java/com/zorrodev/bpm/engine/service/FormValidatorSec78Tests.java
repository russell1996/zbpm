package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * WO-SEC-78 (NEW-08): the WO-SEC-75 {@code future.cancel(true)} fuse did not
 * stop the matcher — {@code java.util.regex} ignores interrupts, so the pooled
 * thread kept burning 100% CPU indefinitely after the caller got "timeout"
 * (audit: 1999ms CPU per 2s window, 6s after the fuse). Plus the static
 * {@code isDangerousPattern} gate false-positived on everyday email/FIO/phone
 * formats, silently rejecting legitimate values.
 *
 * <p>All tests go through the public {@link FormValidator#validate} end to end
 * (schema JSON in, error list out) — no copy of the matching logic, no direct
 * call into any guard (G-N). The CPU test measures the pooled worker thread
 * itself via {@link ThreadMXBean}, not "future was cancelled".
 */
class FormValidatorSec78Tests {

    private final FormValidator validator = new FormValidator(new ObjectMapper());

    private static String schemaFor(String javaPattern) {
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

    /**
     * Criterion 1: after the fuse fires on a real polynomial ReDoS shape, the
     * matching thread's CPU returns to ~0 (measured, not "future cancelled").
     * The shape is the audit's: adjacent ambiguous repetitions
     * ({@code ^\d*\d*\d*\d*\d*x$}) that the static filter does NOT know, so
     * only the interruptible fuse can save the pool slot.
     */
    @Test
    void timedOutMatch_burnsNoCpuAfterFuse() throws Exception {
        ThreadMXBean beans = ManagementFactory.getThreadMXBean();
        assertThat(beans.isThreadCpuTimeSupported()).isTrue();
        if (!beans.isThreadCpuTimeEnabled()) beans.setThreadCpuTimeEnabled(true);

        String pattern = "^\\d*\\d*\\d*\\d*\\d*x$";
        String value = "1".repeat(300);

        // Fail-closed and time-boxed even for the shape the static filter
        // misses: the old code burned the pool thread forever here.
        List<FormValidator.ValidationError> errors = assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> validator.validate(schemaFor(pattern), List.of(var(value))),
            "polynomial pattern must be rejected quickly, not hang");
        assertThat(errors).isNotEmpty();

        // The match ran on the fuse pool; now every pool thread must be idle.
        // Measure AFTER validate() returned, so the delta covers only
        // post-fuse burn (the legitimate ~500ms match burn is already spent).
        long cpuBefore = poolThreadsCpu(beans);
        Thread.sleep(1500);
        long cpuAfter = poolThreadsCpu(beans);
        assertThat(Math.max(0, cpuAfter - cpuBefore))
            .as("pooled matcher threads must not burn CPU after the fuse fired (nanos, 1.5s window)")
            .isLessThan(150_000_000L);
    }

    /**
     * Criterion 2: the exact typical-format shapes from the audit finding
     * (email / FIO / phone) validate normally — no silent static rejection.
     */
    @Test
    void typicalEmailFioPhonePatterns_validateNormally() {
        String[][] cases = {
            {"^([a-zA-Z0-9_.+-])+@(([a-zA-Z0-9-])+\\.)+([a-zA-Z0-9]{2,4})+$",
                "user@example.com", "not-an-email"},
            {"^([A-Z][a-z]+ )+[A-Z][a-z]+$", "John Smith", "john"},
            {"^\\+?[0-9]{1,3}([ -]?[0-9]+)+$", "+1 555 1234567", "abc"},
        };
        for (String[] c : cases) {
            String schema = schemaFor(c[0]);
            assertThat(validator.validate(schema, List.of(var(c[1]))))
                .as("typical format must accept its value: " + c[0])
                .isEmpty();
            assertThat(validator.validate(schema, List.of(var(c[2]))))
                .as("typical format must still reject a non-match: " + c[0])
                .isNotEmpty();
        }
    }

    /**
     * Criterion 3: the audit's polynomial class is either statically rejected
     * or — as implemented — guaranteed not to burn CPU past the fuse. This
     * test pins the fail-closed half on the exact audit shape; the no-burn
     * half is pinned by {@link #timedOutMatch_burnsNoCpuAfterFuse}.
     */
    @Test
    void polynomialPattern_rejectedFailClosedQuickly() {
        String pattern = "^\\d*\\d*\\d*\\d*\\d*x$";
        String value = "1".repeat(300);
        List<FormValidator.ValidationError> errors = assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> validator.validate(schemaFor(pattern), List.of(var(value))),
            "polynomial pattern must be rejected quickly, not hang");
        assertThat(errors).isNotEmpty();
    }

    /**
     * Deploy-time advisory half: the schema scan flags a nested-quantifier
     * shape for the form author and stays silent on a pattern-free schema.
     * (That the deploy proceeds regardless is proven by the REST IT
     * {@code deployFormWithRiskyPattern_warnsButSucceeds} — warning, not gate.)
     */
    @Test
    void findRiskyPatterns_flagsNestedQuantifierSchema() {
        ObjectMapper mapper = new ObjectMapper();
        String evil = "{\"components\":[{\"key\":\"code\",\"type\":\"textfield\","
            + "\"validate\":{\"pattern\":\"^((a+)*)+$\"}}]}";
        assertThat(FormValidator.findRiskyPatterns(evil, mapper))
            .containsExactly("^((a+)*)+$");

        String clean = "{\"components\":[{\"key\":\"code\",\"type\":\"textfield\","
            + "\"validate\":{\"pattern\":\"^[A-Z]{2}[0-9]{4}$\"}}]}";
        assertThat(FormValidator.findRiskyPatterns(clean, mapper)).isEmpty();

        assertThat(FormValidator.findRiskyPatterns(
            "{\"components\":[]}", mapper)).isEmpty();
        assertThat(FormValidator.findRiskyPatterns("not-json{{{", mapper)).isEmpty();
    }

    private static long poolThreadsCpu(ThreadMXBean beans) {
        long total = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("form-pattern-match".equals(t.getName())) {
                long cpu = beans.getThreadCpuTime(t.getId());
                if (cpu >= 0) total += cpu; // -1 = already dead: burns nothing
            }
        }
        return total;
    }
}
