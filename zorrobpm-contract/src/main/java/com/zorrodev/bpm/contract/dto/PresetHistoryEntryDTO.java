package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WO-VT-1 (п.6-бис): одна запись истории шаблона — кто/когда/действие + снапшоты
 * переменных до/после (только значения шаблона, без токенов/секретов).
 */
@Getter
@Setter
public class PresetHistoryEntryDTO {
    private UUID id;
    private String action;
    private UUID actorUserId;
    private Instant at;
    private List<ProcessVariable> variablesBefore;
    private List<ProcessVariable> variablesAfter;
}
