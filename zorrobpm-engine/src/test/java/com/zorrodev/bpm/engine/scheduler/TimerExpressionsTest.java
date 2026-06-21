package com.zorrodev.bpm.engine.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimerExpressionsTest {

    private static final Instant FROM = Instant.parse("2026-06-22T10:30:15Z");

    @Test
    void isoRepeatingIntervalUsesTheDurationPart() {
        assertThat(TimerExpressions.firstOccurrence("R3/PT1H", FROM)).isEqualTo(FROM.plus(Duration.ofHours(1)));
        assertThat(TimerExpressions.firstOccurrence("R/PT30M", FROM)).isEqualTo(FROM.plus(Duration.ofMinutes(30)));
    }

    @Test
    void bareDurationIsAdded() {
        assertThat(TimerExpressions.firstOccurrence("PT5M", FROM)).isEqualTo(FROM.plus(Duration.ofMinutes(5)));
    }

    @Test
    void cronExpressionResolvesToTheNextMatchingInstant() {
        // "every hour at minute 0" -> the next occurrence is strictly after FROM and lands on minute/second 0
        Instant next = TimerExpressions.firstOccurrence("0 0 * * * *", FROM);
        assertThat(next).isAfter(FROM);
        ZonedDateTime local = ZonedDateTime.ofInstant(next, ZoneId.systemDefault());
        assertThat(local.getMinute()).isZero();
        assertThat(local.getSecond()).isZero();
    }

    @Test
    void unsupportedExpressionThrows() {
        assertThatThrownBy(() -> TimerExpressions.firstOccurrence("not-a-cycle", FROM))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
