package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.BusinessRuleExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.CallActivityExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ConditionalFilter;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ScriptTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.IoMappingModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.HeaderModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.MappingModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ZeebeLoopCharacteristicsModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.TaskHeadersModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.JobPriorityDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ExecutionListenerModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ExecutionListenersModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ConditionalFilterModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.TaskListenerModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ZeebeScriptModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionType;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.bpmn.xml.*;
import com.zorrodev.bpm.engine.bpmn.xml.extension.CalledElementModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.VersionTagModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnConditionExpressionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ExclusiveGatewayExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.xml.SecureXmlParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class BpmnParseServiceImpl implements BpmnParseService {

    /**
     * WO-DIFF-1: kept for the pre-existing no-arg construction in unit tests
     * (production code path injects FileService via Spring — single constructor
     * since this change keeps autowiring unambiguous). No FileService field is
     * retained: nested-container extraction uses the in-hand raw source only.
     */
    public BpmnParseServiceImpl() {
    }

    public BpmnParseServiceImpl(com.zorrodev.bpm.engine.service.FileService fileService) {
        // FileService is not needed for container ioMapping extraction (raw source
        // is passed explicitly); the parameter stays so existing Spring/test
        // wiring does not change.
    }

    @Override
    public com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel parse(String bpmn) throws BpmnParseException {
        try {
            // WO-DIFF-1: the raw source rides along as an explicit parameter (no field, no
            // state) so container-level ioMapping can be extracted without JAXB changes.
            BpmnDefinitionsModel definitions = SecureXmlParser.unmarshal(bpmn, BpmnDefinitionsModel.class);
            BpmnProcessDefinitionModel process = definitions.getProcess();

            checkBpmn(process);

            Map<String, String> messageNames = new HashMap<>();
            Map<String, String> messageKeys = new HashMap<>();
            if (definitions.getMessages() != null) {
                for (com.zorrodev.bpm.engine.bpmn.xml.BpmnMessageModel message : definitions.getMessages()) {
                    messageNames.put(message.getId(), message.getName());
                    String key = Optional.ofNullable(message.getExtensionElements())
                        .map(ExtensionElements::getSubscription)
                        .map(com.zorrodev.bpm.engine.bpmn.xml.extension.SubscriptionModel::getCorrelationKey)
                        .orElse(null);
                    if (key != null) {
                        messageKeys.put(message.getId(), key);
                    }
                }
            }

            // definitions-level <error>/<signal>/<escalation> registries: resolve refs to codes/names
            Map<String, String> errorCodes = new HashMap<>();
            if (definitions.getErrors() != null) {
                for (BpmnErrorModel error : definitions.getErrors()) {
                    errorCodes.put(error.getId(), error.getErrorCode() != null ? error.getErrorCode() : error.getName());
                }
            }
            Map<String, String> signalNames = new HashMap<>();
            if (definitions.getSignals() != null) {
                for (BpmnSignalModel signal : definitions.getSignals()) {
                    signalNames.put(signal.getId(), signal.getName());
                }
            }
            Map<String, String> escalationCodes = new HashMap<>();
            if (definitions.getEscalations() != null) {
                for (BpmnEscalationModel escalation : definitions.getEscalations()) {
                    escalationCodes.put(escalation.getId(), escalation.getEscalationCode() != null ? escalation.getEscalationCode() : escalation.getName());
                }
            }
            EventDefinitionRegistry registry = new EventDefinitionRegistry(errorCodes, signalNames, escalationCodes);

            com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel pd = new com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel();
            pd.setExecutionPlatformVersion(definitions.getExecutionPlatformVersion());
            pd.setKey(process.getId());
            pd.setName(process.getName());
            // WO-C8-3: process-level zeebe:versionTag (nullable — untagged versions never match a tag query).
            pd.setVersionTag(Optional.ofNullable(process.getExtensionElements())
                .map(ExtensionElements::getVersionTag)
                .map(VersionTagModel::getValue)
                .orElse(null));
            // WO-ENG-17: process-level camunda:historyTimeToLive (nullable — raw passthrough,
            // parsing/validation happens at deploy time in ProcessDefinitionServiceImpl).
            pd.setHistoryTimeToLive(process.getHistoryTimeToLive());
            // WO-C8-13 (A-1): process-level zeebe:jobPriorityDefinition — default job priority
            // for all service tasks of the process unless overridden on the task itself.
            pd.setDefaultJobPriority(Optional.ofNullable(process.getExtensionElements())
                .map(ExtensionElements::getJobPriorityDefinition)
                .map(JobPriorityDefinitionModel::getPriority)
                .orElse(null));
            // WO-C8-12: process-level zeebe:userTaskForm list (embedded form JSON, id+body).
            pd.setUserTaskForms(Optional.ofNullable(process.getExtensionElements())
                .map(ExtensionElements::getUserTaskForms)
                .orElse(List.of()));

            for (BpmnStartEventModel startEvent : process.getStartEvents()) {
                BpmnElementModel element = toElementModel(startEvent);
                element.setProcessDefinition(pd);
                attachEventDefinition(element, startEvent.getErrorEventDefinition(), startEvent.getSignalEventDefinition(),
                    startEvent.getEscalationEventDefinition(), startEvent.getConditionalEventDefinition(), null, null, registry);
                if (startEvent.getMessageEventDefinition() != null) {
                    if (element.getExtensions() == null) {
                        element.setExtensions(new BpmnElementExtensionModel());
                    }
                    MessageEventExtensionModel msg = new MessageEventExtensionModel();
                    String ref = startEvent.getMessageEventDefinition().getMessageRef();
                    msg.setMessageName(messageNames.getOrDefault(ref, ref));
                    element.getExtensions().setMessageEventExtension(msg);
                }
                if (startEvent.getTimerEventDefinition() != null) {
                    if (element.getExtensions() == null) {
                        element.setExtensions(new BpmnElementExtensionModel());
                    }
                    TimerEventExtensionModel timer = new TimerEventExtensionModel();
                    if (startEvent.getTimerEventDefinition().getTimeDate() != null) {
                        timer.setType(TimerEventType.DATE);
                        timer.setExpression(startEvent.getTimerEventDefinition().getTimeDate());
                    } else if (startEvent.getTimerEventDefinition().getTimeDuration() != null) {
                        timer.setType(TimerEventType.DURATION);
                        timer.setExpression(startEvent.getTimerEventDefinition().getTimeDuration());
                    } else if (startEvent.getTimerEventDefinition().getTimeCycle() != null) {
                        timer.setType(TimerEventType.CYCLE);
                        timer.setExpression(startEvent.getTimerEventDefinition().getTimeCycle());
                    }
                    element.getExtensions().setTimerEventExtension(timer);
                }
                pd.addElement(element);
                // the plain (none) top-level start is where instances begin; message/timer starts
                // are triggers handled separately and must not be treated as the process start
                if (element.getType() == BpmnElementType.START_EVENT) {
                    pd.setStartEvent(element);
                }
                // WO-C8-26: formDefinition of the plain start event (docs: linking targets the
                // none start event). Scalar startFormKey below stays untouched (fallback).
                if (element.getType() == BpmnElementType.START_EVENT
                    && startEvent.getExtensionElements() != null
                    && startEvent.getExtensionElements().getFormDefinition() != null) {
                    var startFormDefinition = startEvent.getExtensionElements().getFormDefinition();
                    pd.setStartFormId(startFormDefinition.getFormId());
                    pd.setStartFormBindingType(startFormDefinition.getBindingType());
                    pd.setStartFormVersionTag(startFormDefinition.getVersionTag());
                }
                if (startEvent.getExtensionElements() != null && startEvent.getExtensionElements().getProperties() != null && startEvent.getExtensionElements().getProperties().getProperties() != null) {
                    List<PropertyModel> properties = startEvent.getExtensionElements().getProperties().getProperties();
                    for (PropertyModel property : properties) {
                        if (property.getName().equals("formKey")) {
                            pd.setStartFormKey(property.getValue());
                        }
                    }
                }
            }

            for (BpmnEndEventModel endEvent : process.getEndEvents()) {
                BpmnElementModel element = toElementModel(endEvent);
                element.setProcessDefinition(pd);
                attachEventDefinition(element, endEvent.getErrorEventDefinition(), endEvent.getSignalEventDefinition(),
                    endEvent.getEscalationEventDefinition(), null, null, endEvent.getCompensateEventDefinition(), registry);
                pd.addElement(element);
            }

            if (Optional.ofNullable(process.getServiceTasks()).isPresent()) {
                for (BpmnServiceTaskModel serviceTask : process.getServiceTasks()) {
                    BpmnElementModel element = toElementModel(serviceTask);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getSendTasks()).isPresent()) {
                for (BpmnSendTaskModel sendTask : process.getSendTasks()) {
                    BpmnElementModel element = toElementModel(sendTask, messageNames);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getReceiveTasks()).isPresent()) {
                for (BpmnReceiveTaskModel receiveTask : process.getReceiveTasks()) {
                    BpmnElementModel element = toElementModel(receiveTask, messageNames, messageKeys);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getManualTasks()).isPresent()) {
                for (BpmnManualTaskModel manualTask : process.getManualTasks()) {
                    BpmnElementModel element = toElementModel(manualTask);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            // WO-DIFF-9: bare <bpmn:task> (untyped) — no-op pass-through, like manual task.
            if (Optional.ofNullable(process.getTasks()).isPresent()) {
                for (BpmnTaskModel task : process.getTasks()) {
                    BpmnElementModel element = toElementModel(task);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getScriptTasks()).isPresent()) {
                for (BpmnScriptTaskModel scriptTask : process.getScriptTasks()) {
                    BpmnElementModel element = toElementModel(scriptTask);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getBusinessRuleTasks()).isPresent()) {
                for (BpmnBusinessRuleTaskModel businessRuleTask : process.getBusinessRuleTasks()) {
                    BpmnElementModel element = toElementModel(businessRuleTask);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getUserTasks()).isPresent()) {
                for (BpmnUserTaskModel userTask : process.getUserTasks()) {
                    BpmnElementModel element = toElementModel(userTask);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getExclusiveGateways()).isPresent()) {
                for (BpmnExclusiveGatewayModel exclusiveGateway : process.getExclusiveGateways()) {
                    BpmnElementModel element = toElementModel(exclusiveGateway);
                    element.setProcessDefinition(pd);
                    element.setType(BpmnElementType.EXCLUSIVE_GATEWAY);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getParallelGateways()).isPresent()) {
                for (BpmnParallelGatewayModel parallelGateway : process.getParallelGateways()) {
                    BpmnElementModel element = toElementModel(parallelGateway);
                    element.setProcessDefinition(pd);
                    element.setType(BpmnElementType.PARALLEL_GATEWAY);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getEventBasedGateways()).isPresent()) {
                for (BpmnEventBasedGatewayModel eventGateway : process.getEventBasedGateways()) {
                    BpmnElementModel element = new BpmnElementModel();
                    element.setId(eventGateway.getId());
                    element.setName(eventGateway.getName());
                    element.setType(BpmnElementType.EVENT_BASED_GATEWAY);
                    element.setIncoming(eventGateway.getIncoming());
                    element.setOutgoing(eventGateway.getOutgoing());
                    // WO-C8-25: start listeners park the gateway before its handler runs.
                    attachElementStartListeners(element, eventGateway.getExtensionElements());
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getInclusiveGateways()).isPresent()) {
                for (BpmnInclusiveGatewayModel inclusiveGateway : process.getInclusiveGateways()) {
                    BpmnElementModel element = new BpmnElementModel();
                    element.setId(inclusiveGateway.getId());
                    element.setName(inclusiveGateway.getName());
                    element.setType(BpmnElementType.INCLUSIVE_GATEWAY);
                    element.setIncoming(inclusiveGateway.getIncoming());
                    element.setOutgoing(inclusiveGateway.getOutgoing());
                    if (inclusiveGateway.getDefaultFlow() != null) {
                        element.setExtensions(new BpmnElementExtensionModel());
                        element.getExtensions().setExclusiveGatewayExtension(new ExclusiveGatewayExtensionModel());
                        element.getExtensions().getExclusiveGatewayExtension().setDefaultFlowId(inclusiveGateway.getDefaultFlow());
                    }
                    // WO-C8-25: start listeners park the gateway before its handler runs.
                    attachElementStartListeners(element, inclusiveGateway.getExtensionElements());
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getFlows()).isPresent()) {
                for (BpmnSequenceFlowModel flow : process.getFlows()) {
                    BpmnFlowModel element = toFlowModel(flow);
                    pd.addFlow(element);
                }
            }
            if (process.getIntermediateCatchEvents() != null) {
                for (BpmnIntermediateCatchEventModel catchEvent : process.getIntermediateCatchEvents()) {
                    BpmnElementModel element = toElementModel(catchEvent, messageNames, messageKeys);
                    element.setProcessDefinition(pd);
                    attachEventDefinition(element, null, catchEvent.getSignalEventDefinition(), null,
                        catchEvent.getConditionalEventDefinition(), catchEvent.getLinkEventDefinition(), null, registry);
                    pd.addElement(element);
                }
            }

            if (process.getIntermediateThrowEvents() != null) {
                for (BpmnIntermediateThrowEventModel throwEvent : process.getIntermediateThrowEvents()) {
                    BpmnElementModel element = toElementModel(throwEvent, messageNames);
                    element.setProcessDefinition(pd);
                    attachEventDefinition(element, null, throwEvent.getSignalEventDefinition(),
                        throwEvent.getEscalationEventDefinition(), null, throwEvent.getLinkEventDefinition(),
                        throwEvent.getCompensateEventDefinition(), registry);
                    pd.addElement(element);
                }
            }

            if (process.getCallActivities() != null) {
                for (BpmnCallActivityModel callActivity : process.getCallActivities()) {
                    BpmnElementModel element = toElementModel(callActivity);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }

            if (process.getSubProcesses() != null) {
                for (BpmnSubProcessModel subProcess : process.getSubProcesses()) {
                    BpmnElementModel element = toSubProcessElement(subProcess, bpmn, pd, registry, messageNames, messageKeys);
                    element.setProcessDefinition(pd);
                    // WO-DIFF-1: container-level zeebe:ioMapping of a plain subProcess/transaction.
                    // JAXB has no field for it on BpmnSubProcessModel (the ad-hoc subclass owns the
                    // only extensionElements binding — promoting it to the parent would rebind ad-hoc's
                    // elements and blind the ad-hoc reader), so the raw XML is re-scanned for the
                    // container's own <bpmn:extensionElements> (same unmarshalled source string, no
                    // second parser, narrow child-only match — nested elements keep their own mappings).
                    attachSubProcessIoMapping(element, bpmn, subProcess.getId());
                    pd.addElement(element);
                }
            }

            // a <transaction> is an embedded subprocess (same flattening/scope execution); cancel semantics
            // are carried by its cancel-end event and cancel boundary, not the container type
            if (process.getTransactions() != null) {
                for (BpmnSubProcessModel transaction : process.getTransactions()) {
                    BpmnElementModel element = toSubProcessElement(transaction, bpmn, pd, registry, messageNames, messageKeys);
                    element.setProcessDefinition(pd);
                    attachSubProcessIoMapping(element, bpmn, transaction.getId());
                    pd.addElement(element);
                }
            }

            // WO-C8-32: ad-hoc subprocesses bind a separate element name, so they never
            // leak into the regular-subprocess path above (own type + own handler).
            if (process.getAdHocSubProcesses() != null) {
                for (BpmnAdHocSubProcessModel adHoc : process.getAdHocSubProcesses()) {
                    BpmnElementModel element = toAdHocSubProcessElement(adHoc, bpmn, pd, registry, messageNames, messageKeys);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }

            if (process.getBoundaryEvents() != null) {
                for (BpmnBoundaryEventModel boundaryEvent : process.getBoundaryEvents()) {
                    boolean timer = boundaryEvent.getTimerEventDefinition() != null;
                    boolean error = boundaryEvent.getErrorEventDefinition() != null;
                    boolean message = boundaryEvent.getMessageEventDefinition() != null;
                    boolean signal = boundaryEvent.getSignalEventDefinition() != null;
                    boolean escalation = boundaryEvent.getEscalationEventDefinition() != null;
                    boolean conditional = boundaryEvent.getConditionalEventDefinition() != null;
                    boolean compensation = boundaryEvent.getCompensateEventDefinition() != null;
                    boolean cancel = boundaryEvent.getCancelEventDefinition() != null;
                    if (!timer && !error && !message && !signal && !escalation && !conditional && !compensation && !cancel) {
                        continue; // only timer, error, message, signal, escalation, conditional, compensation and cancel boundaries are executable today
                    }
                    BpmnElementModel element = toBoundaryElement(boundaryEvent);
                    element.setProcessDefinition(pd);
                    attachEventDefinition(element, boundaryEvent.getErrorEventDefinition(), boundaryEvent.getSignalEventDefinition(), boundaryEvent.getEscalationEventDefinition(), boundaryEvent.getConditionalEventDefinition(), null, null, registry);
                    if (message) {
                        MessageEventExtensionModel msg = new MessageEventExtensionModel();
                        String ref = boundaryEvent.getMessageEventDefinition().getMessageRef();
                        msg.setMessageName(messageNames.getOrDefault(ref, ref));
                        msg.setCorrelationKeyExpression(messageKeys.get(ref));
                        element.getExtensions().setMessageEventExtension(msg);
                    }
                    pd.addElement(element);
                }
            }

            // resolve each compensation boundary's handler from the <association> that links them
            if (process.getAssociations() != null) {
                for (BpmnElementModel element : pd.getElements()) {
                    if (element.getType() != BpmnElementType.COMPENSATION_BOUNDARY_EVENT) {
                        continue;
                    }
                    String handlerId = resolveCompensationHandler(element.getId(), process.getAssociations());
                    if (handlerId != null) {
                        element.getExtensions().getBoundaryEventExtension().setCompensationHandlerId(handlerId);
                    }
                }
            }

            return pd;
        } catch (Exception e) {
            throw new BpmnParseException(e);
        }
    }

    /** Resolved definitions-level declarations used to turn {@code xxxRef}s into codes/names. */
    private record EventDefinitionRegistry(Map<String, String> errorCodes,
                                           Map<String, String> signalNames,
                                           Map<String, String> escalationCodes) {
    }

    /**
     * Builds the resolved {@link EventDefinitionExtensionModel} for an event element, given whichever
     * raw event definitions were parsed from its XML (all but one are typically null). Attaches it to
     * the element's extensions so handlers can read the event kind and its resolved code/name/expression.
     */
    private void attachEventDefinition(BpmnElementModel element,
                                       BpmnErrorEventDefinitionModel error,
                                       BpmnSignalEventDefinitionModel signal,
                                       BpmnEscalationEventDefinitionModel escalation,
                                       BpmnConditionalEventDefinitionModel conditional,
                                       BpmnLinkEventDefinitionModel link,
                                       BpmnCompensateEventDefinitionModel compensate,
                                       EventDefinitionRegistry registry) {
        EventDefinitionExtensionModel def = new EventDefinitionExtensionModel();
        if (error != null) {
            def.setType(EventDefinitionType.ERROR);
            def.setReference(error.getErrorRef());
            def.setCode(registry.errorCodes().getOrDefault(error.getErrorRef(), error.getErrorRef()));
        } else if (signal != null) {
            def.setType(EventDefinitionType.SIGNAL);
            def.setReference(signal.getSignalRef());
            def.setName(registry.signalNames().getOrDefault(signal.getSignalRef(), signal.getSignalRef()));
        } else if (escalation != null) {
            def.setType(EventDefinitionType.ESCALATION);
            def.setReference(escalation.getEscalationRef());
            def.setCode(registry.escalationCodes().getOrDefault(escalation.getEscalationRef(), escalation.getEscalationRef()));
        } else if (conditional != null) {
            def.setType(EventDefinitionType.CONDITIONAL);
            def.setExpression(conditional.getCondition());
            // WO-C8-29: conditionalFilter narrows re-evaluation (parsed here so the
            // trigger point only consults the resolved model, never raw XML).
            if (conditional.getExtensionElements() != null
                && conditional.getExtensionElements().getConditionalFilter() != null) {
                ConditionalFilterModel filter = conditional.getExtensionElements().getConditionalFilter();
                def.setConditionalFilter(ConditionalFilter.parse(
                    filter.getVariableNames(), filter.getVariableEvents()));
            }
        } else if (link != null) {
            def.setType(EventDefinitionType.LINK);
            def.setName(link.getName());
        } else if (compensate != null) {
            def.setType(EventDefinitionType.COMPENSATE);
            def.setReference(compensate.getActivityRef());
        } else {
            return;
        }
        if (element.getExtensions() == null) {
            element.setExtensions(new BpmnElementExtensionModel());
        }
        element.getExtensions().setEventDefinition(def);
    }

    private BpmnElementModel toBoundaryElement(BpmnBoundaryEventModel boundaryEvent) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(boundaryEvent.getId());
        element.setName(boundaryEvent.getName());
        if (boundaryEvent.getOutgoing() != null) {
            element.getOutgoing().addAll(boundaryEvent.getOutgoing());
        }

        element.setExtensions(new BpmnElementExtensionModel());

        com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel boundaryExt = new com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef(boundaryEvent.getAttachedToRef());
        // BPMN: cancelActivity defaults to true (interrupting) when the attribute is absent
        boundaryExt.setInterrupting(boundaryEvent.getCancelActivity() == null || boundaryEvent.getCancelActivity());
        element.getExtensions().setBoundaryEventExtension(boundaryExt);

        if (boundaryEvent.getTimerEventDefinition() != null) {
            element.setType(BpmnElementType.BOUNDARY_TIMER_EVENT);
            TimerEventExtensionModel timer = new TimerEventExtensionModel();
            if (boundaryEvent.getTimerEventDefinition().getTimeDate() != null) {
                timer.setType(TimerEventType.DATE);
                timer.setExpression(boundaryEvent.getTimerEventDefinition().getTimeDate());
            } else if (boundaryEvent.getTimerEventDefinition().getTimeDuration() != null) {
                timer.setType(TimerEventType.DURATION);
                timer.setExpression(boundaryEvent.getTimerEventDefinition().getTimeDuration());
            } else if (boundaryEvent.getTimerEventDefinition().getTimeCycle() != null) {
                timer.setType(TimerEventType.CYCLE);
                timer.setExpression(boundaryEvent.getTimerEventDefinition().getTimeCycle());
            }
            element.getExtensions().setTimerEventExtension(timer);
        } else if (boundaryEvent.getErrorEventDefinition() != null) {
            // error boundaries are always interrupting; the error code is resolved into the
            // element's eventDefinition extension by attachEventDefinition in the caller
            element.setType(BpmnElementType.ERROR_BOUNDARY_EVENT);
        } else if (boundaryEvent.getMessageEventDefinition() != null) {
            // the message name is resolved into messageEventExtension by the caller
            element.setType(BpmnElementType.MESSAGE_BOUNDARY_EVENT);
        } else if (boundaryEvent.getSignalEventDefinition() != null) {
            // the signal name is resolved into the eventDefinition extension by the caller
            element.setType(BpmnElementType.SIGNAL_BOUNDARY_EVENT);
        } else if (boundaryEvent.getEscalationEventDefinition() != null) {
            // the escalation code is resolved into the eventDefinition extension by the caller;
            // escalation boundaries are interrupting or non-interrupting per cancelActivity
            element.setType(BpmnElementType.ESCALATION_BOUNDARY_EVENT);
        } else if (boundaryEvent.getConditionalEventDefinition() != null) {
            // the FEEL condition is resolved into the eventDefinition extension by the caller;
            // conditional boundaries are interrupting or non-interrupting per cancelActivity
            element.setType(BpmnElementType.CONDITIONAL_BOUNDARY_EVENT);
        } else if (boundaryEvent.getCompensateEventDefinition() != null) {
            // compensation boundary: registers a handler (resolved from <association>) to run on a
            // compensation throw; it never fires/cancels the host like other boundaries
            element.setType(BpmnElementType.COMPENSATION_BOUNDARY_EVENT);
        } else if (boundaryEvent.getCancelEventDefinition() != null) {
            // cancel boundary on a transaction: flow continues from here when the transaction is cancelled
            element.setType(BpmnElementType.CANCEL_BOUNDARY_EVENT);
        }

        return element;
    }

    private BpmnElementModel toSubProcessElement(BpmnSubProcessModel sub, String rawBpmn, com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel pd, EventDefinitionRegistry registry, Map<String, String> messageNames, Map<String, String> messageKeys) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(sub.getId());
        element.setName(sub.getName());
        boolean eventSubProcess = Boolean.TRUE.equals(sub.getTriggeredByEvent());
        element.setType(eventSubProcess ? BpmnElementType.EVENT_SUB_PROCESS : BpmnElementType.SUB_PROCESS);
        if (sub.getIncoming() != null) {
            element.getIncoming().addAll(sub.getIncoming());
        }
        if (sub.getOutgoing() != null) {
            element.getOutgoing().addAll(sub.getOutgoing());
        }

        com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel ext = new com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel();
        ext.setEventSubProcess(eventSubProcess);

        // flatten nested flow nodes and flows into the same process definition
        if (sub.getStartEvents() != null) {
            for (BpmnStartEventModel start : sub.getStartEvents()) {
                BpmnElementModel child = toElementModel(start);
                child.setProcessDefinition(pd);
                if (eventSubProcess) {
                    // mark the start so it is not collected as a process-level start; record its trigger
                    child.setEventSubProcessId(sub.getId());
                    ext.setInterrupting(start.getIsInterrupting() == null || start.getIsInterrupting());
                    if (start.getMessageEventDefinition() != null) {
                        String ref = start.getMessageEventDefinition().getMessageRef();
                        ext.setTriggerMessageName(messageNames.getOrDefault(ref, ref));
                    } else if (start.getSignalEventDefinition() != null) {
                        String ref = start.getSignalEventDefinition().getSignalRef();
                        ext.setTriggerSignalName(registry.signalNames().getOrDefault(ref, ref));
                    } else if (start.getErrorEventDefinition() != null) {
                        String ref = start.getErrorEventDefinition().getErrorRef();
                        ext.setErrorTriggered(true);
                        ext.setTriggerErrorCode(ref == null ? null : registry.errorCodes().getOrDefault(ref, ref));
                    } else if (start.getTimerEventDefinition() != null) {
                        TimerEventExtensionModel timer = new TimerEventExtensionModel();
                        if (start.getTimerEventDefinition().getTimeDate() != null) {
                            timer.setType(TimerEventType.DATE);
                            timer.setExpression(start.getTimerEventDefinition().getTimeDate());
                        } else if (start.getTimerEventDefinition().getTimeDuration() != null) {
                            timer.setType(TimerEventType.DURATION);
                            timer.setExpression(start.getTimerEventDefinition().getTimeDuration());
                        } else if (start.getTimerEventDefinition().getTimeCycle() != null) {
                            timer.setType(TimerEventType.CYCLE);
                            timer.setExpression(start.getTimerEventDefinition().getTimeCycle());
                        }
                        ext.setTriggerTimer(timer);
                    }
                }
                pd.addElement(child);
                ext.setStartEventId(child.getId());
            }
        }
        if (sub.getEndEvents() != null) {
            for (BpmnEndEventModel end : sub.getEndEvents()) {
                BpmnElementModel child = toElementModel(end);
                child.setProcessDefinition(pd);
                attachEventDefinition(child, end.getErrorEventDefinition(), end.getSignalEventDefinition(),
                    end.getEscalationEventDefinition(), null, null, end.getCompensateEventDefinition(), registry);
                pd.addElement(child);
            }
        }
        flattenSubProcessChildren(sub, rawBpmn, pd, registry, messageNames, messageKeys);

        element.setExtensions(new BpmnElementExtensionModel());
        element.getExtensions().setSubProcessExtension(ext);
        // WO-DIFF-1: the container's own ioMapping is attached by the CALLER
        // (attachSubProcessIoMapping at each toSubProcessElement call site — it holds
        // the raw source there), not here: this method is also reached from nested
        // recursion paths where the raw source is not available.
        return element;
    }

    /**
     * WO-DIFF-1: attaches a plain subProcess/transaction container's OWN
     * {@code zeebe:ioMapping} (input seed / output promote) to its element.
     * The container has no JAXB {@code extensionElements} field (the only binding
     * lives on the ad-hoc subclass and must stay there — see its javadoc), so the
     * raw BPMN source is scanned for the container's direct
     * {@code <bpmn:extensionElements>} child block, and the block is unmarshalled
     * through the SAME {@code ExtensionElements} JAXB type the task readers use
     * (no simplified copy: the very same {@code attachIoMapping} maps it).
     * Narrow by construction: only the container's own direct block (match ends at
     * the first nested flow-node open tag), never nested elements' blocks; absent
     * block or absent ioMapping → silent no-op (plain sub without mappings is the
     * common case — zero behaviour change for it).
     */
    private void attachSubProcessIoMapping(BpmnElementModel element, String rawBpmn, String containerId) {
        IoMappingModel io = readSubProcessIoMapping(rawBpmn, containerId);
        if (io == null) {
            return;
        }
        ExtensionElements ee = new ExtensionElements();
        ee.setIoMapping(io);
        attachIoMapping(element, ee);
    }

    /**
     * WO-DIFF-1: test-visible extraction step — raw XML of the container's own
     * direct {@code <bpmn:extensionElements>} block → {@code IoMappingModel}
     * (null when the container carries none). The mapping parse itself reuses
     * {@code attachIoMapping} (see {@link #attachSubProcessIoMapping}).
     */
    static IoMappingModel readSubProcessIoMapping(String rawBpmn, String containerId) {
        String block = subProcessExtensionBlock(rawBpmn, containerId);
        if (block == null) {
            return null;
        }
        try {
            // The block carries BOTH namespaces on its wrapper so the shared JAXB
            // types unmarshall it directly. Loud on parse failure (log + null would
            // silently drop the user's mappings — a quieter bug than the one fixed):
            // the caller treats null as "no mappings", so log BEFORE swallowing.
            BpmnSubProcessIoMappingWrapper wrapper =
                SecureXmlParser.unmarshal(block, BpmnSubProcessIoMappingWrapper.class);
            if (wrapper == null || wrapper.getIoMapping() == null) {
                log.warn("WO-DIFF-1: subProcess '{}' extension block parsed to no ioMapping — mappings ignored", containerId);
                return null;
            }
            return wrapper.getIoMapping();
        } catch (RuntimeException e) {
            log.warn("WO-DIFF-1: subProcess '{}' extension block failed to parse — mappings ignored: {}", containerId, e.toString());
            return null;
        }
    }

    /**
     * WO-DIFF-1: locates the DIRECT {@code <bpmn:extensionElements>} child block of
     * the {@code <bpmn:subProcess id="...">} (or {@code <bpmn:transaction>}) container
     * and returns it wrapped as a standalone {@code <extensionElements>} document
     * (BPMN-namespace wrapper so the shared JAXB type unmarshalls it). Returns null
     * when the container has no direct block. The scan stops the container's own
     * block at the first nested flow-node open tag — nested elements' own blocks
     * are never picked up.
     */
    static String subProcessExtensionBlock(String rawBpmn, String containerId) {
        if (rawBpmn == null || containerId == null || containerId.isBlank()) {
            return null;
        }
        int from = 0;
        while (true) {
            int sub = rawBpmn.indexOf("<bpmn:subProcess", from);
            int txn = rawBpmn.indexOf("<bpmn:transaction", from);
            int open;
            String tag;
            if (sub == -1 && txn == -1) {
                return null;
            } else if (sub != -1 && (txn == -1 || sub < txn)) {
                open = sub;
                tag = "subProcess";
            } else {
                open = txn;
                tag = "transaction";
            }
            // The open tag ends at the first '>' that is NOT inside a quoted
            // attribute value (FEEL sources contain '>' comparisons, e.g.
            // source="=x > 5" — a naive indexOf('>') would cut the head mid-tag).
            int headEnd = tagHeadEnd(rawBpmn, open);
            if (headEnd == -1) {
                return null;
            }
            String head = rawBpmn.substring(open, headEnd);
            boolean mine = head.contains("id=\"" + containerId + "\"")
                || head.contains("id='" + containerId + "'");
            // WO-DIFF-1 HOLD (nested subProcess-in-subProcess): the close must be the
            // one PAIRED with this open (depth-aware) — a plain indexOf finds the
            // INNERMOST nested same-tag container's close first and, combined with the
            // skip below, jumps clean over a nested container (its open tag never seen
            // again → its ioMapping silently null, no log, no exception).
            int close = matchingCloseTag(rawBpmn, headEnd, tag);
            if (close == -1) {
                return null;
            }
            if (!mine) {
                // Descend: the wanted container may be nested INSIDE this one — skipping
                // past its paired close would jump clean over it (exactly the HOLD bug:
                // outer is found first, mine=false, and the old code leapt over inner).
                from = headEnd + 1;
                continue;
            }
            String closeTag = "</bpmn:" + tag + ">";
            from = close + closeTag.length();
            String body = rawBpmn.substring(headEnd + 1, close);
            // The container's direct <bpmn:extensionElements> is the FIRST child element
            // of the container (BPMN XSD sequence: extensionElements precedes incoming/
            // outgoing/flow nodes). Skip only whitespace/comments — the first real child
            // tag decides: extensionElements → own block; anything else → the container
            // has none (a nested element's block can never be "the container's own").
            // NOTE: the tag may carry attributes or be self-closed across files, so the
            // match is prefix-based ("<bpmn:extensionElements" + '>' or whitespace),
            // never an exact "<bpmn:extensionElements>" literal.
            int cursor = 0;
            while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
                cursor++;
            }
            if (body.startsWith("<!--", cursor)) {
                int commentEnd = body.indexOf("-->", cursor + 4);
                if (commentEnd == -1) {
                    return null;
                }
                cursor = commentEnd + 3;
                while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
                    cursor++;
                }
            }
            String extPrefix = "<bpmn:extensionElements";
            if (!body.startsWith(extPrefix, cursor)) {
                return null;
            }
            int extHeadEnd = tagHeadEnd(body, cursor + extPrefix.length() - 1);
            if (extHeadEnd == -1) {
                return null;
            }
            // Self-closed "<bpmn:extensionElements .../>" → no inner block, no ioMapping.
            if (body.charAt(extHeadEnd - 1) == '/') {
                return null;
            }
            int innerFrom = extHeadEnd + 1;
            // The container's own block ends at ITS close tag. Inner content is
            // zeebe:* (ioMapping/input/output, different prefix, never confused)
            // plus whitespace/comments, so the close tag is searched directly.
            // Narrowness guard after: the block must not smuggle a nested flow
            // node. Any other '<bpmn:' open before the close (outside a comment)
            // means this extension block belongs to a nested element, not to the
            // container.
            int extClose = body.indexOf("</bpmn:extensionElements>", innerFrom);
            if (extClose == -1) {
                return null;
            }
            String between = body.substring(innerFrom, extClose);
            int probe = 0;
            while (true) {
                int nested = between.indexOf("<bpmn:", probe);
                if (nested == -1) {
                    break;
                }
                if (between.startsWith("<!--", nested)) {
                    int commentEnd = between.indexOf("-->", nested + 4);
                    if (commentEnd == -1) {
                        return null;
                    }
                    probe = commentEnd + 3;
                    continue;
                }
                return null;
            }
            return "<subProcessIoMapping xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\">"
                + between + "</subProcessIoMapping>";
        }
    }

    /**
     * WO-DIFF-1 HOLD (nested subProcess-in-subProcess): index of the CLOSE tag paired
     * with the container whose open tag ends at {@code headEnd}. Same-tag nested
     * containers are counted (depth), so the match is the TRUE paired close, not the
     * first textual occurrence (which belongs to the innermost nested container).
     * Returns -1 when unterminated. Self-closed same-tag elements and tag-like text
     * inside XML comments/CDATA never change the depth.
     */
     static int matchingCloseTag(String rawBpmn, int headEnd, String tag) {
         String openPrefix = "<bpmn:" + tag;
         String closeTag = "</bpmn:" + tag + ">";
         int depth = 1;
         int cursor = headEnd + 1;
         while (depth > 0) {
             int comment = rawBpmn.indexOf("<!--", cursor);
             int cdata = rawBpmn.indexOf("<![CDATA[", cursor);
             int nextOpen = rawBpmn.indexOf(openPrefix, cursor);
             int nextClose = rawBpmn.indexOf(closeTag, cursor);
             if (nextClose == -1) {
                 return -1;
             }
             // An XML comment or CDATA section opening first — skip it wholesale so
             // tag-like text inside documentation never perturbs the depth count.
             if (comment != -1 && comment < nextClose && (nextOpen == -1 || comment < nextOpen)
                 && (cdata == -1 || comment < cdata)) {
                 int commentEnd = rawBpmn.indexOf("-->", comment + 4);
                 if (commentEnd == -1) {
                     return -1;
                 }
                 cursor = commentEnd + 3;
                 continue;
             }
             if (cdata != -1 && cdata < nextClose && (nextOpen == -1 || cdata < nextOpen)) {
                 int cdataEnd = rawBpmn.indexOf("]]>", cdata + 9);
                 if (cdataEnd == -1) {
                     return -1;
                 }
                 cursor = cdataEnd + 3;
                 continue;
             }
             if (nextOpen != -1 && nextOpen < nextClose) {
                 // A longer tag name merely sharing the prefix (no such BPMN tag today)
                 // is not an open of this container — skip the occurrence.
                 int afterPrefix = nextOpen + openPrefix.length();
                 if (afterPrefix < rawBpmn.length()) {
                     char delim = rawBpmn.charAt(afterPrefix);
                     if (delim != '>' && delim != '/' && !Character.isWhitespace(delim)) {
                         cursor = afterPrefix;
                         continue;
                     }
                 }
                 int openEnd = tagHeadEnd(rawBpmn, nextOpen);
                 if (openEnd == -1) {
                     return -1;
                 }
                 // Self-closed "<bpmn:subProcess ... />" holds no children — depth unchanged
                 // (whitespace-tolerant: "<... / >" is legal XML too).
                 int back = openEnd - 1;
                 while (back > nextOpen && Character.isWhitespace(rawBpmn.charAt(back))) {
                     back--;
                 }
                 if (rawBpmn.charAt(back) == '/') {
                     cursor = openEnd + 1;
                     continue;
                 }
                 depth++;
                 cursor = openEnd + 1;
             } else {
                 depth--;
                 if (depth == 0) {
                     return nextClose;
                 }
                 cursor = nextClose + closeTag.length();
             }
         }
         return -1;
     }

    /**
     * WO-DIFF-1: end of an XML open tag starting at {@code open} (the index of
     * {@code '<'}): the first {@code '>'} outside single/double-quoted attribute
     * values, or -1 when unterminated. FEEL sources routinely contain bare
     * {@code >} comparisons ({@code source="=x > 5"}), so a naive
     * {@code indexOf('>')} would cut the tag mid-attribute.
     */
    static int tagHeadEnd(String xml, int open) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = open; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '>' && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    /**
     * WO-C8-32: maps {@code <bpmn:adHocSubProcess>} — same shape as
     * {@code toSubProcessElement} minus start/end (forbidden: loud
     * {@code BpmnParseException}, never silent) plus the ad-hoc metadata.
     * The extra {@code rawBpmn} parameter exists only so nested-sub recursion
     * shares one signature — ad-hoc ignores it (its extensions stay JAXB-bound).
     */
    private BpmnElementModel toAdHocSubProcessElement(BpmnAdHocSubProcessModel sub, String rawBpmnUnused, com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel pd, EventDefinitionRegistry registry, Map<String, String> messageNames, Map<String, String> messageKeys) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(sub.getId());
        element.setName(sub.getName());
        element.setType(BpmnElementType.AD_HOC_SUB_PROCESS);
        if (sub.getIncoming() != null) {
            element.getIncoming().addAll(sub.getIncoming());
        }
        if (sub.getOutgoing() != null) {
            element.getOutgoing().addAll(sub.getOutgoing());
        }
        if ((sub.getStartEvents() != null && !sub.getStartEvents().isEmpty())
            || (sub.getEndEvents() != null && !sub.getEndEvents().isEmpty())) {
            throw new BpmnParseException("Ad-hoc subprocess '" + sub.getId()
                + "' must not contain start or end events (Camunda docs constraint)");
        }
        com.zorrodev.bpm.engine.bpmn.model.AdHocSubProcessExtensionModel ext = new com.zorrodev.bpm.engine.bpmn.model.AdHocSubProcessExtensionModel();
        if (sub.getExtensionElements() != null && sub.getExtensionElements().getAdHoc() != null) {
            // WO-C8-32: FEEL attributes strip the leading '=' at parse, exactly like the
            // multi-instance inputCollection/outputElement (attachMultiInstance) — the
            // expression engine cannot parse it. outputCollection is a variable NAME, kept.
            ext.setActiveElementsCollection(stripLeadingEquals(sub.getExtensionElements().getAdHoc().getActiveElementsCollection()));
            ext.setOutputCollection(sub.getExtensionElements().getAdHoc().getOutputCollection());
            ext.setOutputElement(stripLeadingEquals(sub.getExtensionElements().getAdHoc().getOutputElement()));
        }
        if (sub.getCompletionCondition() != null) {
            ext.setCompletionCondition(stripLeadingEquals(sub.getCompletionCondition()));
        }
        ext.setCancelRemainingInstances(sub.getCancelRemainingInstances());
        collectAdHocInnerElements(sub, ext);

        flattenSubProcessChildren(sub, null, pd, registry, messageNames, messageKeys);

        element.setExtensions(new BpmnElementExtensionModel());
        element.getExtensions().setAdHocSubProcessExtension(ext);
        // WO-C8-33: a taskDefinition on the ad-hoc container switches it to job-worker
        // mode (schema-legal: ZeebeServiceTask extends bpmn:AdHocSubProcess — verified in
        // raw zeebe.json). Reuses the shared service-task job attach (job/retries/headers);
        // all its readers pull single fields, none dispatches on its presence (verified).
        attachServiceTaskJob(element, sub.getExtensionElements());
        return element;
    }

    /**
     * WO-C8-33: harvests directly nested executable elements of an ad-hoc container —
     * both the membership ids (C8-32 validation) and the per-element metadata for the
     * {@code adHocSubProcessElements} scope variable (job-worker mode). Mirrors the child
     * lists flattened by {@link #flattenSubProcessChildren} minus the non-executable ones
     * (boundary events attach to a host, flows/associations are not elements to execute,
     * start/end events are forbidden outright).
     */
    private void collectAdHocInnerElements(BpmnSubProcessModel sub, com.zorrodev.bpm.engine.bpmn.model.AdHocSubProcessExtensionModel ext) {
        addInnerElements(ext, sub.getServiceTasks(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, BpmnServiceTaskModel::getExtensionElements);
        addInnerElements(ext, sub.getScriptTasks(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, BpmnScriptTaskModel::getExtensionElements);
        addInnerElements(ext, sub.getUserTasks(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, BpmnUserTaskModel::getExtensionElements);
        addInnerElements(ext, sub.getManualTasks(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, null);
        // WO-DIFF-9: bare <bpmn:task> activates in ad-hoc like any other inner element.
        addInnerElements(ext, sub.getTasks(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, null);
        addInnerElements(ext, sub.getBusinessRuleTasks(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, BpmnBusinessRuleTaskModel::getExtensionElements);
        addInnerElements(ext, sub.getSendTasks(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, BpmnSendTaskModel::getExtensionElements);
        addInnerElements(ext, sub.getReceiveTasks(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, null);
        addInnerElements(ext, sub.getExclusiveGateways(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, BpmnExclusiveGatewayModel::getExtensionElements);
        addInnerElements(ext, sub.getParallelGateways(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, BpmnParallelGatewayModel::getExtensionElements);
        addInnerElements(ext, sub.getInclusiveGateways(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, BpmnInclusiveGatewayModel::getExtensionElements);
        addInnerElements(ext, sub.getEventBasedGateways(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, BpmnEventBasedGatewayModel::getExtensionElements);
        addInnerElements(ext, sub.getCallActivities(), BpmnBaseElementModel::getId, BpmnBaseElementModel::getName, BpmnBaseElementModel::getDocumentation, BpmnCallActivityModel::getExtensionElements);
        // Intermediate catch/throw events carry no documentation in our XML model.
        addInnerElements(ext, sub.getIntermediateCatchEvents(), BpmnIntermediateCatchEventModel::getId, BpmnIntermediateCatchEventModel::getName, null, BpmnIntermediateCatchEventModel::getExtensionElements);
        addInnerElements(ext, sub.getIntermediateThrowEvents(), BpmnIntermediateThrowEventModel::getId, BpmnIntermediateThrowEventModel::getName, null, BpmnIntermediateThrowEventModel::getExtensionElements);
        // Nested containers: id + name only (no documentation/properties in our XML model).
        addInnerElements(ext, sub.getSubProcesses(), BpmnSubProcessModel::getId, BpmnSubProcessModel::getName, null, null);
        addInnerElements(ext, sub.getTransactions(), BpmnSubProcessModel::getId, BpmnSubProcessModel::getName, null, null);
        addInnerElements(ext, sub.getAdHocSubProcesses(), BpmnSubProcessModel::getId, BpmnSubProcessModel::getName, null, BpmnAdHocSubProcessModel::getExtensionElements);
    }

    private <T> void addInnerElements(com.zorrodev.bpm.engine.bpmn.model.AdHocSubProcessExtensionModel ext, java.util.List<T> items,
            java.util.function.Function<T, String> idOf, java.util.function.Function<T, String> nameOf,
            java.util.function.Function<T, String> docOf, java.util.function.Function<T, ExtensionElements> eeOf) {
        if (items == null) {
            return;
        }
        for (T item : items) {
            if (item == null || idOf.apply(item) == null) {
                continue;
            }
            ext.getInnerElementIds().add(idOf.apply(item));
            com.zorrodev.bpm.engine.bpmn.model.AdHocElementMetadata meta = new com.zorrodev.bpm.engine.bpmn.model.AdHocElementMetadata();
            meta.setElementId(idOf.apply(item));
            meta.setElementName(nameOf == null ? null : nameOf.apply(item));
            meta.setDocumentation(docOf == null ? null : docOf.apply(item));
            meta.setProperties(elementProperties(eeOf == null ? null : eeOf.apply(item)));
            ext.getElementsMetadata().add(meta);
        }
    }

    private java.util.Map<String, String> elementProperties(ExtensionElements ee) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (ee == null || ee.getProperties() == null || ee.getProperties().getProperties() == null) {
            return out;
        }
        for (com.zorrodev.bpm.engine.bpmn.xml.PropertyModel pm : ee.getProperties().getProperties()) {
            if (pm != null && pm.getName() != null) {
                out.put(pm.getName(), pm.getValue());
            }
        }
        return out;
    }

    /**
     * WO-C8-32: flattens one container’s nested flow nodes/flows into the process
     * definition — extracted 1:1 from {@link #toSubProcessElement} (no logic changes),
     * shared by regular subprocesses, transactions and ad-hoc subprocesses so the
     * three cannot diverge. Start/end events are NOT handled here (regular containers
     * map them in their own prologue; ad-hoc forbids them outright).
     */
    private void flattenSubProcessChildren(BpmnSubProcessModel sub, String rawBpmn, com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel pd, EventDefinitionRegistry registry, Map<String, String> messageNames, Map<String, String> messageKeys) {
        if (sub.getServiceTasks() != null) {
            for (BpmnServiceTaskModel serviceTask : sub.getServiceTasks()) {
                BpmnElementModel child = toElementModel(serviceTask);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getScriptTasks() != null) {
            for (BpmnScriptTaskModel scriptTask : sub.getScriptTasks()) {
                BpmnElementModel child = toElementModel(scriptTask);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getUserTasks() != null) {
            for (BpmnUserTaskModel userTask : sub.getUserTasks()) {
                BpmnElementModel child = toElementModel(userTask);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getManualTasks() != null) {
            for (BpmnManualTaskModel manualTask : sub.getManualTasks()) {
                BpmnElementModel child = toElementModel(manualTask);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        // WO-DIFF-9: bare <bpmn:task> nested in an embedded subprocess.
        if (sub.getTasks() != null) {
            for (BpmnTaskModel task : sub.getTasks()) {
                BpmnElementModel child = toElementModel(task);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getExclusiveGateways() != null) {
            for (BpmnExclusiveGatewayModel gateway : sub.getExclusiveGateways()) {
                BpmnElementModel child = toElementModel(gateway);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getParallelGateways() != null) {
            for (BpmnParallelGatewayModel gateway : sub.getParallelGateways()) {
                BpmnElementModel child = toElementModel(gateway);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        // WO-C8-14 (A-2, срез 1): 7 плоских flow-нод разбираются ТЕМИ ЖЕ вызовами, что верхний
        // уровень (включая attachEventDefinition) — не упрощённой копией. callActivity,
        // вложенный subProcess, transaction, boundaryEvent, association — WO-C8-14b.
        if (sub.getIntermediateCatchEvents() != null) {
            for (BpmnIntermediateCatchEventModel catchEvent : sub.getIntermediateCatchEvents()) {
                BpmnElementModel child = toElementModel(catchEvent, messageNames, messageKeys);
                child.setProcessDefinition(pd);
                attachEventDefinition(child, null, catchEvent.getSignalEventDefinition(), null,
                    catchEvent.getConditionalEventDefinition(), catchEvent.getLinkEventDefinition(), null, registry);
                pd.addElement(child);
            }
        }
        if (sub.getIntermediateThrowEvents() != null) {
            for (BpmnIntermediateThrowEventModel throwEvent : sub.getIntermediateThrowEvents()) {
                BpmnElementModel child = toElementModel(throwEvent, messageNames);
                child.setProcessDefinition(pd);
                attachEventDefinition(child, null, throwEvent.getSignalEventDefinition(),
                    throwEvent.getEscalationEventDefinition(), null, throwEvent.getLinkEventDefinition(),
                    throwEvent.getCompensateEventDefinition(), registry);
                pd.addElement(child);
            }
        }
        if (sub.getBusinessRuleTasks() != null) {
            for (BpmnBusinessRuleTaskModel businessRuleTask : sub.getBusinessRuleTasks()) {
                BpmnElementModel child = toElementModel(businessRuleTask);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getSendTasks() != null) {
            for (BpmnSendTaskModel sendTask : sub.getSendTasks()) {
                BpmnElementModel child = toElementModel(sendTask, messageNames);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getReceiveTasks() != null) {
            for (BpmnReceiveTaskModel receiveTask : sub.getReceiveTasks()) {
                BpmnElementModel child = toElementModel(receiveTask, messageNames, messageKeys);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getInclusiveGateways() != null) {
            for (BpmnInclusiveGatewayModel inclusiveGateway : sub.getInclusiveGateways()) {
                BpmnElementModel child = new BpmnElementModel();
                child.setId(inclusiveGateway.getId());
                child.setName(inclusiveGateway.getName());
                child.setType(BpmnElementType.INCLUSIVE_GATEWAY);
                child.setIncoming(inclusiveGateway.getIncoming());
                child.setOutgoing(inclusiveGateway.getOutgoing());
                if (inclusiveGateway.getDefaultFlow() != null) {
                    child.setExtensions(new BpmnElementExtensionModel());
                    child.getExtensions().setExclusiveGatewayExtension(new ExclusiveGatewayExtensionModel());
                    child.getExtensions().getExclusiveGatewayExtension().setDefaultFlowId(inclusiveGateway.getDefaultFlow());
                }
                // WO-C8-25: вложенные шлюзы — тем же хуком, что верхний уровень.
                attachElementStartListeners(child, inclusiveGateway.getExtensionElements());
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getEventBasedGateways() != null) {
            for (BpmnEventBasedGatewayModel eventGateway : sub.getEventBasedGateways()) {
                BpmnElementModel child = new BpmnElementModel();
                child.setId(eventGateway.getId());
                child.setName(eventGateway.getName());
                child.setType(BpmnElementType.EVENT_BASED_GATEWAY);
                child.setIncoming(eventGateway.getIncoming());
                child.setOutgoing(eventGateway.getOutgoing());
                // WO-C8-25: вложенные шлюзы — тем же хуком, что верхний уровень.
                attachElementStartListeners(child, eventGateway.getExtensionElements());
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        // WO-C8-14b (A-2, срез 2): вложенные контейнеры и boundary-события — ТЕМИ ЖЕ вызовами,
        // что верхний уровень. WO-C8-32: плюс вложенные adHocSubProcess (та же рекурсия).
        if (sub.getCallActivities() != null) {
            for (BpmnCallActivityModel callActivity : sub.getCallActivities()) {
                BpmnElementModel child = toElementModel(callActivity);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getSubProcesses() != null) {
            for (BpmnSubProcessModel nested : sub.getSubProcesses()) {
                BpmnElementModel child = toSubProcessElement(nested, rawBpmn, pd, registry, messageNames, messageKeys);
                child.setProcessDefinition(pd);
                attachSubProcessIoMapping(child, rawBpmn, nested.getId());
                pd.addElement(child);
            }
        }
        if (sub.getTransactions() != null) {
            for (BpmnSubProcessModel transaction : sub.getTransactions()) {
                BpmnElementModel child = toSubProcessElement(transaction, rawBpmn, pd, registry, messageNames, messageKeys);
                child.setProcessDefinition(pd);
                attachSubProcessIoMapping(child, rawBpmn, transaction.getId());
                pd.addElement(child);
            }
        }
        if (sub.getAdHocSubProcesses() != null) {
            for (BpmnAdHocSubProcessModel nested : sub.getAdHocSubProcesses()) {
                BpmnElementModel child = toAdHocSubProcessElement(nested, rawBpmn, pd, registry, messageNames, messageKeys);
                child.setProcessDefinition(pd);
                pd.addElement(child);
            }
        }
        if (sub.getBoundaryEvents() != null) {
            for (BpmnBoundaryEventModel boundaryEvent : sub.getBoundaryEvents()) {
                boolean timer = boundaryEvent.getTimerEventDefinition() != null;
                boolean error = boundaryEvent.getErrorEventDefinition() != null;
                boolean message = boundaryEvent.getMessageEventDefinition() != null;
                boolean signal = boundaryEvent.getSignalEventDefinition() != null;
                boolean escalation = boundaryEvent.getEscalationEventDefinition() != null;
                boolean conditional = boundaryEvent.getConditionalEventDefinition() != null;
                boolean compensation = boundaryEvent.getCompensateEventDefinition() != null;
                boolean cancel = boundaryEvent.getCancelEventDefinition() != null;
                if (!timer && !error && !message && !signal && !escalation && !conditional && !compensation && !cancel) {
                    continue; // only timer, error, message, signal, escalation, conditional, compensation and cancel boundaries are executable today
                }
                BpmnElementModel child = toBoundaryElement(boundaryEvent);
                child.setProcessDefinition(pd);
                attachEventDefinition(child, boundaryEvent.getErrorEventDefinition(), boundaryEvent.getSignalEventDefinition(), boundaryEvent.getEscalationEventDefinition(), boundaryEvent.getConditionalEventDefinition(), null, null, registry);
                if (message) {
                    MessageEventExtensionModel msg = new MessageEventExtensionModel();
                    String ref = boundaryEvent.getMessageEventDefinition().getMessageRef();
                    msg.setMessageName(messageNames.getOrDefault(ref, ref));
                    msg.setCorrelationKeyExpression(messageKeys.get(ref));
                    child.getExtensions().setMessageEventExtension(msg);
                }
                pd.addElement(child);
            }
        }
        // Compensation associations declared inside the subprocess: same resolution as the
        // top-level pass, scoped to this container's association list (boundary ids are unique,
        // so no cross-container contamination — resolveCompensationHandler matches by id).
        if (sub.getAssociations() != null) {
            for (BpmnElementModel child : pd.getElements()) {
                if (child.getType() != BpmnElementType.COMPENSATION_BOUNDARY_EVENT) {
                    continue;
                }
                String handlerId = resolveCompensationHandler(child.getId(), sub.getAssociations());
                if (handlerId != null) {
                    child.getExtensions().getBoundaryEventExtension().setCompensationHandlerId(handlerId);
                }
            }
        }
        if (sub.getFlows() != null) {
            for (BpmnSequenceFlowModel flow : sub.getFlows()) {
                pd.addFlow(toFlowModel(flow));
            }
        }
    }

    private BpmnFlowModel toFlowModel(BpmnSequenceFlowModel flow) {
        BpmnFlowModel element = new BpmnFlowModel();
        element.setFlowId(flow.getId());
        element.setSourceRef(flow.getSourceRef());
        element.setTargetRef(flow.getTargetRef());
        if (flow.getConditionExpression() != null) {
            BpmnConditionExpressionModel expression = new BpmnConditionExpressionModel();
            expression.setType(flow.getConditionExpression().getType());
            expression.setExpression(flow.getConditionExpression().getExpression());
            element.setConditionExpression(expression);
        }
        return element;
    }

    private void checkBpmn(BpmnProcessDefinitionModel process) {
        if (Optional.ofNullable(process.getStartEvents()).isEmpty()) {
            throw new BpmnParseException("No start events in the process definition xml");
        }
        long vanillaStartEventCount = process.getStartEvents().stream()
            .filter(e -> e.getMessageEventDefinition()==null)
            .filter(e -> e.getTimerEventDefinition()==null)
            .filter(e -> e.getSignalEventDefinition()==null)
            .count();
        // at most one plain (none) start is allowed; a process may instead start via message/timer/
        // signal start events, so zero plain starts is valid as long as some start event exists
        if (vanillaStartEventCount > 1) {
            throw new BpmnParseException("At most one plain start event is allowed in the process definition xml");
        }
        if (Optional.ofNullable(process.getEndEvents()).isEmpty()) {
            throw new BpmnParseException("No end events in the process definition xml");
        }
    }

    private BpmnElementModel toElementModel(BpmnServiceTaskModel serviceTask) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(serviceTask.getId());
        element.setName(serviceTask.getName());
        element.setType(BpmnElementType.SERVICE_TASK);
        element.setIncoming(serviceTask.getIncoming());
        element.setOutgoing(serviceTask.getOutgoing());
        attachServiceTaskJob(element, serviceTask.getExtensionElements());
            // WO-C8-11: start execution listeners block the real job until each completes.
            // WO-C8-11b: end listeners block the token until each completes (after the real job).
            // Service-task-only: end/throw events never read startListeners.
            if (serviceTask.getExtensionElements() != null && serviceTask.getExtensionElements().getTaskDefinition() != null
                && serviceTask.getExtensionElements().getExecutionListeners() != null
                && serviceTask.getExtensionElements().getExecutionListeners().getListeners() != null) {
                List<ListenerModel> starts = new ArrayList<>();
                List<ListenerModel> ends = new ArrayList<>();
                for (ExecutionListenerModel l : serviceTask.getExtensionElements().getExecutionListeners().getListeners()) {
                    if (l.getType() == null) {
                        continue;
                    }
                    if ("start".equals(l.getEventType())) {
                        starts.add(new ListenerModel(l.getType(), l.getRetries(), listenerHeaders(l)));
                    } else if ("end".equals(l.getEventType())) {
                        // WO-C8-11b: end listeners block the token until each completes (after the real job).
                        ends.add(new ListenerModel(l.getType(), l.getRetries(), listenerHeaders(l)));
                    }
                }
                if (!starts.isEmpty()) {
                    element.getExtensions().getServiceTaskExtension().setStartListeners(starts);
                }
                if (!ends.isEmpty()) {
                    element.getExtensions().getServiceTaskExtension().setEndListeners(ends);
                }
            }
        attachIoMapping(element, serviceTask.getExtensionElements());
        attachMultiInstance(element, serviceTask.getMultiInstanceLoopCharacteristics());
        return element;
    }

    /**
     * WO-C8-7r2: nested {@code zeebe:taskHeaders} of one execution listener as a map
     * (null when absent) — delivered with the listener's job merged over the element
     * headers, listener wins per the docs.
     */
    private Map<String, String> listenerHeaders(ExecutionListenerModel l) {
        TaskHeadersModel headers = l == null ? null : l.getTaskHeaders();
        if (headers == null || headers.getHeaders() == null) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (HeaderModel h : headers.getHeaders()) {
            if (h.getKey() != null) map.put(h.getKey(), h.getValue());
        }
        return map;
    }

    /**
     * WO-C8-16: attaches job identity ({@code zeebe:taskDefinition} type/retries,
     * {@code taskHeaders}, {@code jobPriorityDefinition}) to any element carrying a
     * taskDefinition — service tasks (extracted 1:1, behavior-identical) and now end/throw
     * events. One body shared by all callers, not a simplified copy (criterion 5).
     * Reuses a pre-existing extensions object (message throws already set one) instead of
     * overwriting it. Start listeners stay service-task-only (parsed after this call).
     */
    private void attachServiceTaskJob(BpmnElementModel element, ExtensionElements ee) {
        if (ee == null || ee.getTaskDefinition() == null) {
            return;
        }
        if (element.getExtensions() == null) {
            element.setExtensions(new BpmnElementExtensionModel());
        }
        if (element.getExtensions().getServiceTaskExtension() == null) {
            element.getExtensions().setServiceTaskExtension(new ServiceTaskExtensionModel());
        }
        element.getExtensions().getServiceTaskExtension().setJob(ee.getTaskDefinition().getType());
        element.getExtensions().getServiceTaskExtension().setRetries(ee.getTaskDefinition().getRetries());
        attachTaskHeaders(element, ee);
        // WO-C8-9: raw priority rides along for FEEL→Integer resolution at enqueue time (null when absent).
        // WO-C8-13 (A-1): the element is zeebe:jobPriorityDefinition — priorityDefinition is
        // only allowed on user tasks and is never read here.
        JobPriorityDefinitionModel jobPriorityDefinition = ee.getJobPriorityDefinition();
        if (jobPriorityDefinition != null) {
            element.getExtensions().getServiceTaskExtension().setPriority(jobPriorityDefinition.getPriority());
        }
    }

    /**
     * WO-C8-25 (part B of finding A-5): start execution listeners for gateway/event
     * elements — parsed into the DEDICATED {@code elementStartListeners} field (NOT into
     * {@code ServiceTaskExtensionModel.startListeners}: the C8-11 service-task machinery
     * assumes a service_tasks row, which these elements never have). Service/user tasks
     * never pass through here (they keep their own C8-11/C8-21 paths); boundary elements
     * have a separate mapping ({@code toBoundaryElement}) that deliberately does NOT call
     * this.
     */
    private void attachElementStartListeners(BpmnElementModel element, ExtensionElements ee) {
        if (ee == null || ee.getExecutionListeners() == null
            || ee.getExecutionListeners().getListeners() == null) {
            return;
        }
        List<ListenerModel> starts = new ArrayList<>();
        for (ExecutionListenerModel l : ee.getExecutionListeners().getListeners()) {
            if (l.getType() == null) {
                continue;
            }
            if ("start".equals(l.getEventType())) {
                starts.add(new ListenerModel(l.getType(), l.getRetries(), listenerHeaders(l)));
            }
        }
        if (starts.isEmpty()) {
            return;
        }
        if (element.getExtensions() == null) {
            element.setExtensions(new BpmnElementExtensionModel());
        }
        element.getExtensions().setElementStartListeners(starts);
    }

    /**
     * WO-C8-7r2: custom headers ride along to the worker via JobDetailModel (null when
     * absent). Headers-only slice of {@link #attachServiceTaskJob} for element kinds that
     * build their own {@code ServiceTaskExtension} (script/send job-worker branches) —
     * one body, so job/priority semantics of those branches stay exactly as they were.
     */
    private void attachTaskHeaders(BpmnElementModel element, ExtensionElements ee) {
        if (ee == null || ee.getTaskHeaders() == null) {
            return;
        }
        if (element.getExtensions() == null) {
            element.setExtensions(new BpmnElementExtensionModel());
        }
        if (element.getExtensions().getServiceTaskExtension() == null) {
            element.getExtensions().setServiceTaskExtension(new ServiceTaskExtensionModel());
        }
        // WO-C8-7: custom headers ride along to the worker via JobDetailModel (null when absent).
        TaskHeadersModel headers = ee.getTaskHeaders();
        if (headers != null && headers.getHeaders() != null) {
            Map<String, String> map = new LinkedHashMap<>();
            for (HeaderModel h : headers.getHeaders()) {
                if (h.getKey() != null) map.put(h.getKey(), h.getValue());
            }
            element.getExtensions().getServiceTaskExtension().setTaskHeaders(map);
        }
    }

    /** Reads a {@code zeebe:ioMapping} into the element's extensions (input/output FEEL transformations). */
    private void attachIoMapping(BpmnElementModel element, ExtensionElements ee) {
        IoMappingModel io = ee == null ? null : ee.getIoMapping();
        if (io == null) {
            return;
        }
        boolean hasInputs = io.getInputs() != null && !io.getInputs().isEmpty();
        boolean hasOutputs = io.getOutputs() != null && !io.getOutputs().isEmpty();
        if (!hasInputs && !hasOutputs) {
            return;
        }
        IoMappingExtensionModel ext = new IoMappingExtensionModel();
        ext.setInputs(toMappings(io.getInputs()));
        ext.setOutputs(toMappings(io.getOutputs()));
        if (element.getExtensions() == null) {
            element.setExtensions(new BpmnElementExtensionModel());
        }
        element.getExtensions().setIoMappingExtension(ext);
    }

    private List<IoMappingExtensionModel.Mapping> toMappings(List<MappingModel> src) {
        if (src == null) {
            return null;
        }
        return src.stream().map(m -> {
            IoMappingExtensionModel.Mapping mapping = new IoMappingExtensionModel.Mapping();
            mapping.setSource(m.getSource());
            mapping.setTarget(m.getTarget());
            return mapping;
        }).toList();
    }

    private BpmnElementModel toElementModel(BpmnScriptTaskModel scriptTask) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(scriptTask.getId());
        element.setName(scriptTask.getName());
        element.setType(BpmnElementType.SCRIPT_TASK);
        element.setIncoming(scriptTask.getIncoming());
        element.setOutgoing(scriptTask.getOutgoing());
        element.setExtensions(new BpmnElementExtensionModel());

        // Camunda 8: a zeebe:taskDefinition makes the script task a job worker (executed like a service
        // task) instead of an inline FEEL script.
        if (scriptTask.getExtensionElements() != null && scriptTask.getExtensionElements().getTaskDefinition() != null) {
            ServiceTaskExtensionModel job = new ServiceTaskExtensionModel();
            job.setJob(scriptTask.getExtensionElements().getTaskDefinition().getType());
            job.setRetries(scriptTask.getExtensionElements().getTaskDefinition().getRetries());
            element.getExtensions().setServiceTaskExtension(job);
            // WO-C8-7r2: job-worker script tasks deliver taskHeaders like service tasks.
            attachTaskHeaders(element, scriptTask.getExtensionElements());
            return element;
        }

        ScriptTaskExtensionModel script = new ScriptTaskExtensionModel();
        ZeebeScriptModel zeebeScript = scriptTask.getExtensionElements() == null ? null
            : scriptTask.getExtensionElements().getScript();
        if (zeebeScript != null && zeebeScript.getExpression() != null) {
            // Camunda 8 style: <zeebe:script expression="=…" resultVariable="…">; strip the leading '='
            script.setScript(stripLeadingEquals(zeebeScript.getExpression()));
            script.setResultVariable(zeebeScript.getResultVariable());
            script.setScriptFormat("feel");
        } else {
            // BPMN-standard inline <script> child
            script.setScriptFormat(scriptTask.getScriptFormat());
            script.setScript(scriptTask.getScript() != null ? scriptTask.getScript().strip() : null);
            script.setResultVariable(scriptTask.getResultVariable());
        }
        element.getExtensions().setScriptTaskExtension(script);
        return element;
    }

    /** Strips a leading {@code =} (Zeebe FEEL expressions are written {@code "=expr"}). */
    private String stripLeadingEquals(String expression) {
        if (expression == null) {
            return null;
        }
        String stripped = expression.strip();
        return stripped.startsWith("=") ? stripped.substring(1).strip() : stripped;
    }

    private BpmnElementModel toElementModel(BpmnBusinessRuleTaskModel businessRuleTask) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(businessRuleTask.getId());
        element.setName(businessRuleTask.getName());
        element.setType(BpmnElementType.BUSINESS_RULE_TASK);
        element.setIncoming(businessRuleTask.getIncoming());
        element.setOutgoing(businessRuleTask.getOutgoing());
        ExtensionElements ee = businessRuleTask.getExtensionElements();
        BusinessRuleExtensionModel ext = new BusinessRuleExtensionModel();
        if (ee != null && ee.getCalledDecision() != null) {
            ext.setDecisionId(ee.getCalledDecision().getDecisionId());
            ext.setResultVariable(ee.getCalledDecision().getResultVariable());
            ext.setBindingType(ee.getCalledDecision().getBindingType());
            ext.setVersionTag(ee.getCalledDecision().getVersionTag());
        } else if (ee != null && ee.getScript() != null) {
            ext.setExpression(ee.getScript().getExpression() != null ? ee.getScript().getExpression().strip() : null);
            ext.setResultVariable(ee.getScript().getResultVariable());
        }
        element.setExtensions(new BpmnElementExtensionModel());
        element.getExtensions().setBusinessRuleExtension(ext);
        return element;
    }

    private BpmnElementModel toElementModel(BpmnSendTaskModel sendTask, Map<String, String> messageNames) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(sendTask.getId());
        element.setName(sendTask.getName());
        element.setType(BpmnElementType.SEND_TASK);
        element.setIncoming(sendTask.getIncoming());
        element.setOutgoing(sendTask.getOutgoing());
        element.setExtensions(new BpmnElementExtensionModel());
        // Camunda 8 form: a zeebe:taskDefinition makes the send task a job worker (like a service task)
        if (sendTask.getExtensionElements() != null && sendTask.getExtensionElements().getTaskDefinition() != null) {
            ServiceTaskExtensionModel job = new ServiceTaskExtensionModel();
            job.setJob(sendTask.getExtensionElements().getTaskDefinition().getType());
            job.setRetries(sendTask.getExtensionElements().getTaskDefinition().getRetries());
            element.getExtensions().setServiceTaskExtension(job);
            // WO-C8-7r2: job-worker send tasks deliver taskHeaders like service tasks.
            attachTaskHeaders(element, sendTask.getExtensionElements());
        } else if (sendTask.getMessageRef() != null) {
            MessageEventExtensionModel message = new MessageEventExtensionModel();
            message.setMessageName(messageNames.getOrDefault(sendTask.getMessageRef(), sendTask.getMessageRef()));
            element.getExtensions().setMessageEventExtension(message);
        }
        return element;
    }

    private BpmnElementModel toElementModel(BpmnReceiveTaskModel receiveTask, Map<String, String> messageNames, Map<String, String> messageKeys) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(receiveTask.getId());
        element.setName(receiveTask.getName());
        element.setType(BpmnElementType.RECEIVE_TASK);
        element.setIncoming(receiveTask.getIncoming());
        element.setOutgoing(receiveTask.getOutgoing());
        if (receiveTask.getMessageRef() != null) {
            MessageEventExtensionModel message = new MessageEventExtensionModel();
            message.setMessageName(messageNames.getOrDefault(receiveTask.getMessageRef(), receiveTask.getMessageRef()));
            message.setCorrelationKeyExpression(messageKeys.get(receiveTask.getMessageRef()));
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setMessageEventExtension(message);
        }
        return element;
    }

    private BpmnElementModel toElementModel(BpmnManualTaskModel manualTask) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(manualTask.getId());
        element.setName(manualTask.getName());
        element.setType(BpmnElementType.MANUAL_TASK);
        element.setIncoming(manualTask.getIncoming());
        element.setOutgoing(manualTask.getOutgoing());
        return element;
    }

    // WO-DIFF-9: bare <bpmn:task> maps to its own type (not MANUAL_TASK) so
    // diagnostics distinguish "forgotten type" from "real manual task"; the
    // runtime behavior is identical (pass-through, see SyncTaskHandler.UndefinedTask).
    private BpmnElementModel toElementModel(BpmnTaskModel task) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(task.getId());
        element.setName(task.getName());
        element.setType(BpmnElementType.UNDEFINED_TASK);
        element.setIncoming(task.getIncoming());
        element.setOutgoing(task.getOutgoing());
        return element;
    }

    private BpmnElementModel toElementModel(BpmnUserTaskModel userTask) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(userTask.getId());
        element.setName(userTask.getName());
        element.setType(BpmnElementType.USER_TASK);
        element.setIncoming(userTask.getIncoming());
        element.setOutgoing(userTask.getOutgoing());
        if (userTask.getExtensionElements() != null) {
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setUserTaskExtension(new UserTaskExtensionModel());
            if (userTask.getExtensionElements().getAssignmentDefinition() != null) {
                element.getExtensions().getUserTaskExtension().setAssignee(userTask.getExtensionElements().getAssignmentDefinition().getAssignee());
                element.getExtensions().getUserTaskExtension().setCandidateUsers(userTask.getExtensionElements().getAssignmentDefinition().getCandidateUsers());
                element.getExtensions().getUserTaskExtension().setCandidateGroups(userTask.getExtensionElements().getAssignmentDefinition().getCandidateGroups());
            }
            if (userTask.getExtensionElements().getTaskSchedule() != null) {
                element.getExtensions().getUserTaskExtension().setDueDate(userTask.getExtensionElements().getTaskSchedule().getDueDate());
                element.getExtensions().getUserTaskExtension().setFollowUpDate(userTask.getExtensionElements().getTaskSchedule().getFollowUpDate());
            }
            // WO-C8-30: priorityDefinition rides its own field (never the service-task
            // jobPriorityDefinition — different type, C8-13, untouched). Raw string,
            // resolved at activation (static integer or FEEL).
            if (userTask.getExtensionElements().getPriorityDefinition() != null
                && userTask.getExtensionElements().getPriorityDefinition().getPriority() != null) {
                element.getExtensions().getUserTaskExtension().setPriority(
                    userTask.getExtensionElements().getPriorityDefinition().getPriority());
            }

            if (userTask.getExtensionElements().getFormDefinition() != null) {
                if (userTask.getExtensionElements().getFormDefinition().getFormKey() != null) {
                    element.getExtensions().getUserTaskExtension().setFormKey(userTask.getExtensionElements().getFormDefinition().getFormKey());
                } else if (userTask.getExtensionElements().getFormDefinition().getExternalReference() != null) {
                    element.getExtensions().getUserTaskExtension().setFormKey(userTask.getExtensionElements().getFormDefinition().getExternalReference());
                    element.getExtensions().getUserTaskExtension().setExternalReference(userTask.getExtensionElements().getFormDefinition().getExternalReference());
                }
                // WO-C8-22: formId rides a separate field — never merged into formKey.
                // Docs list the three reference kinds as mutually exclusive, so co-presence
                // is invalid input; resolve prefers the specific linked id (see FormResolver).
                if (userTask.getExtensionElements().getFormDefinition().getFormId() != null) {
                    element.getExtensions().getUserTaskExtension().setFormId(userTask.getExtensionElements().getFormDefinition().getFormId());
                }
                // WO-C8-23: bindingType (+versionTag — parse-only, ⛔ граница) rides alongside.
                // latest/absent keeps the old resolve path untouched (see TaskFormOperationsImpl).
                if (userTask.getExtensionElements().getFormDefinition().getBindingType() != null) {
                    element.getExtensions().getUserTaskExtension().setBindingType(userTask.getExtensionElements().getFormDefinition().getBindingType());
                }
                if (userTask.getExtensionElements().getFormDefinition().getVersionTag() != null) {
                    element.getExtensions().getUserTaskExtension().setVersionTag(userTask.getExtensionElements().getFormDefinition().getVersionTag());
                }
            }
            // WO-C8-21: creating task listeners block task creation until each completes.
            // WO-C8-24: completing task listeners block task completion until each completes.
            // WO-C8-28: assigning/updating/canceling task listeners block their own
            // lifecycle transitions (assignment / variable update / cancellation).
            // Only user tasks may carry taskListeners (schema allowedIn); unknown
            // eventTypes stay unparsed (fail-closed at parse time).
            if (userTask.getExtensionElements().getTaskListeners() != null
                && userTask.getExtensionElements().getTaskListeners().getListeners() != null) {
                List<ListenerModel> creating = new ArrayList<>();
                List<ListenerModel> completing = new ArrayList<>();
                List<ListenerModel> assigning = new ArrayList<>();
                List<ListenerModel> updating = new ArrayList<>();
                List<ListenerModel> canceling = new ArrayList<>();
                for (TaskListenerModel l : userTask.getExtensionElements().getTaskListeners().getListeners()) {
                    if (l.getType() == null) {
                        continue;
                    }
                    if ("creating".equals(l.getEventType())) {
                        // No nested headers here: the schema gives TaskListener no headers
                        // property (unlike ExecutionListener) — always null, by schema.
                        creating.add(new ListenerModel(l.getType(), l.getRetries(), null));
                    } else if ("completing".equals(l.getEventType())) {
                        // WO-C8-24: same — completing listeners block completion.
                        completing.add(new ListenerModel(l.getType(), l.getRetries(), null));
                    } else if ("assigning".equals(l.getEventType())) {
                        // WO-C8-28: same — assigning listeners block assignment.
                        assigning.add(new ListenerModel(l.getType(), l.getRetries(), null));
                    } else if ("updating".equals(l.getEventType())) {
                        // WO-C8-28: same — updating listeners block the variable update.
                        updating.add(new ListenerModel(l.getType(), l.getRetries(), null));
                    } else if ("canceling".equals(l.getEventType())) {
                        // WO-C8-28: same — canceling listeners observe cancellation.
                        canceling.add(new ListenerModel(l.getType(), l.getRetries(), null));
                    }
                }
                if (!creating.isEmpty()) {
                    element.getExtensions().getUserTaskExtension().setCreatingListeners(creating);
                }
                if (!completing.isEmpty()) {
                    element.getExtensions().getUserTaskExtension().setCompletingListeners(completing);
                }
                if (!assigning.isEmpty()) {
                    element.getExtensions().getUserTaskExtension().setAssigningListeners(assigning);
                }
                if (!updating.isEmpty()) {
                    element.getExtensions().getUserTaskExtension().setUpdatingListeners(updating);
                }
                if (!canceling.isEmpty()) {
                    element.getExtensions().getUserTaskExtension().setCancelingListeners(canceling);
                }
            }
        }
        attachIoMapping(element, userTask.getExtensionElements());
        attachMultiInstance(element, userTask.getMultiInstanceLoopCharacteristics());
        return element;
    }

    /** Parses a {@code multiInstanceLoopCharacteristics} (sequential/cardinality/completionCondition and the
     *  Camunda 8 {@code zeebe:loopCharacteristics} collection/element attributes) onto the element. No-op if
     *  the element is not multi-instance. Shared by user and service tasks. */
    private void attachMultiInstance(BpmnElementModel element, BpmnMultiInstanceModel mi) {
        if (mi == null) {
            return;
        }
        MultiInstanceExtensionModel ext = new MultiInstanceExtensionModel();
        ext.setSequential(Boolean.TRUE.equals(mi.getIsSequential()));
        ext.setCardinality(mi.getLoopCardinality() != null ? mi.getLoopCardinality().strip() : null);
        ext.setCompletionCondition(mi.getCompletionCondition() != null ? mi.getCompletionCondition().strip() : null);
        ZeebeLoopCharacteristicsModel loop = mi.getExtensionElements() == null ? null
            : mi.getExtensionElements().getLoopCharacteristics();
        if (loop != null) {
            ext.setInputCollection(stripLeadingEquals(loop.getInputCollection()));
            ext.setInputElement(loop.getInputElement());
            ext.setOutputCollection(loop.getOutputCollection());
            ext.setOutputElement(stripLeadingEquals(loop.getOutputElement()));
        }
        if (element.getExtensions() == null) {
            element.setExtensions(new BpmnElementExtensionModel());
        }
        element.getExtensions().setMultiInstanceExtension(ext);
    }

    private BpmnElementModel toElementModel(BpmnEndEventModel endEvent) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(endEvent.getId());
        element.setName(endEvent.getName());
        if (endEvent.getTerminateEventDefinition() != null) {
            element.setType(BpmnElementType.TERMINATE_END_EVENT);
        } else if (endEvent.getErrorEventDefinition() != null) {
            element.setType(BpmnElementType.ERROR_END_EVENT);
        } else if (endEvent.getEscalationEventDefinition() != null) {
            element.setType(BpmnElementType.ESCALATION_END_EVENT);
        } else if (endEvent.getCancelEventDefinition() != null) {
            element.setType(BpmnElementType.CANCEL_END_EVENT);
        } else {
            element.setType(BpmnElementType.END_EVENT);
        }
        element.setIncoming(endEvent.getIncoming());
        // WO-C8-16: job-based end events (zeebe:taskDefinition) park as jobs.
        attachServiceTaskJob(element, endEvent.getExtensionElements());
        // WO-C8-25: start listeners park the event before its handler runs.
        attachElementStartListeners(element, endEvent.getExtensionElements());
        return element;
    }

    private BpmnElementModel toElementModel(BpmnStartEventModel startEvent) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(startEvent.getId());
        element.setName(startEvent.getName());

        if (startEvent.getIncoming() != null) {
            element.getIncoming().addAll(startEvent.getIncoming());
        }

        if (startEvent.getOutgoing() != null) {
            element.getOutgoing().addAll(startEvent.getOutgoing());
        }

        if (startEvent.getMessageEventDefinition() != null) {
            element.setType(BpmnElementType.MESSAGE_START_EVENT);
        } else if (startEvent.getTimerEventDefinition() != null) {
            element.setType(BpmnElementType.TIMER_START_EVENT);
        } else if (startEvent.getSignalEventDefinition() != null) {
            element.setType(BpmnElementType.SIGNAL_START_EVENT);
        } else {
            element.setType(BpmnElementType.START_EVENT);
        }
        // WO-C8-25: start listeners park the event before its handler runs.
        attachElementStartListeners(element, startEvent.getExtensionElements());

        return element;
    }


    private BpmnElementModel toElementModel(BpmnExclusiveGatewayModel exclusiveGateway) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(exclusiveGateway.getId());
        element.setName(exclusiveGateway.getName());
        element.setType(BpmnElementType.EXCLUSIVE_GATEWAY);
        element.setOutgoing(exclusiveGateway.getOutgoing());
        element.setIncoming(exclusiveGateway.getIncoming());
        if (exclusiveGateway.getDefaultFlow() != null) {
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setExclusiveGatewayExtension(new ExclusiveGatewayExtensionModel());
            element.getExtensions().getExclusiveGatewayExtension().setDefaultFlowId(exclusiveGateway.getDefaultFlow());
        }
        // WO-C8-25: start listeners park the gateway before its handler runs.
        attachElementStartListeners(element, exclusiveGateway.getExtensionElements());
        return element;
    }

    private BpmnElementModel toElementModel(BpmnParallelGatewayModel parallelGateway) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(parallelGateway.getId());
        element.setName(parallelGateway.getName());
        element.setType(BpmnElementType.PARALLEL_GATEWAY);
        element.setOutgoing(parallelGateway.getOutgoing());
        element.setIncoming(parallelGateway.getIncoming());
        // WO-C8-25: start listeners park the gateway before its handler runs.
        attachElementStartListeners(element, parallelGateway.getExtensionElements());
        return element;
    }

    private BpmnElementModel toElementModel(BpmnIntermediateCatchEventModel catchEvent, Map<String, String> messageNames, Map<String, String> messageKeys) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(catchEvent.getId());
        element.setName(catchEvent.getName());
        element.setIncoming(catchEvent.getIncoming());
        element.setOutgoing(catchEvent.getOutgoing());

        if (catchEvent.getMessageEventDefinition() != null) {
            element.setType(BpmnElementType.MESSAGE_CATCH_EVENT);
        } else if (catchEvent.getTimerEventDefinition() != null) {
            element.setType(BpmnElementType.TIMER_CATCH_EVENT);
        } else if (catchEvent.getSignalEventDefinition() != null) {
            element.setType(BpmnElementType.SIGNAL_CATCH_EVENT);
        } else if (catchEvent.getLinkEventDefinition() != null) {
            element.setType(BpmnElementType.LINK_CATCH_EVENT);
        } else if (catchEvent.getConditionalEventDefinition() != null) {
            element.setType(BpmnElementType.CONDITIONAL_CATCH_EVENT);
        } else {
            element.setType(BpmnElementType.INTERMEDIATE_CATCH_EVENT);
        }

        if (catchEvent.getTimerEventDefinition() != null) {
            element.setExtensions(new BpmnElementExtensionModel());
            TimerEventExtensionModel timer = new TimerEventExtensionModel();

            if (catchEvent.getTimerEventDefinition().getTimeDate() != null) {
                timer.setType(TimerEventType.DATE);
                timer.setExpression(catchEvent.getTimerEventDefinition().getTimeDate());
            } else if (catchEvent.getTimerEventDefinition().getTimeDuration() != null) {
                timer.setType(TimerEventType.DURATION);
                timer.setExpression(catchEvent.getTimerEventDefinition().getTimeDuration());
            } else if (catchEvent.getTimerEventDefinition().getTimeCycle() != null) {
                timer.setType(TimerEventType.CYCLE);
                timer.setExpression(catchEvent.getTimerEventDefinition().getTimeCycle());
            }

            element.getExtensions().setTimerEventExtension(timer);
        }

        if (catchEvent.getMessageEventDefinition() != null) {
            if (element.getExtensions() == null) {
                element.setExtensions(new BpmnElementExtensionModel());
            }
            MessageEventExtensionModel message = new MessageEventExtensionModel();
            String ref = catchEvent.getMessageEventDefinition().getMessageRef();
            message.setMessageName(messageNames.getOrDefault(ref, ref));
            message.setCorrelationKeyExpression(messageKeys.get(ref));
            element.getExtensions().setMessageEventExtension(message);
        }
        // WO-C8-25: start listeners park the event before its handler runs.
        attachElementStartListeners(element, catchEvent.getExtensionElements());

        return element;
    }

    private BpmnElementModel toElementModel(BpmnIntermediateThrowEventModel throwEvent, Map<String, String> messageNames) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(throwEvent.getId());
        element.setName(throwEvent.getName());
        element.setIncoming(throwEvent.getIncoming());
        element.setOutgoing(throwEvent.getOutgoing());

        if (throwEvent.getMessageEventDefinition() != null) {
            element.setType(BpmnElementType.MESSAGE_THROW_EVENT);
            MessageEventExtensionModel message = new MessageEventExtensionModel();
            String ref = throwEvent.getMessageEventDefinition().getMessageRef();
            message.setMessageName(messageNames.getOrDefault(ref, ref));
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setMessageEventExtension(message);
        } else if (throwEvent.getSignalEventDefinition() != null) {
            element.setType(BpmnElementType.SIGNAL_THROW_EVENT);
        } else if (throwEvent.getEscalationEventDefinition() != null) {
            element.setType(BpmnElementType.ESCALATION_THROW_EVENT);
        } else if (throwEvent.getLinkEventDefinition() != null) {
            element.setType(BpmnElementType.LINK_THROW_EVENT);
        } else if (throwEvent.getCompensateEventDefinition() != null) {
            element.setType(BpmnElementType.COMPENSATION_THROW_EVENT);
            // activityRef (if any) targets a single activity to compensate; null = compensate everything
            String activityRef = throwEvent.getCompensateEventDefinition().getActivityRef();
            if (activityRef != null) {
                EventDefinitionExtensionModel def = new EventDefinitionExtensionModel();
                def.setType(EventDefinitionType.COMPENSATE);
                def.setReference(activityRef);
                element.setExtensions(new BpmnElementExtensionModel());
                element.getExtensions().setEventDefinition(def);
            }
        } else {
            element.setType(BpmnElementType.INTERMEDIATE_THROW_EVENT);
        }
        // WO-C8-16: job-based throw events (zeebe:taskDefinition) park as jobs.
        attachServiceTaskJob(element, throwEvent.getExtensionElements());
        // WO-C8-25: start listeners park the event before its handler runs.
        attachElementStartListeners(element, throwEvent.getExtensionElements());
        return element;
    }

    /** Finds the compensation handler associated with a boundary: the other end of an {@code <association>}
     *  touching the boundary (direction is not significant). Returns null if none. */
    private String resolveCompensationHandler(String boundaryId, List<BpmnAssociationModel> associations) {
        for (BpmnAssociationModel association : associations) {
            if (boundaryId.equals(association.getSourceRef())) {
                return association.getTargetRef();
            }
            if (boundaryId.equals(association.getTargetRef())) {
                return association.getSourceRef();
            }
        }
        return null;
    }

    private BpmnElementModel toElementModel(BpmnCallActivityModel callActivity) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(callActivity.getId());
        element.setName(callActivity.getName());
        element.setType(BpmnElementType.CALL_ACTIVITY);
        element.setOutgoing(callActivity.getOutgoing());
        element.setIncoming(callActivity.getIncoming());
        if (callActivity.getExtensionElements() != null) {
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setCallActivityExtension(new CallActivityExtensionModel());
            CalledElementModel calledElement = Optional.ofNullable(callActivity.getExtensionElements()).map(ExtensionElements::getCalledElement).orElse(null);
            if (calledElement != null) {
                element.getExtensions().getCallActivityExtension().setProcessId(calledElement.getProcessId());
                element.getExtensions().getCallActivityExtension().setBindingType(calledElement.getBindingType());
                element.getExtensions().getCallActivityExtension().setVersionTag(calledElement.getVersionTag());
                // WO-ENG-11: parent→child propagation flag (previously silently dropped by JAXB)
                element.getExtensions().getCallActivityExtension().setPropagateAllParentVariables(calledElement.getPropagateAllParentVariables());
                element.getExtensions().getCallActivityExtension().setPropagateAllChildVariables(calledElement.getPropagateAllChildVariables());
            }
            // WO-ENG-11: zeebe:ioMapping on a call activity was never parsed before — explicit
            // Input mappings seed the child instance, Output mappings override the propagate flag.
            attachIoMapping(element, callActivity.getExtensionElements());
        }
        return element;
    }
}
