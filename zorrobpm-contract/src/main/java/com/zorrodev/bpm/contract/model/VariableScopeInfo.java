package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * WO-C8-51 (часть A): описание скоупа, которому принадлежит группа переменных
 * из {@link VariableScopeGroup}.
 *
 * <p>Скоуп — это активность-владелец локальных переменных (например, тело
 * MI-инстанса подпроцесса, чьи {@code item}/{@code loopCounter} видны только
 * этому инстансу). Root-скоуп инстанса описывается {@code scope == null}
 * в группе, а не этим объектом.
 */
@Getter
@Setter
public class VariableScopeInfo {
    /** id activity-строки исполнения, чей скоуп описывается (не bpmn-элемента). */
    private UUID activityId;
    /** bpmn id элемента (как в схеме), {@code null} — если activity-строка уже удалена. */
    private String bpmnElementId;
    /** Тип BPMN-элемента (значения {@code BpmnElementType}, например {@code SUB_PROCESS}). */
    private String elementType;
    /** Статус activity-строки (значения {@code ActivityStatus}: CREATED/IN_PROGRESS/ERROR/COMPLETED/CANCELLED). */
    private String status;
    /**
     * Скоуп-владелец самой этой активности (scope id её токена), {@code null} —
     * если активность лежит в root-скоупе инстанса. Для вложенного MI тут будет
     * activityId внешнего MI-тела.
     */
    private UUID parentScopeActivityId;
}
