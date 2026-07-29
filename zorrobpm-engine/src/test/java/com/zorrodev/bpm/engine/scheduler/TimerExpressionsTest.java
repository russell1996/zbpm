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
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    @Test
    void isoRepeatingIntervalUsesTheDurationPart() {
        assertThat(TimerExpressions.firstOccurrence("R3/PT1H", FROM, UTC)).isEqualTo(FROM.plus(Duration.ofHours(1)));
        assertThat(TimerExpressions.firstOccurrence("R/PT30M", FROM, UTC)).isEqualTo(FROM.plus(Duration.ofMinutes(30)));
    }

    @Test
    void bareDurationIsAdded() {
        assertThat(TimerExpressions.firstOccurrence("PT5M", FROM, UTC)).isEqualTo(FROM.plus(Duration.ofMinutes(5)));
    }

    @Test
    void cronExpressionResolvesToTheNextMatchingInstant() {
        // "every hour at minute 0" -> the next occurrence is strictly after FROM and lands on minute/second 0
        Instant next = TimerExpressions.firstOccurrence("0 0 * * * *", FROM, UTC);
        assertThat(next).isAfter(FROM);
        ZonedDateTime local = ZonedDateTime.ofInstant(next, UTC);
        assertThat(local.getMinute()).isZero();
        assertThat(local.getSecond()).isZero();
    }

    @Test
    void cronExpressionUsesExplicitZone() {
        // Cron "0 0 9 * * *" with FROM = 2026-06-22T00:00:00Z
        Instant from = Instant.parse("2026-06-22T00:00:00Z");
        // In Asia/Almaty (UTC+5) 09:00 = 04:00 UTC
        Instant next = TimerExpressions.firstOccurrence("0 0 9 * * *", from, ALMATY);
        assertThat(next)
            .as("09:00 Asia/Almaty should resolve to 04:00 UTC")
            .isEqualTo(Instant.parse("2026-06-22T04:00:00Z"));
    }

    @Test
    void unsupportedExpressionThrows() {
        assertThatThrownBy(() -> TimerExpressions.firstOccurrence("not-a-cycle", FROM, UTC))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Backward-compat 2-arg overload (deprecated) ──

    @Test
    void deprecatedTwoArgOverloadStillWorks() {
        // The 2-arg overload delegates to the 3-arg with systemDefault
        Instant next = TimerExpressions.firstOccurrence("PT5M", FROM);
        assertThat(next).isEqualTo(FROM.plus(Duration.ofMinutes(5)));
    }
}
