package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.BusinessRuleExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.CallActivityExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ScriptTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.IoMappingModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.MappingModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ZeebeLoopCharacteristicsModel;
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
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class BpmnParseServiceImpl implements BpmnParseService {

    @Override
    public com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel parse(String bpmn) throws BpmnParseException {
        try {
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
                    BpmnElementModel element = toSubProcessElement(subProcess, pd, registry, messageNames);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }

            // a <transaction> is an embedded subprocess (same flattening/scope execution); cancel semantics
            // are carried by its cancel-end event and cancel boundary, not the container type
            if (process.getTransactions() != null) {
                for (BpmnSubProcessModel transaction : process.getTransactions()) {
                    BpmnElementModel element = toSubProcessElement(transaction, pd, registry, messageNames);
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

    private BpmnElementModel toSubProcessElement(BpmnSubProcessModel sub, com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel pd, EventDefinitionRegistry registry, Map<String, String> messageNames) {
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
        if (sub.getFlows() != null) {
            for (BpmnSequenceFlowModel flow : sub.getFlows()) {
                pd.addFlow(toFlowModel(flow));
            }
        }

        element.setExtensions(new BpmnElementExtensionModel());
        element.getExtensions().setSubProcessExtension(ext);
        return element;
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
        if (serviceTask.getExtensionElements() != null && serviceTask.getExtensionElements().getTaskDefinition() != null) {
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setServiceTaskExtension(new ServiceTaskExtensionModel());
            element.getExtensions().getServiceTaskExtension().setJob(serviceTask.getExtensionElements().getTaskDefinition().getType());
            element.getExtensions().getServiceTaskExtension().setRetries(serviceTask.getExtensionElements().getTaskDefinition().getRetries());
        }
        attachIoMapping(element, serviceTask.getExtensionElements());
        attachMultiInstance(element, serviceTask.getMultiInstanceLoopCharacteristics());
        return element;
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
            if (userTask.getExtensionElements().getFormDefinition() != null) {
                if (userTask.getExtensionElements().getFormDefinition().getFormKey() != null) {
                    element.getExtensions().getUserTaskExtension().setFormKey(userTask.getExtensionElements().getFormDefinition().getFormKey());
                } else if (userTask.getExtensionElements().getFormDefinition().getExternalReference() != null) {
                    element.getExtensions().getUserTaskExtension().setFormKey(userTask.getExtensionElements().getFormDefinition().getExternalReference());
                    element.getExtensions().getUserTaskExtension().setExternalReference(userTask.getExtensionElements().getFormDefinition().getExternalReference());
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
        return element;
    }

    private BpmnElementModel toElementModel(BpmnParallelGatewayModel parallelGateway) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(parallelGateway.getId());
        element.setName(parallelGateway.getName());
        element.setType(BpmnElementType.PARALLEL_GATEWAY);
        element.setOutgoing(parallelGateway.getOutgoing());
        element.setIncoming(parallelGateway.getIncoming());
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
            CalledElementModel calledElement = Optional.ofNullable(callActivity.getExtensionElements()).map(ExtensionElements::getCalledElement).orElse(null);;
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
