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

    public static Instant firstOccurrence(String cycle, Instant from) {
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
        ZonedDateTime next = CronExpression.parse(spec).next(ZonedDateTime.ofInstant(from, ZoneId.systemDefault()));
        if (next == null) {
            throw new IllegalArgumentException("timeCycle cron never fires: " + cycle);
        }
        return next.toInstant();
    }
}
