package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * WO-VT-1: создание шаблона. Привязка (ключ, вид, ref) после создания immutable.
 */
@Getter
@Setter
public class CreatePresetDTO {
    private String processDefinitionKey;
    private String targetKind;
    private String targetRef;
    private String name;
    private String description;
    private List<ProcessVariable> variables;
    private String visibility;
}
