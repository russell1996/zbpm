package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <scriptTask>}: an inline script evaluated synchronously while the token flows through. The
 * {@code <script>} child holds the expression (FEEL); {@code resultVariable} names the process variable
 * the script's result is written to.
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
}
