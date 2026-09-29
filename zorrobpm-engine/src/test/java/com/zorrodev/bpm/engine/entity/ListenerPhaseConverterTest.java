package com.zorrodev.bpm.engine.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-AUDIT-4 (A5): enum ↔ DB round-trip. Stored strings stay lowercase
 * ({@code "start"}/{@code "done"}) — no value migration.
 */
class ListenerPhaseConverterTest {

    private final ListenerPhaseConverter converter = new ListenerPhaseConverter();

    @Test
    void roundTrip_startAndDone() {
        assertThat(converter.convertToDatabaseColumn(ListenerPhase.START)).isEqualTo("start");
        assertThat(converter.convertToDatabaseColumn(ListenerPhase.DONE)).isEqualTo("done");
        assertThat(converter.convertToEntityAttribute("start")).isEqualTo(ListenerPhase.START);
        assertThat(converter.convertToEntityAttribute("done")).isEqualTo(ListenerPhase.DONE);
    }

    @Test
    void nulls_passThrough() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void unknownDbValue_rejectedLoudly() {
        // A legacy/typo'd row must fail fast, not silently become a hanging phase.
        assertThatThrownBy(() -> converter.convertToEntityAttribute("doen"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entity_exposesTypedPhase() {
        // G-N binding: this references the entity's typed setter/getter, so it
        // only compiles against the fixed prod code — revert the entity to
        // String and this test goes RED (compilation error), not silent GREEN.
        ElementListenerPhaseEntity entity = new ElementListenerPhaseEntity();
        entity.setPhase(ListenerPhase.DONE);
        assertThat(entity.getPhase()).isSameAs(ListenerPhase.DONE);
    }

    @Test
    void dbValues_unchangedFromLegacyStrings() {
        // No migration needed: what the converter writes is exactly what the old
        // string code wrote.
        assertThat(ListenerPhase.START.dbValue()).isEqualTo("start");
        assertThat(ListenerPhase.DONE.dbValue()).isEqualTo("done");
    }
}
