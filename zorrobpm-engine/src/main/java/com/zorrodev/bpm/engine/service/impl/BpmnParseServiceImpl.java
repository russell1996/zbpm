package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.CallActivityExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionType;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.bpmn.xml.*;
import com.zorrodev.bpm.engine.bpmn.xml.extension.CalledElementModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnConditionExpressionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ExclusiveGatewayExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import jakarta.xml.bind.JAXB;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class BpmnParseServiceImpl implements BpmnParseService {

    @Override
    public com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel parse(String bpmn) throws BpmnParseException {
        try {
            BpmnDefinitionsModel definitions = JAXB.unmarshal(new StringReader(bpmn), BpmnDefinitionsModel.class);
            BpmnProcessDefinitionModel process = definitions.getProcess();

            checkBpmn(process);

            Map<String, String> messageNames = new HashMap<>();
            if (definitions.getMessages() != null) {
                for (com.zorrodev.bpm.engine.bpmn.xml.BpmnMessageModel message : definitions.getMessages()) {
                    messageNames.put(message.getId(), message.getName());
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
                    BpmnElementModel element = toElementModel(receiveTask, messageNames);
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
            if (Optional.ofNullable(process.getFlows()).isPresent()) {
                for (BpmnSequenceFlowModel flow : process.getFlows()) {
                    BpmnFlowModel element = toFlowModel(flow);
                    pd.addFlow(element);
                }
            }
            if (process.getIntermediateCatchEvents() != null) {
                for (BpmnIntermediateCatchEventModel catchEvent : process.getIntermediateCatchEvents()) {
                    BpmnElementModel element = toElementModel(catchEvent, messageNames);
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
                    BpmnElementModel element = toSubProcessElement(subProcess, pd, registry);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }

            if (process.getBoundaryEvents() != null) {
                for (BpmnBoundaryEventModel boundaryEvent : process.getBoundaryEvents()) {
                    boolean timer = boundaryEvent.getTimerEventDefinition() != null;
                    boolean error = boundaryEvent.getErrorEventDefinition() != null;
                    boolean message = boundaryEvent.getMessageEventDefinition() != null;
                    if (!timer && !error && !message) {
                        continue; // only timer, error and message boundaries are executable today
                    }
                    BpmnElementModel element = toBoundaryElement(boundaryEvent);
                    element.setProcessDefinition(pd);
                    attachEventDefinition(element, boundaryEvent.getErrorEventDefinition(), null, null, null, null, null, registry);
                    if (message) {
                        MessageEventExtensionModel msg = new MessageEventExtensionModel();
                        String ref = boundaryEvent.getMessageEventDefinition().getMessageRef();
                        msg.setMessageName(messageNames.getOrDefault(ref, ref));
                        element.getExtensions().setMessageEventExtension(msg);
                    }
                    pd.addElement(element);
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
            }
            element.getExtensions().setTimerEventExtension(timer);
        } else if (boundaryEvent.getErrorEventDefinition() != null) {
            // error boundaries are always interrupting; the error code is resolved into the
            // element's eventDefinition extension by attachEventDefinition in the caller
            element.setType(BpmnElementType.ERROR_BOUNDARY_EVENT);
        } else if (boundaryEvent.getMessageEventDefinition() != null) {
            // the message name is resolved into messageEventExtension by the caller
            element.setType(BpmnElementType.MESSAGE_BOUNDARY_EVENT);
        }

        return element;
    }

    private BpmnElementModel toSubProcessElement(BpmnSubProcessModel sub, com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel pd, EventDefinitionRegistry registry) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(sub.getId());
        element.setName(sub.getName());
        element.setType(BpmnElementType.SUB_PROCESS);
        if (sub.getIncoming() != null) {
            element.getIncoming().addAll(sub.getIncoming());
        }
        if (sub.getOutgoing() != null) {
            element.getOutgoing().addAll(sub.getOutgoing());
        }

        com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel ext = new com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel();

        // flatten nested flow nodes and flows into the same process definition
        if (sub.getStartEvents() != null) {
            for (BpmnStartEventModel start : sub.getStartEvents()) {
                BpmnElementModel child = toElementModel(start);
                child.setProcessDefinition(pd);
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
            .count();
        // at most one plain (none) start is allowed; a process may instead start via message/timer
        // start events, so zero plain starts is valid as long as some start event exists
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
        }
        return element;
    }

    private BpmnElementModel toElementModel(BpmnSendTaskModel sendTask, Map<String, String> messageNames) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(sendTask.getId());
        element.setName(sendTask.getName());
        element.setType(BpmnElementType.SEND_TASK);
        element.setIncoming(sendTask.getIncoming());
        element.setOutgoing(sendTask.getOutgoing());
        if (sendTask.getMessageRef() != null) {
            MessageEventExtensionModel message = new MessageEventExtensionModel();
            message.setMessageName(messageNames.getOrDefault(sendTask.getMessageRef(), sendTask.getMessageRef()));
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setMessageEventExtension(message);
        }
        return element;
    }

    private BpmnElementModel toElementModel(BpmnReceiveTaskModel receiveTask, Map<String, String> messageNames) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(receiveTask.getId());
        element.setName(receiveTask.getName());
        element.setType(BpmnElementType.RECEIVE_TASK);
        element.setIncoming(receiveTask.getIncoming());
        element.setOutgoing(receiveTask.getOutgoing());
        if (receiveTask.getMessageRef() != null) {
            MessageEventExtensionModel message = new MessageEventExtensionModel();
            message.setMessageName(messageNames.getOrDefault(receiveTask.getMessageRef(), receiveTask.getMessageRef()));
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
                }
            }
        }
        return element;
    }

    private BpmnElementModel toElementModel(BpmnEndEventModel endEvent) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(endEvent.getId());
        element.setName(endEvent.getName());
        if (endEvent.getTerminateEventDefinition() != null) {
            element.setType(BpmnElementType.TERMINATE_END_EVENT);
        } else if (endEvent.getErrorEventDefinition() != null) {
            element.setType(BpmnElementType.ERROR_END_EVENT);
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

    private BpmnElementModel toElementModel(BpmnIntermediateCatchEventModel catchEvent, Map<String, String> messageNames) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(catchEvent.getId());
        element.setName(catchEvent.getName());
        element.setIncoming(catchEvent.getIncoming());
        element.setOutgoing(catchEvent.getOutgoing());

        if (catchEvent.getMessageEventDefinition() != null) {
            element.setType(BpmnElementType.MESSAGE_CATCH_EVENT);
        } else if (catchEvent.getTimerEventDefinition() != null) {
            element.setType(BpmnElementType.TIMER_CATCH_EVENT);
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
        } else {
            element.setType(BpmnElementType.INTERMEDIATE_THROW_EVENT);
        }

        return element;
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
                element.getExtensions().getCallActivityExtension().setPropagateAllChildVariables(calledElement.getPropagateAllChildVariables());
            }
        }
        return element;
    }
}
