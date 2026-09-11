package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * WO-AUDIT-4 (A5): maps {@link ListenerPhase} to its lowercase DB value and back.
 * Explicit (not auto-apply) — bound to the field via {@code @Convert}. Stored
 * strings are unchanged ({@code "start"}/{@code "done"}), so no migration.
 */
@Converter
public class ListenerPhaseConverter implements AttributeConverter<ListenerPhase, String> {

    @Override
    public String convertToDatabaseColumn(ListenerPhase attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public ListenerPhase convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ListenerPhase.fromDb(dbData);
    }
}
