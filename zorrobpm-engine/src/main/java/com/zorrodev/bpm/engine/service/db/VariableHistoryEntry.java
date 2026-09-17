package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessVariableType;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-ENG-16 (WB-003): одна строка истории переменной — значение на момент
 * изменения в хронологическом порядке.
 */
public record VariableHistoryEntry(
    UUID id,
    UUID processInstanceId,
    String name,
    String textValue,
    ProcessVariableType type,
    UUID scopeId,
    String source,
    Instant changedAt
) {
}
