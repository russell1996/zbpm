package com.zorrodev.bpm.engine.scheduler;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Computes the first occurrence of a BPMN {@code timeCycle} relative to a given instant. Supports:
 * <ul>
 *   <li>ISO-8601 repeating intervals {@code R[n]/<duration>} (e.g. {@code R3/PT1H}, {@code R/PT30M}),</li>
 *   <li>a bare ISO-8601 duration ({@code PT5M}), and</li>
 *   <li>Spring 6-field cron expressions (e.g. {@code 0 0 * * * *} — every hour).</li>
 * </ul>
 * Repetition (re-scheduling subsequent occurrences) is handled separately by the timer subsystem; this
 * utility only resolves when the timer should first fire. Throws {@link IllegalArgumentException} on an
 * unsupported expression so the caller can park an incident.
 */
public final class TimerExpressions {

    private TimerExpressions() {
    }

    /**
     * Backward-compatible variant using the JVM's default timezone.
     * @deprecated Use {@link #firstOccurrence(String, Instant, ZoneId)} with an explicit zone
     *             (businessZone) for consistent timezone handling (WO-ENG-4).
     */
    @Deprecated
    public static Instant firstOccurrence(String cycle, Instant from) {
        return firstOccurrence(cycle, from, ZoneId.systemDefault());
    }

    /**
     * Computes the first occurrence of a {@code timeCycle} expression relative to {@code from},
     * interpreting cron time-of-day fields in the given timezone.
     *
     * @param cycle the cycle expression (ISO repeating interval, bare duration, or Spring cron)
     * @param from  the reference instant
     * @param zone  the timezone for cron hour/minute interpretation (WO-ENG-4: use businessZone,
     *              not ZoneId.systemDefault())
     * @return the {@link Instant} of the first occurrence
     */
    public static Instant firstOccurrence(String cycle, Instant from, ZoneId zone) {
        if (cycle == null || cycle.isBlank()) {
            throw new IllegalArgumentException("Empty timeCycle expression");
        }
        String spec = cycle.trim();

        // ISO-8601 repeating interval: R[n]/<duration> — take the duration part
        if (spec.startsWith("R")) {
            int slash = spec.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("Unsupported timeCycle (expected R[n]/<duration>): " + cycle);
            }
            return from.plus(Duration.parse(spec.substring(slash + 1)));
        }

        // bare ISO-8601 duration
        if (spec.startsWith("P")) {
            return from.plus(Duration.parse(spec));
        }

        // otherwise a Spring cron expression
        ZonedDateTime next = CronExpression.parse(spec).next(ZonedDateTime.ofInstant(from, zone));
        if (next == null) {
            throw new IllegalArgumentException("timeCycle cron never fires: " + cycle);
        }
        return next.toInstant();
    }

    /**
     * Whether a {@code timeCycle} repeats indefinitely: an unbounded ISO interval ({@code R/<duration>}) or a
     * cron expression. A bounded interval ({@code R<n>/<duration>}) or a bare duration is not (it fires once
     * here — bounded repetition is a separate enhancement).
     */
    public static boolean isInfiniteCycle(String cycle) {
        if (cycle == null || cycle.isBlank()) {
            return false;
        }
        String spec = cycle.trim();
        if (spec.startsWith("R")) {
            int slash = spec.indexOf('/');
            return slash > 0 && spec.substring(1, slash).isBlank(); // "R/..." = unbounded; "R3/..." = bounded
        }
        if (spec.startsWith("P")) {
            return false; // bare duration = one-shot
        }
        return true; // cron = repeats
    }

    /**
     * Returns the repeat count for a bounded cycle like {@code R3/PT1S}.
     * @return n for {@code R<n>/...}, -1 for unbounded {@code R/...} or cron
     */
    public static int repeatCount(String cycle) {
        if (cycle == null || cycle.isBlank()) return -1;
        String spec = cycle.trim();
        if (spec.startsWith("R")) {
            int slash = spec.indexOf('/');
            if (slash > 0) {
                String countStr = spec.substring(1, slash).trim();
                if (countStr.isEmpty()) return -1; // "R/..." = unbounded
                try {
                    return Integer.parseInt(countStr);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1; // bare duration or cron = unbounded
    }

    /**
     * Persisted {@code remaining_count} for the FIRST timer job of a cycle: one less than the
     * repeat count (the first occurrence is the job itself), or {@code null} for unbounded cycles
     * (infinite repeat) and non-cycle timers. Mirrors {@code TimerCatchHandler} — WO-REL-17 makes
     * the boundary-timer primary scheduling use the same convention so the re-arm logic in
     * {@code EventTrigger} can decrement a persisted value instead of recomputing it from the model.
     *
     * @return {@code repeatCount - 1} for bounded {@code R<n>/...}, {@code null} otherwise
     */
    public static Integer remainingCount(String cycle) {
        int count = repeatCount(cycle);
        return count > 0 ? count - 1 : null;
    }
}
