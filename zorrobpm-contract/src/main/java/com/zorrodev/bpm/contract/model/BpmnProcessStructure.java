package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The full, nested structure of a deployed BPMN process definition, intended for inspection and UI
 * rendering: process metadata plus its flow nodes (with sub-process bodies and attached boundary
 * events nested) and the sequence flows between them.
 */
@Getter
@Setter
public class BpmnProcessStructure {
    /** Process definition id (the deployment UUID), not the BPMN process key. */
    private UUID id;
    /** BPMN process id / key. */
    private String key;
    private Integer version;
    private String name;
    /** Process-level BPMN documentation (shown as overall requirements). */
    private String documentation;
    private List<BpmnNode> nodes = new ArrayList<>();
    private List<BpmnFlow> flows = new ArrayList<>();
}
