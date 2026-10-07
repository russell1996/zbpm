package com.zorrodev.bpm.engine.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-7: unit-семантика пина живых курсоров (точные значения, не
 * «что-то вызвалось» — P-67: каждое утверждение именует конкретный курсор,
 * который retention обязан держать/отпускать).
 */
class SseLiveCursorTrackerTest {

    @Test
    void emptyTracker_hasNoPin() {
        SseLiveCursorTracker tracker = new SseLiveCursorTracker();
        assertThat(tracker.minActiveCursor()).isEmpty();
        assertThat(tracker.trackedCount()).isEqualTo(0);
    }

    @Test
    void slowestClientWins_minIsMinimum() {
        SseLiveCursorTracker tracker = new SseLiveCursorTracker();
        tracker.track("fast", 9L);
        tracker.track("slow", 5L);
        assertThat(tracker.minActiveCursor()).hasValue(5L);
        assertThat(tracker.trackedCount()).isEqualTo(2);
    }

    @Test
    void advanceMovesForwardOnly_neverBackward() {
        SseLiveCursorTracker tracker = new SseLiveCursorTracker();
        tracker.track("c1", 5L);
        tracker.advance("c1", 12L);
        assertThat(tracker.minActiveCursor()).hasValue(12L);
        // Откат границы (переигранный catchup) пин не двигает назад.
        tracker.advance("c1", 7L);
        assertThat(tracker.minActiveCursor()).hasValue(12L);
    }

    @Test
    void advanceUnknownClient_isNoOp() {
        SseLiveCursorTracker tracker = new SseLiveCursorTracker();
        tracker.advance("ghost", 12L);
        assertThat(tracker.minActiveCursor()).isEmpty();
    }

    @Test
    void disconnectSlowest_releasesPinToNext() {
        SseLiveCursorTracker tracker = new SseLiveCursorTracker();
        tracker.track("slow", 5L);
        tracker.track("fast", 12L);
        tracker.untrack("slow");
        assertThat(tracker.minActiveCursor()).hasValue(12L);
        tracker.untrack("fast");
        assertThat(tracker.minActiveCursor()).isEmpty();
    }

    @Test
    void nonPositiveSince_notTracked() {
        SseLiveCursorTracker tracker = new SseLiveCursorTracker();
        tracker.track("plain", 0L);
        tracker.track("garbage", -3L);
        tracker.track(null, 10L);
        assertThat(tracker.minActiveCursor()).isEmpty();
        assertThat(tracker.trackedCount()).isEqualTo(0);
    }

    @Test
    void untrackUnknown_isNoOp() {
        SseLiveCursorTracker tracker = new SseLiveCursorTracker();
        tracker.track("c1", 5L);
        tracker.untrack("ghost");
        tracker.untrack(null);
        assertThat(tracker.minActiveCursor()).hasValue(5L);
    }
}
