package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * WO-VT-1: тела import/export — тот же формат, что запрос старта
 * ({@code processDefinitionKey + variables[]}) плюс привязка и имя шаблона.
 */
@Getter
@Setter
public class PresetImportDTO {
    private String processDefinitionKey;
    private String targetKind;
    private String targetRef;
    private String name;
    private String description;
    private String visibility;
    private List<ProcessVariable> variables;
}
