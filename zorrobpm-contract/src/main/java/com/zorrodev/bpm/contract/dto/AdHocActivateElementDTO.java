package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * WO-C8-33: instruction to activate a single BPMN element within an ad-hoc
 * sub-process, optionally with variables scoped to that element. Mirrors Camunda
 * {@code JobResultActivateElement} ({@code elementId} + nullable {@code variables});
 * variables use our {@link ProcessVariable} model (typed), not a raw map — the whole
 * engine/REST layer speaks {@code ProcessVariable}, including {@code CompleteTaskDTO}.
 */
@Getter
@Setter
public class AdHocActivateElementDTO {
    private String elementId;
    private List<ProcessVariable> variables = new ArrayList<>();
}
