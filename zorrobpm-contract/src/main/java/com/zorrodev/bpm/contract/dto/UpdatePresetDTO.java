package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * WO-VT-1: правка шаблона. Все поля кроме {@code version} опциональны (null = не
 * менять); привязка здесь НЕ меняется (смена = новый шаблон).
 */
@Getter
@Setter
public class UpdatePresetDTO {
    private String name;
    private String description;
    private List<ProcessVariable> variables;
    private String visibility;
    private int version;
}
