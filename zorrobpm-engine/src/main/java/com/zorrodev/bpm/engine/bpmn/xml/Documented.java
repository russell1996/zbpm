package com.zorrodev.bpm.engine.bpmn.xml;

/**
 * BPMN elements carrying {@code <documentation>} text surfaced in the UI.
 * Exists because several JAXB models (catch/throw events, sub-processes,
 * boundary events) do not share {@link BpmnBaseElementModel} but still need
 * uniform documentation collection in
 * {@code BpmnStructureServiceImpl#applyDocumentation}.
 */
public interface Documented {
    String getId();
    String getDocumentation();
}
