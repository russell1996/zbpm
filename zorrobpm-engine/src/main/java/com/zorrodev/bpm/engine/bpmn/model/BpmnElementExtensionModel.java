package com.zorrodev.bpm.engine.bpmn.model;

import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BpmnElementExtensionModel {
    private ServiceTaskExtensionModel serviceTaskExtension;
    private ScriptTaskExtensionModel scriptTaskExtension;
    private BusinessRuleExtensionModel businessRuleExtension;
    private IoMappingExtensionModel ioMappingExtension;
    private MultiInstanceExtensionModel multiInstanceExtension;
    private UserTaskExtensionModel userTaskExtension;
    private ExclusiveGatewayExtensionModel exclusiveGatewayExtension;
    private TimerEventExtensionModel timerEventExtension;
    private MessageEventExtensionModel messageEventExtension;
    private CallActivityExtensionModel callActivityExtension;
    private SubProcessExtensionModel subProcessExtension;
    /**
     * WO-C8-32: ad-hoc subprocess metadata (internal mode). Set only on
     * {@code AD_HOC_SUB_PROCESS} elements; regular subprocesses keep
     * {@code subProcessExtension} untouched.
     */
    private AdHocSubProcessExtensionModel adHocSubProcessExtension;
    private BoundaryEventExtensionModel boundaryEventExtension;
    private EventDefinitionExtensionModel eventDefinition;
    /**
     * WO-C8-25: start execution listeners of gateway/event elements (part B of finding
     * A-5) — a SEPARATE field from {@code ServiceTaskExtensionModel.startListeners} ON
     * PURPOSE: the C8-11 service-task machinery must never see gateway/event listeners
     * (it assumes a service_tasks row, which these elements never have). Only the
     * element-listener phase paths read this field.
     */
    private List<ListenerModel> elementStartListeners;
}
