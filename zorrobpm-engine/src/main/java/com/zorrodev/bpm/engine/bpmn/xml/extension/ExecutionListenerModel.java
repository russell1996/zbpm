package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class ExecutionListenerModel {
    @XmlAttribute
    private String eventType;
    @XmlAttribute
    private String type;
    @XmlAttribute(name = "retries")
    private Integer retries;
    /**
     * WO-C8-7r2: nested {@code zeebe:taskHeaders} of the listener itself (schema property
     * {@code headers: TaskHeaders} on {@code ExecutionListener}) — delivered with the
     * listener's job, merged over the element headers (listener wins per the docs).
     */
    @XmlElement(name = "taskHeaders", namespace = "http://camunda.org/schema/zeebe/1.0")
    private TaskHeadersModel taskHeaders;
}
