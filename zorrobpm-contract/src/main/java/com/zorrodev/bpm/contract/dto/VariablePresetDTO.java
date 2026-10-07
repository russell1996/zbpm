package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WO-VT-1: шаблон переменных — чтение/список.
 *
 * <p>{@code targetRef} — null для START (в БД хранится пустая строка, см.
 * changeset 20261007-120). {@code favorite} — избранное у вызывающего.
 */
@Getter
@Setter
public class VariablePresetDTO {
    private UUID id;
    private String processDefinitionKey;
    private String targetKind;
    private String targetRef;
    private String name;
    private String description;
    private List<ProcessVariable> variables;
    private UUID ownerUserId;
    private String visibility;
    private boolean favorite;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;
}
