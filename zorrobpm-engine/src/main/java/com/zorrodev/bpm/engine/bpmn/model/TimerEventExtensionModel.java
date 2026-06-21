package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TimerEventExtensionModel {
    private TimerEventType type;
    /** Raw timer value: an ISO-8601 duration (PT5M) for DURATION, an ISO-8601 instant for DATE, or an
     *  ISO-8601 repeating interval (R[n]/PT…) for CYCLE. */
    private String expression;

    /**
     * First-occurrence duration of an ISO-8601 repeating-interval {@code timeCycle} ({@code R[n]/<duration>},
     * e.g. {@code R3/PT1H} or {@code R/PT30M}); a bare duration is also accepted. Throws on unsupported forms
     * (e.g. a cron expression): {@link java.time.format.DateTimeParseException} / {@link IllegalArgumentException}.
     */
    public static java.time.Duration cycleFirstDuration(String cycle) {
        String spec = cycle.trim();
        if (spec.startsWith("R")) {
            int slash = spec.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("Unsupported timeCycle (expected R[n]/<duration>): " + cycle);
            }
            spec = spec.substring(slash + 1);
        }
        return java.time.Duration.parse(spec);
    }
}
