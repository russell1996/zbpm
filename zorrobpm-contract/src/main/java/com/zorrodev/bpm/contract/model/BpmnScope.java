package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A container of flow nodes and the sequence flows between them. Used both for the process itself
 * and for the body of an embedded sub-process (nested under {@link BpmnNode#getChildren()}).
 */
@Getter
@Setter
public class BpmnScope {
    private List<BpmnNode> nodes = new ArrayList<>();
    private List<BpmnFlow> flows = new ArrayList<>();
}
