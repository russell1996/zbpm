package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <scriptTask>}: a FEEL script evaluated synchronously while the token flows through. Two ways to
 * carry it: the BPMN-standard inline {@code <script>} child (with {@code scriptFormat}/{@code resultVariable}
 * attributes), or — the Camunda 8 way — {@code <zeebe:script expression=… resultVariable=…>} in
 * {@code extensionElements}. {@code resultVariable} names the process variable the result is written to.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnScriptTaskModel extends BpmnBaseElementModel {
    @XmlAttribute
    private String scriptFormat;
    @XmlAttribute
    private String resultVariable;
    @XmlElement(name = "script", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private String script;
    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;
}
