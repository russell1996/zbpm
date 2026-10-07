package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

@Getter
@Setter
public class BpmnElementModel {
    @Getter
    @Setter
    private String id;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private BpmnProcessDefinitionModel processDefinition;
    @Getter
    @Setter
    private BpmnElementType type;
    @Getter
    private List<String> outgoing = new LinkedList<>();
    @Getter
    private List<String> incoming = new LinkedList<>();
    @Getter
    @Setter
    private BpmnElementExtensionModel extensions;
    /** Set on the (flattened) start event of an event sub-process to the owning event-subprocess id, so
     *  it is not mistaken for a process-level start event (see {@code BpmnProcessDefinitionModel}). */
    @Getter
    @Setter
    private String eventSubProcessId;

    /**
     * WO-C8-38: lexical parent container element id in the BPMN source
     * ({@code subProcess}/{@code transaction}/{@code adHocSubProcess} that directly contains
     * this element), {@code null} for process-level elements. The runtime model is FLAT
     * (nested flow nodes live in the same map as top-level ones), so without this field
     * «which joins are inside scope S» is not answerable from the model.
     *
     * <p>Set once at parse time ({@code BpmnParseServiceImpl}); read-only afterwards and
     * therefore safe to share via the cached definition model. Pure structure — no runtime
     * state, no schema change.
     */
    @Getter
    @Setter
    private String parentContainerElementId;
}
