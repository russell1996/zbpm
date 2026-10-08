package com.zorrodev.bpm.engine.entity;

/**
 * WO-VT-1: место применения шаблона переменных — «кубик», на который шаблон заведён.
 * Значения обязаны совпадать с CHECK {@code ck_variable_presets__target_kind} в
 * changeset 20261007-120 (deny-by-default на уровне БД: строка с неизвестным
 * видом невозможна, даже если её напишет код мимо enum).
 */
public enum VariablePresetTargetKind {
    START,
    USER_TASK,
    SERVICE_TASK,
    MESSAGE,
    INCIDENT,
    DMN,
    ADHOC_JOB
}
