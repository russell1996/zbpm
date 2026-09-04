package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class ExecutionListenersModel {
    @XmlElement(name = "executionListener", namespace = "http://camunda.org/schema/zeebe/1.0")
    private List<ExecutionListenerModel> listeners;
}
