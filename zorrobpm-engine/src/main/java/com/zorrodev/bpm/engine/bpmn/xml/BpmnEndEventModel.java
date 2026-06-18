package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BpmnEndEventModel extends BpmnBaseElementModel {
    @XmlElement(name = "terminateEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private Object terminateEventDefinition;
}
