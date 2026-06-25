package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A flow node of a BPMN process (event, task, gateway, sub-process, call activity, boundary event).
 *
 * <ul>
 *   <li>{@code type} — the BPMN element name, e.g. {@code startEvent}, {@code serviceTask},
 *       {@code exclusiveGateway}, {@code subProcess}, {@code boundaryEvent}.</li>
 *   <li>{@code eventDefinition} — for events/boundaries: {@code message}, {@code timer},
 *       {@code error}, {@code signal}, {@code escalation}, {@code terminate}, … (null otherwise).</li>
 *   <li>{@code properties} — type-specific details (resolved message/error/signal names, timer
 *       expression, service-task job type, user-task assignment, called process id, default flow,
 *       boundary {@code attachedToRef}/{@code cancelActivity}, …).</li>
 *   <li>{@code boundaryEvents} — boundary events attached to this node (nested for the UI).</li>
 *   <li>{@code children} — the body of an embedded sub-process; null for every other node.</li>
 * </ul>
 */
@Getter
@Setter
public class BpmnNode {
    private String id;
    private String name;
    private String type;
    private String eventDefinition;
    /** BPMN documentation text, surfaced in the UI as element "requirements". */
    private String documentation;
    private List<String> incoming = new ArrayList<>();
    private List<String> outgoing = new ArrayList<>();
    private Map<String, Object> properties = new LinkedHashMap<>();
    private List<BpmnNode> boundaryEvents = new ArrayList<>();
    private BpmnScope children;
}
