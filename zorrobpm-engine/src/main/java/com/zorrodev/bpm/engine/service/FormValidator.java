package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;

/**
 * WO-FORM-5: Server-side validation of submitted variables against form-js schema.
 * Supports: required, minLength, maxLength, pattern (for textfield/textarea),
 * numeric parsing (for number/integer), boolean parsing (for checkbox).
 * Unknown component types are skipped (passthrough).
 *
 * <p>WO-API-1 (F17): рекурсивный обход вложенных {@code components}
 * (required внутри group/container проверяется); {@code integer} валидируется
 * строго ({@code Long.parseLong} — дробь/NaN/Infinity отклоняются, в отличие от
 * {@code Double.parseDouble}); regex из схемы исполняется с защитой от
 * catastrophic backtracking (см. {@link #matchesPattern}).
 */
@Slf4j
@Component
public class FormValidator {

    /**
     * WO-API-1 (F17, ReDoS): жёсткий лимит длины pattern + значения. form-js
     * patterns — короткие якорные выражения для форматов (email/phone/zip);
     * всё длиннее — не формат, а попытка положить request-поток. Лимит по длине
     * дешёвый, детерминированный и не требует потокобезопасных таймаутов
     * (interrupt-based timeout на общем пуле ненадёжен).
     */
    static final int MAX_PATTERN_LENGTH = 256;
    static final int MAX_PATTERN_VALUE_LENGTH = 4096;

    /**
     * WO-SEC-75 (N11): wall-clock fuse for a single pattern match. Length caps
     * alone do not stop ReDoS: short patterns with nested ambiguous
     * quantifiers (e.g. {@code ^((a+)*)+$} — 10 chars) fit the caps freely and
     * backtrack exponentially on the calling thread.
     *
     * <p>WO-SEC-78 (NEW-08): the fuse worker is genuinely interruptible. The
     * previous design ({@code future.cancel(true)}) never stopped the match:
     * {@code java.util.regex} does not honour the interrupt flag, so the pooled
     * thread kept burning 100% CPU indefinitely after the caller had already
     * received "timeout" — 32 such requests pinned 32 cores until JVM restart.
     * Now the matcher runs over a {@link DeadlineCharSequence} whose
     * {@code charAt} throws once the deadline passes, which unwinds the
     * backtracking engine from the inside within milliseconds of the fuse.
     * The static {@link #isDangerousPattern} shape check is NOT consulted here
     * anymore (it produced false positives on everyday email/phone/FIO formats
     * and silently rejected legitimate values); it survives only as a
     * deploy-time advisory (see {@link #findRiskyPatterns}).
     *
     * <p>500ms is orders of magnitude above a legitimate form-js format match
     * (microseconds — the formats are short anchored expressions over values
     * of a few KB at most) and far below a request-hang. The match runs on a
     * bounded worker pool (daemon threads, never the request thread).
     */
    static final long PATTERN_MATCH_TIMEOUT_MS = 500;
    /**
     * WO-SEC-75 layer-2 pool: cached (threads are created per concurrent
     * pathological match and reaped 60s after use — idle cost zero), daemon
     * (a timed-out match still burning CPU must never block JVM shutdown),
     * bounded queue discipline via AbortPolicy: when the pool is saturated
     * the submit itself throws RejectedExecutionException → fail-closed
     * invalid, exactly like a timeout. Cached+unbounded-thread-creation
     * alone would let N pathological patterns spawn N threads; the
     * SynchronousQueue handoff + AbortPolicy bounds the damage to what the
     * pool can absorb instead of hanging the request thread forever.
     */
    private static final java.util.concurrent.ExecutorService PATTERN_MATCH_POOL =
        new java.util.concurrent.ThreadPoolExecutor(0, 32,
            60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "form-pattern-match");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /**
     * WO-SEC-75 (N11): static shape check, kept in WO-SEC-78 ONLY as a
     * deploy-time advisory (see {@link #findRiskyPatterns}) — it is no longer
     * consulted on the submit path (see the fuse javadoc above for why).
     * Detects nested ambiguous quantifiers and backreferences, as before.
     * Known limitation, accepted by WO-SEC-78: flags some everyday formats
     * (email/phone/FIO shapes) — harmless as a warning the form author reads
     * at deploy time, unacceptable as a silent runtime rejection.
     */
    static boolean isDangerousPattern(String pattern) {
        return hasBackreference(pattern) || hasNestedQuantifier(pattern);
    }

    /** WO-SEC-75: {@code \1}..\{@code \9} outside a character class. */
    private static boolean hasBackreference(String pattern) {
        boolean escaped = false;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                if (!inClass && c >= '1' && c <= '9') return true;
                escaped = false;
                continue;
            }
            if (c == '\\') { escaped = true; continue; }
            if (c == '[') { inClass = true; continue; }
            if (c == ']' && inClass) { inClass = false; }
        }
        return false;
    }

    /**
     * WO-SEC-75: a repeated group whose body contains an AMBIGUOUS split —
     * a free repetition choice ({@code +}, {@code *}, open/{@code n>1}
     * {@code {m,n}}) inside a group that is itself repeatable from outside
     * ({@code +}, {@code *}, open/{@code n>1} {@code {m,n}}). Re-entering the
     * group multiplies the search: the split between inner repetitions and
     * outer repetitions is free. Linear counter-cases that must PASS:
     * fixed-shape atoms ({@code \d{3}}, literals, classes, {@code ?}-optionals)
     * even when the group repeats ({@code ^(\d{3})+$} — the partition is
     * forced); {@code ?}/{@code {1}} outside (re-enter at most once, e.g.
     * {@code (-\d{4})?}); single-char repetitions ({@code \d+},
     * {@code [a-z]*}, {@code a+}); plain alternations of fixed shapes
     * ({@code ^(red|green|blue)$}).
     * Runs in O(n) with an explicit stack (no regex-on-regex, no recursion).
     */
    private static boolean hasNestedQuantifier(String pattern) {
        int n = pattern.length();
        // groupStack: for each open '(' — whether its body already contains a
        // free repetition choice (the ambiguity source). A bare fixed
        // repetition like \d{3} is NOT recorded (see isInnerMultiQuantifierAt).
        java.util.ArrayDeque<Boolean> bodyHasInnerMultiQuant = new java.util.ArrayDeque<>();
        boolean escaped = false;
        boolean inClass = false;
        for (int i = 0; i < n; i++) {
            char c = pattern.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (inClass) {
                if (c == ']') inClass = false;
                continue;
            }
            if (c == '[') { inClass = true; continue; }
            if (c == '(') {
                bodyHasInnerMultiQuant.push(false);
                continue;
            }
            if (c == ')') {
                if (bodyHasInnerMultiQuant.isEmpty()) continue; // unbalanced — compile will fail-closed later
                boolean inner = bodyHasInnerMultiQuant.pop();
                // Catastrophe needs the group repeatable MORE than once from
                // the outside (+, *, {m,n} with n>1 or open). A lone '?' (0..1)
                // or '{1}' cannot re-enter the body, so (-\d{4})? stays linear.
                if (inner && isRepeatableQuantifierAt(pattern, i + 1)) return true;
                continue;
            }
            if (isInnerMultiQuantifierAt(pattern, i)) {
                if (!bodyHasInnerMultiQuant.isEmpty()) {
                    bodyHasInnerMultiQuant.pop();
                    bodyHasInnerMultiQuant.push(true);
                }
                // skip the rest of a {m,n} token so its digits/commas are
                // not re-scanned as independent characters
                if (pattern.charAt(i) == '{') {
                    int j = pattern.indexOf('}', i);
                    if (j > i) i = j;
                }
            }
        }
        return false;
    }

    /**
     * WO-SEC-75: "repeatable from outside" — the group can be re-entered more
     * than once ({@code +}, {@code *}, {@code {m,n}} with n &gt; 1 or open
     * upper bound). A lone {@code ?} or {@code {1}} repeats at most once and
     * cannot re-enter the body, so e.g. {@code (-\d{4})?} stays linear and is
     * NOT dangerous. Lazy modifiers ({@code +?}) do not change repeatability.
     */
    private static boolean isRepeatableQuantifierAt(String pattern, int i) {
        if (i >= pattern.length()) return false;
        char c = pattern.charAt(i);
        if (c == '*' || c == '+') return true;
        if (c == '{') {
            int j = pattern.indexOf('}', i);
            if (j <= i) return false;
            String inside = pattern.substring(i + 1, j);
            if (!inside.matches("[0-9]+(,[0-9]*)?")) return false;
            int comma = inside.indexOf(',');
            if (comma < 0) return Integer.parseInt(inside) > 1;
            String after = inside.substring(comma + 1);
            return after.isEmpty() || Integer.parseInt(after) > 1;
        }
        return false;
    }

    /**
     * Inner ambiguity source: a quantifier at i that introduces a FREE
     * repetition choice inside a group body — {@code *}, {@code +},
     * {@code {m,n}} with n &gt; 1 or open upper bound. Deliberately NOT
     * counted: fixed repetitions ({@code {3}}, {@code {1}}) — a fixed-shape
     * atom like {@code \d{3}} leaves no split choice, so
     * {@code ^(\d{3})+$} is linear and must pass. {@code ?} consumes at most
     * one char — linear, not counted. The laziness modifier
     * ({@code +?}) sits AFTER a real quantifier and adds no choice.
     */
    private static boolean isInnerMultiQuantifierAt(String pattern, int i) {
        if (i >= pattern.length()) return false;
        char c = pattern.charAt(i);
        if (c == '*' || c == '+') return true;
        if (c == '{') {
            int j = pattern.indexOf('}', i);
            if (j <= i) return false;
            String inside = pattern.substring(i + 1, j);
            if (!inside.matches("[0-9]+(,[0-9]*)?")) return false;
            int comma = inside.indexOf(',');
            if (comma < 0) return false; // fixed {m} — no free choice
            String after = inside.substring(comma + 1);
            // {m,} — open upper bound: free choice. {m,n} — iff n > 1.
            return after.isEmpty() || Integer.parseInt(after) > 1;
        }
        return false;
    }

    /**
     * WO-SEC-78 (NEW-08): unchecked abort thrown by
     * {@link DeadlineCharSequence} past the match deadline. Unchecked because
     * {@link CharSequence#charAt} cannot throw checked exceptions; caught
     * inside the fuse worker and converted to fail-closed {@code false}.
     */
    static final class RegexTimeoutException extends RuntimeException {
        RegexTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * WO-SEC-78 (NEW-08): a {@link CharSequence} view that aborts regex
     * backtracking from the inside. {@code java.util.regex} never checks the
     * thread interrupt flag, so {@code Future.cancel(true)} cannot stop a
     * pathological match — but the engine reads every input character through
     * {@code charAt}, and throwing there unwinds the matcher promptly. The
     * deadline is compared against {@link System#nanoTime} (monotonic,
     * immune to wall-clock steps).
     */
    static final class DeadlineCharSequence implements CharSequence {
        private final String delegate;
        private final long deadlineNanos;

        DeadlineCharSequence(String delegate, long deadlineNanos) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        private void checkDeadline() {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexTimeoutException(
                    "form pattern match exceeded its time budget");
            }
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            checkDeadline();
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            checkDeadline();
            return new DeadlineCharSequence(delegate.substring(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return delegate;
        }
    }

    /**
     * WO-SEC-78 (NEW-08): deploy-time advisory over a form-js schema — collects
     * the {@code validate.pattern} strings that {@link #isDangerousPattern}
     * flags, so the deploy path can warn the form author. Advisory ONLY: the
     * caller deploys regardless; runtime safety is the interruptible fuse in
     * {@link #matchesPattern}, not this list. Unparseable schemas yield an
     * empty list (deploy validates JSON separately and must never break on
     * an advisory scan).
     */
    static List<String> findRiskyPatterns(String schemaJson, ObjectMapper mapper) {
        List<String> risky = new ArrayList<>();
        if (schemaJson == null || schemaJson.isBlank()) return risky;
        try {
            Object parsed = mapper.readValue(schemaJson, Object.class);
            collectRiskyPatterns(parsed, risky);
        } catch (Exception e) {
            log.debug("ReDoS advisory scan skipped, schema not parseable: {}", e.getMessage());
        }
        return risky;
    }

    @SuppressWarnings("unchecked")
    private static void collectRiskyPatterns(Object node, List<String> risky) {
        if (node instanceof Map<?, ?> map) {
            Object components = map.get("components");
            if (components instanceof List<?> list) {
                for (Object child : list) collectRiskyPatterns(child, risky);
            }
            Object validate = map.get("validate");
            if (validate instanceof Map<?, ?> validateMap) {
                Object pattern = validateMap.get("pattern");
                if (pattern instanceof String patternStr
                        && !patternStr.isBlank()
                        && isDangerousPattern(patternStr)) {
                    risky.add(patternStr);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object child : list) collectRiskyPatterns(child, risky);
        }
    }

    private final ObjectMapper objectMapper;

    public FormValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ValidationError(String field, String message) {}

    /**
     * Validate submitted variables against a form-js schema.
     * Returns empty list if valid, list of errors otherwise.
     */
    @SuppressWarnings("unchecked")
    public List<ValidationError> validate(String schemaJson, List<ProcessVariable> variables) {
        List<ValidationError> errors = new ArrayList<>();
        if (schemaJson == null || schemaJson.isBlank()) return errors;

        // WO-FORM-5: null variables → treat as empty list (validate required → errors)
        List<ProcessVariable> vars = variables == null ? List.of() : variables;

        try {
            Map<String, Object> schema = objectMapper.readValue(schemaJson, Map.class);
            List<Map<String, Object>> components = (List<Map<String, Object>>) schema.get("components");
            if (components == null) return errors;

            // Build a map of submitted variables by name
            Map<String, String> submittedVars = new LinkedHashMap<>();
            for (ProcessVariable v : vars) {
                if (v.getName() != null && v.getValue() != null) {
                    submittedVars.put(v.getName(), v.getValue());
                }
            }

            validateComponents(components, submittedVars, errors);
        } catch (Exception e) {
            // WO-SEC-59 #3: fail-closed — a schema that cannot be parsed must NOT be treated as valid.
            log.warn("Failed to parse form schema for validation: {}", e.getMessage());
            errors.add(new ValidationError("schema", "Form schema could not be parsed: " + e.getMessage()));
        }

        return errors;
    }

    /**
     * WO-API-1 (F17): рекурсивный обход — group/container/fieldset несут свои
     * {@code components}, required внутри них обязан проверяться так же, как
     * top-level. Глубина form-js схем — единицы, рекурсия безопасна.
     */
    @SuppressWarnings("unchecked")
    private void validateComponents(List<Map<String, Object>> components,
            Map<String, String> submittedVars, List<ValidationError> errors) {
        for (Map<String, Object> component : components) {
            Object nested = component.get("components");
            if (nested instanceof List<?> nestedList && !nestedList.isEmpty()
                    && nestedList.get(0) instanceof Map) {
                validateComponents((List<Map<String, Object>>) nestedList, submittedVars, errors);
            }

            String key = (String) component.get("key");
            if (key == null) continue;

            String type = (String) component.get("type");
            String value = submittedVars.get(key);
            Map<String, Object> validate = (Map<String, Object>) component.get("validate");

            // Required check
            if (validate != null && Boolean.TRUE.equals(validate.get("required"))) {
                if (value == null || value.isBlank()) {
                    errors.add(new ValidationError(key, key + " is required"));
                    continue;
                }
            }

            if (value == null || value.isBlank()) continue;

            // Type-specific validation
            if ("integer".equals(type)) {
                // WO-API-1 (F17): строго целое — Long.parseLong отклоняет дробь,
                // NaN, Infinity, hex, пробелы по краям (trim перед парсингом —
                // form-js шлёт чистые значения, пробелы вокруг числа не число).
                try {
                    Long.parseLong(value.trim());
                } catch (NumberFormatException e) {
                    errors.add(new ValidationError(key, key + " must be an integer"));
                    continue;
                }
            } else if ("number".equals(type)) {
                try {
                    double parsed = Double.parseDouble(value.trim());
                    // WO-API-1 (F17): NaN/Infinity — не числа для формы
                    if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                        errors.add(new ValidationError(key, key + " must be a number"));
                        continue;
                    }
                } catch (NumberFormatException e) {
                    errors.add(new ValidationError(key, key + " must be a number"));
                    continue;
                }
            } else if ("checkbox".equals(type) || "boolean".equals(type)) {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    errors.add(new ValidationError(key, key + " must be a boolean"));
                    continue;
                }
            }
            // textfield, textarea, select, etc. — string validation below

            // String validations (for non-numeric, non-boolean types)
            if (validate != null) {
                Object minLenObj = validate.get("minLength");
                Object maxLenObj = validate.get("maxLength");
                String pattern = (String) validate.get("pattern");

                if (minLenObj instanceof Number minLength) {
                    if (value.length() < minLength.intValue()) {
                        errors.add(new ValidationError(key, key + " must be at least " + minLength.intValue() + " characters"));
                    }
                }
                if (maxLenObj instanceof Number maxLength) {
                    if (value.length() > maxLength.intValue()) {
                        errors.add(new ValidationError(key, key + " must be at most " + maxLength.intValue() + " characters"));
                    }
                }
                if (pattern != null && !matchesPattern(pattern, value)) {
                    errors.add(new ValidationError(key, key + " does not match the required pattern"));
                }
            }
        }
    }

    /**
     * WO-API-1 (F17, ReDoS): pattern-match с fail-closed лимитами. Длинный pattern
     * или длинное значение отклоняются как невалидные БЕЗ исполнения regex —
     * catastrophic backtracking невозможен конструктивно (движок не запускается).
     * Легитимные form-js patterns (email/phone/zip/code) — десятки символов;
     * значения — единицы KB максимум.
     *
     * <p>WO-SEC-78 (NEW-08): матч исполняется поверх {@link DeadlineCharSequence}
     * с дедлайном {@link #PATTERN_MATCH_TIMEOUT_MS} — патологический backtracking
     * абортится изнутри движка за миллисекунды после дедлайна, pooled-поток
     * освобождается (CPU возвращается к 0) вместо вечного прожига ядра после
     * {@code future.cancel(true)}, который {@code java.util.regex} игнорирует.
     */
    private boolean matchesPattern(String pattern, String value) {
        if (pattern.length() > MAX_PATTERN_LENGTH || value.length() > MAX_PATTERN_VALUE_LENGTH) {
            log.warn("Form pattern rejected without execution: patternLen={}, valueLen={}",
                pattern.length(), value.length());
            return false;
        }
        // WO-SEC-78 (NEW-08): no static isDangerousPattern gate here anymore —
        // it false-positived on everyday email/phone/FIO formats. Every shape,
        // known or unknown, takes the same interruptible path below.
        long deadlineNanos = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(PATTERN_MATCH_TIMEOUT_MS);
        java.util.concurrent.Future<Boolean> future =
            PATTERN_MATCH_POOL.submit(() -> {
                try {
                    return Pattern.compile(pattern)
                        .matcher(new DeadlineCharSequence(value, deadlineNanos))
                        .find();
                } catch (RegexTimeoutException e) {
                    log.warn("Form pattern match exceeded {}ms, rejected: patternLen={}, valueLen={}",
                        PATTERN_MATCH_TIMEOUT_MS, pattern.length(), value.length());
                    return false;
                }
            });
        try {
            return future.get(PATTERN_MATCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.warn("Form pattern match timed out after {}ms, rejected: patternLen={}, valueLen={}",
                PATTERN_MATCH_TIMEOUT_MS, pattern.length(), value.length());
            return false;
        } catch (Exception e) {
            // WO-SEC-59 #3, та же fail-closed дисциплина: битый pattern схемы —
            // невалидное значение, не пропуск.
            log.warn("Invalid form pattern rejected: {}", e.getMessage());
            return false;
        }
    }
}
