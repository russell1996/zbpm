package com.zorrodev.bpm.engine.bpmn.xml;

import com.zorrodev.bpm.engine.bpmn.xml.extension.AssignmentDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.AdHocModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.CalledElementModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.CalledDecisionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ConditionalFilterModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ExecutionListenersModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.FormDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.IoMappingModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.JobPriorityDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.PriorityDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.SubscriptionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.TaskDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.TaskHeadersModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.TaskListenersModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskFormModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.TaskScheduleModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.VersionTagModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ZeebeLoopCharacteristicsModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ZeebeScriptModel;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class ExtensionElements {
    @XmlElement(name = "taskDefinition", namespace = "http://camunda.org/schema/zeebe/1.0")
    private TaskDefinitionModel taskDefinition;
    @XmlElement(name = "assignmentDefinition", namespace = "http://camunda.org/schema/zeebe/1.0")
    private AssignmentDefinitionModel assignmentDefinition;
    @XmlElement(name = "formDefinition", namespace = "http://camunda.org/schema/zeebe/1.0")
    private FormDefinitionModel formDefinition;
    @XmlElement(name = "properties", namespace = "http://camunda.org/schema/zeebe/1.0")
    private PropertiesModel properties;
    @XmlElement(name = "calledElement", namespace = "http://camunda.org/schema/zeebe/1.0")
    private CalledElementModel calledElement;
    @XmlElement(name = "subscription", namespace = "http://camunda.org/schema/zeebe/1.0")
    private SubscriptionModel subscription;
    @XmlElement(name = "ioMapping", namespace = "http://camunda.org/schema/zeebe/1.0")
    private IoMappingModel ioMapping;
    @XmlElement(name = "calledDecision", namespace = "http://camunda.org/schema/zeebe/1.0")
    private CalledDecisionModel calledDecision;
    @XmlElement(name = "script", namespace = "http://camunda.org/schema/zeebe/1.0")
    private ZeebeScriptModel script;
    @XmlElement(name = "loopCharacteristics", namespace = "http://camunda.org/schema/zeebe/1.0")
    private ZeebeLoopCharacteristicsModel loopCharacteristics;
    @XmlElement(name = "versionTag", namespace = "http://camunda.org/schema/zeebe/1.0")
    private VersionTagModel versionTag;
    @XmlElement(name = "userTaskForm", namespace = "http://camunda.org/schema/zeebe/1.0")
    private java.util.List<UserTaskFormModel> userTaskForms;
    @XmlElement(name = "taskHeaders", namespace = "http://camunda.org/schema/zeebe/1.0")
    private TaskHeadersModel taskHeaders;
    @XmlElement(name = "taskSchedule", namespace = "http://camunda.org/schema/zeebe/1.0")
    private TaskScheduleModel taskSchedule;
    @XmlElement(name = "jobPriorityDefinition", namespace = "http://camunda.org/schema/zeebe/1.0")
    private JobPriorityDefinitionModel jobPriorityDefinition;
    @XmlElement(name = "priorityDefinition", namespace = "http://camunda.org/schema/zeebe/1.0")
    private PriorityDefinitionModel priorityDefinition;
    @XmlElement(name = "executionListeners", namespace = "http://camunda.org/schema/zeebe/1.0")
    private ExecutionListenersModel executionListeners;
    @XmlElement(name = "taskListeners", namespace = "http://camunda.org/schema/zeebe/1.0")
    private TaskListenersModel taskListeners;
    @XmlElement(name = "conditionalFilter", namespace = "http://camunda.org/schema/zeebe/1.0")
    private ConditionalFilterModel conditionalFilter;
    @XmlElement(name = "adHoc", namespace = "http://camunda.org/schema/zeebe/1.0")
    private AdHocModel adHoc;
}
