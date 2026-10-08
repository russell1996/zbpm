package com.zorrodev.bpm.engine.entity;

/**
 * WO-VT-1: видимость шаблона переменных. PRIVATE — только владелец; PROCESS —
 * владелец плюс участники процесса {@code processDefinitionKey} (любая роль).
 * Чужие PRIVATE-шаблоны отдаются как 404, а не 403, чтобы не раскрывать
 * существование (см. {@code VariablePresetService}).
 */
public enum VariablePresetVisibility {
    PRIVATE,
    PROCESS
}
