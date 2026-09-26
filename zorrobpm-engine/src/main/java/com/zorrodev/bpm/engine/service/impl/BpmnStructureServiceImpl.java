package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.contract.model.BpmnFlow;
import com.zorrodev.bpm.contract.model.BpmnNode;
import com.zorrodev.bpm.contract.model.BpmnProcessStructure;
import com.zorrodev.bpm.contract.model.BpmnScope;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.bpmn.xml.*;
import com.zorrodev.bpm.engine.bpmn.xml.extension.IoMappingModel;
import com.zorrodev.bpm.engine.service.BpmnStructureService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.xml.SecureXmlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Builds a nested {@link BpmnProcessStructure} from the stored BPMN XML. Uses the JAXB models, which
 * preserve the document hierarchy (sub-process bodies, attached boundary events), unlike the flat
 * execution model. Read-only: it never touches runtime state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmnStructureServiceImpl implements BpmnStructureService {

    private final FileService fileService;
    private final ProcessDefinitionService processDefinitionService;

    /** Resolved definitions-level declarations: ref id -> human name / code. */
    private record Refs(Map<String, String> messageNames,
                        Map<String, String> errorCodes,
                        Map<String, String> signalNames,
                        Map<String, String> escalationCodes) {
    }

    @Override
    public Optional<BpmnProcessStructure> getStructure(UUID processDefinitionId) {
        Optional<ProcessDefinition> definition = processDefinitionService.getProcessDefinitionById(processDefinitionId);
        if (definition.isEmpty()) {
            return Optional.empty();
        }

        String xml;
        try {
            xml = fileService.getFileBytes(processDefinitionId)
                .orElseThrow(() -> new BpmnParseException("BPMN file not found for definition " + processDefinitionId));
        } catch (IOException e) {
            throw new BpmnParseException(e);
        }

        BpmnDefinitionsModel definitions = SecureXmlParser.unmarshal(xml, BpmnDefinitionsModel.class);
        BpmnProcessDefinitionModel process = definitions.getProcess();

        Refs refs = buildRefs(definitions);

        BpmnProcessStructure structure = new BpmnProcessStructure();
        structure.setId(processDefinitionId);
        structure.setKey(definition.get().getKey());
        structure.setVersion(definition.get().getVersion());
        structure.setName(definition.get().getName());
        if (process.getDocumentation() != null && !process.getDocumentation().isBlank()) {
            structure.setDocumentation(process.getDocumentation().trim());
        }

        List<BpmnNode> nodes = structure.getNodes();
        collectProcessNodes(process, refs, nodes);
        structure.setFlows(mapFlows(process.getFlows()));
        attachBoundaryEvents(process.getBoundaryEvents(), refs, nodes);
        applyDocumentation(process, nodes);

        return Optional.of(structure);
    }

    /** Surfaces BPMN &lt;documentation&gt; per element (id -> text) onto the built nodes (incl. boundaries/children). */
    private void applyDocumentation(BpmnProcessDefinitionModel process, List<BpmnNode> nodes) {
        Map<String, String> docs = new HashMap<>();
        addDocs(process.getStartEvents(), docs);
        addDocs(process.getEndEvents(), docs);
        addDocs(process.getServiceTasks(), docs);
        addDocs(process.getSendTasks(), docs);
        addDocs(process.getReceiveTasks(), docs);
        addDocs(process.getUserTasks(), docs);
        addDocs(process.getExclusiveGateways(), docs);
        addDocs(process.getParallelGateways(), docs);
        addDocs(process.getCallActivities(), docs);
        addDocs(process.getManualTasks(), docs);
        addDocs(process.getTasks(), docs);
        addDocs(process.getScriptTasks(), docs);
        addDocs(process.getBusinessRuleTasks(), docs);
        addDocs(process.getInclusiveGateways(), docs);
        addDocs(process.getEventBasedGateways(), docs);
        addDocs(process.getTransactions(), docs);
        addDocs(process.getIntermediateCatchEvents(), docs);
        addDocs(process.getIntermediateThrowEvents(), docs);
        addDocs(process.getSubProcesses(), docs);
        addDocs(process.getBoundaryEvents(), docs);
        // nested elements inside sub-processes share the same models, so one
        // pass over children covers them (no separate nested collection needed
        // for documentation: setDocs below recurses into children anyway, and
        // the docs map is keyed by element id globally).
        if (process.getSubProcesses() != null) {
            for (BpmnSubProcessModel s : process.getSubProcesses()) {
                addDocs(s.getStartEvents(), docs);
                addDocs(s.getEndEvents(), docs);
                addDocs(s.getServiceTasks(), docs);
                addDocs(s.getUserTasks(), docs);
                addDocs(s.getCallActivities(), docs);
                addDocs(s.getIntermediateCatchEvents(), docs);
                addDocs(s.getIntermediateThrowEvents(), docs);
                addDocs(s.getTransactions(), docs);
                addDocs(s.getBoundaryEvents(), docs);
            }
        }
        setDocs(nodes, docs);
    }

    private void addDocs(List<? extends Documented> list, Map<String, String> docs) {
        if (list == null) {
            return;
        }
        for (Documented e : list) {
            if (e.getId() != null && e.getDocumentation() != null && !e.getDocumentation().isBlank()) {
                docs.put(e.getId(), e.getDocumentation().trim());
            }
        }
    }

    private void setDocs(List<BpmnNode> nodes, Map<String, String> docs) {
        for (BpmnNode n : nodes) {
            n.setDocumentation(docs.get(n.getId()));
            for (BpmnNode b : n.getBoundaryEvents()) {
                b.setDocumentation(docs.get(b.getId()));
            }
            if (n.getChildren() != null) {
                setDocs(n.getChildren().getNodes(), docs);
            }
        }
    }

    private Refs buildRefs(BpmnDefinitionsModel definitions) {
        Map<String, String> messages = new HashMap<>();
        if (definitions.getMessages() != null) {
            definitions.getMessages().forEach(m -> messages.put(m.getId(), m.getName()));
        }
        Map<String, String> errors = new HashMap<>();
        if (definitions.getErrors() != null) {
            definitions.getErrors().forEach(e -> errors.put(e.getId(), e.getErrorCode() != null ? e.getErrorCode() : e.getName()));
        }
        Map<String, String> signals = new HashMap<>();
        if (definitions.getSignals() != null) {
            definitions.getSignals().forEach(s -> signals.put(s.getId(), s.getName()));
        }
        Map<String, String> escalations = new HashMap<>();
        if (definitions.getEscalations() != null) {
            definitions.getEscalations().forEach(e -> escalations.put(e.getId(), e.getEscalationCode() != null ? e.getEscalationCode() : e.getName()));
        }
        return new Refs(messages, errors, signals, escalations);
    }

    /** Maps every top-level flow node of the process (boundary events are attached separately). */
    private void collectProcessNodes(BpmnProcessDefinitionModel process, Refs refs, List<BpmnNode> nodes) {
        forEach(process.getStartEvents(), s -> nodes.add(mapStartEvent(s, refs)));
        forEach(process.getEndEvents(), e -> nodes.add(mapEndEvent(e, refs)));
        forEach(process.getServiceTasks(), t -> nodes.add(mapServiceTask(t)));
        forEach(process.getManualTasks(), t -> nodes.add(simpleNode(t.getId(), t.getName(), "manualTask", t.getIncoming(), t.getOutgoing())));
        // WO-DIFF-9: bare <bpmn:task> (untyped) — must stay visible, not silently dropped.
        forEach(process.getTasks(), t -> nodes.add(simpleNode(t.getId(), t.getName(), "task", t.getIncoming(), t.getOutgoing())));
        forEach(process.getScriptTasks(), t -> nodes.add(simpleNode(t.getId(), t.getName(), "scriptTask", t.getIncoming(), t.getOutgoing())));
        forEach(process.getBusinessRuleTasks(), t -> nodes.add(simpleNode(t.getId(), t.getName(), "businessRuleTask", t.getIncoming(), t.getOutgoing())));
        forEach(process.getSendTasks(), t -> nodes.add(mapMessageTask(t.getId(), t.getName(), "sendTask", t.getIncoming(), t.getOutgoing(), t.getMessageRef(), refs)));
        forEach(process.getReceiveTasks(), t -> nodes.add(mapMessageTask(t.getId(), t.getName(), "receiveTask", t.getIncoming(), t.getOutgoing(), t.getMessageRef(), refs)));
        forEach(process.getUserTasks(), t -> nodes.add(mapUserTask(t)));
        forEach(process.getExclusiveGateways(), g -> nodes.add(mapExclusiveGateway(g)));
        forEach(process.getInclusiveGateways(), g -> nodes.add(mapInclusiveGateway(g)));
        forEach(process.getEventBasedGateways(), g -> nodes.add(simpleNode(g.getId(), g.getName(), "eventBasedGateway", g.getIncoming(), g.getOutgoing())));
        forEach(process.getParallelGateways(), g -> nodes.add(simpleNode(g.getId(), g.getName(), "parallelGateway", g.getIncoming(), g.getOutgoing())));
        forEach(process.getIntermediateCatchEvents(), e -> nodes.add(mapCatchEvent(e, refs)));
        forEach(process.getIntermediateThrowEvents(), e -> nodes.add(mapThrowEvent(e, refs)));
        forEach(process.getCallActivities(), c -> nodes.add(mapCallActivity(c)));
        forEach(process.getSubProcesses(), s -> nodes.add(mapSubProcess(s, refs)));
        forEach(process.getTransactions(), s -> nodes.add(mapSubProcess(s, refs)));
    }

    private <T> void forEach(List<T> list, Consumer<T> consumer) {
        if (list != null) {
            list.forEach(consumer);
        }
    }

    // --- flow nodes -----------------------------------------------------------------------------

    private BpmnNode mapStartEvent(BpmnStartEventModel s, Refs refs) {
        BpmnNode node = simpleNode(s.getId(), s.getName(), "startEvent", s.getIncoming(), s.getOutgoing());
        resolveEvent(node, refs, s.getMessageEventDefinition(), s.getTimerEventDefinition(), s.getErrorEventDefinition(),
            s.getSignalEventDefinition(), s.getEscalationEventDefinition(), s.getConditionalEventDefinition(), null, null, false);
        return node;
    }

    private BpmnNode mapEndEvent(BpmnEndEventModel e, Refs refs) {
        BpmnNode node = simpleNode(e.getId(), e.getName(), "endEvent", e.getIncoming(), e.getOutgoing());
        resolveEvent(node, refs, e.getMessageEventDefinition(), null, e.getErrorEventDefinition(), e.getSignalEventDefinition(),
            e.getEscalationEventDefinition(), null, null, e.getCompensateEventDefinition(), e.getTerminateEventDefinition() != null);
        return node;
    }

    private BpmnNode mapCatchEvent(BpmnIntermediateCatchEventModel e, Refs refs) {
        BpmnNode node = simpleNode(e.getId(), e.getName(), "intermediateCatchEvent", e.getIncoming(), e.getOutgoing());
        resolveEvent(node, refs, e.getMessageEventDefinition(), e.getTimerEventDefinition(), null, e.getSignalEventDefinition(),
            null, e.getConditionalEventDefinition(), e.getLinkEventDefinition(), null, false);
        return node;
    }

    private BpmnNode mapThrowEvent(BpmnIntermediateThrowEventModel e, Refs refs) {
        BpmnNode node = simpleNode(e.getId(), e.getName(), "intermediateThrowEvent", e.getIncoming(), e.getOutgoing());
        resolveEvent(node, refs, e.getMessageEventDefinition(), null, null, e.getSignalEventDefinition(),
            e.getEscalationEventDefinition(), null, e.getLinkEventDefinition(), e.getCompensateEventDefinition(), false);
        return node;
    }

    private BpmnNode mapServiceTask(BpmnServiceTaskModel t) {
        BpmnNode node = simpleNode(t.getId(), t.getName(), "serviceTask", t.getIncoming(), t.getOutgoing());
        if (t.getExtensionElements() != null && t.getExtensionElements().getTaskDefinition() != null) {
            put(node, "job", t.getExtensionElements().getTaskDefinition().getType());
        }
        putIoMapping(node, t.getExtensionElements());
        return node;
    }

    private BpmnNode mapMessageTask(String id, String name, String type, List<String> incoming, List<String> outgoing, String messageRef, Refs refs) {
        BpmnNode node = simpleNode(id, name, type, incoming, outgoing);
        if (messageRef != null) {
            put(node, "messageName", refs.messageNames().getOrDefault(messageRef, messageRef));
        }
        return node;
    }

    private BpmnNode mapUserTask(BpmnUserTaskModel t) {
        BpmnNode node = simpleNode(t.getId(), t.getName(), "userTask", t.getIncoming(), t.getOutgoing());
        if (t.getExtensionElements() != null) {
            if (t.getExtensionElements().getAssignmentDefinition() != null) {
                var a = t.getExtensionElements().getAssignmentDefinition();
                put(node, "assignee", a.getAssignee());
                put(node, "candidateUsers", a.getCandidateUsers());
                put(node, "candidateGroups", a.getCandidateGroups());
            }
            if (t.getExtensionElements().getFormDefinition() != null) {
                var f = t.getExtensionElements().getFormDefinition();
                put(node, "formKey", f.getFormKey() != null ? f.getFormKey() : f.getExternalReference());
            }
            putIoMapping(node, t.getExtensionElements());
        }
        return node;
    }

    private BpmnNode mapExclusiveGateway(BpmnExclusiveGatewayModel g) {
        BpmnNode node = simpleNode(g.getId(), g.getName(), "exclusiveGateway", g.getIncoming(), g.getOutgoing());
        put(node, "defaultFlow", g.getDefaultFlow());
        return node;
    }

    private BpmnNode mapInclusiveGateway(BpmnInclusiveGatewayModel g) {
        BpmnNode node = simpleNode(g.getId(), g.getName(), "inclusiveGateway", g.getIncoming(), g.getOutgoing());
        put(node, "defaultFlow", g.getDefaultFlow());
        return node;
    }

    private BpmnNode mapCallActivity(BpmnCallActivityModel c) {
        BpmnNode node = simpleNode(c.getId(), c.getName(), "callActivity", c.getIncoming(), c.getOutgoing());
        if (c.getExtensionElements() != null && c.getExtensionElements().getCalledElement() != null) {
            put(node, "calledProcessId", c.getExtensionElements().getCalledElement().getProcessId());
        }
        putIoMapping(node, c.getExtensionElements());
        return node;
    }

    private BpmnNode mapSubProcess(BpmnSubProcessModel s, Refs refs) {
        BpmnNode node = simpleNode(s.getId(), s.getName(), "subProcess", s.getIncoming(), s.getOutgoing());
        BpmnScope children = new BpmnScope();
        forEach(s.getStartEvents(), e -> children.getNodes().add(mapStartEvent(e, refs)));
        forEach(s.getEndEvents(), e -> children.getNodes().add(mapEndEvent(e, refs)));
        forEach(s.getServiceTasks(), t -> children.getNodes().add(mapServiceTask(t)));
        // WO-DIFF-9: bare <bpmn:task> nested in an embedded subprocess.
        forEach(s.getTasks(), t -> children.getNodes().add(simpleNode(t.getId(), t.getName(), "task", t.getIncoming(), t.getOutgoing())));
        forEach(s.getUserTasks(), t -> children.getNodes().add(mapUserTask(t)));
        forEach(s.getCallActivities(), c -> children.getNodes().add(mapCallActivity(c)));
        forEach(s.getIntermediateCatchEvents(), e -> children.getNodes().add(mapCatchEvent(e, refs)));
        forEach(s.getIntermediateThrowEvents(), e -> children.getNodes().add(mapThrowEvent(e, refs)));
        forEach(s.getExclusiveGateways(), g -> children.getNodes().add(mapExclusiveGateway(g)));
        forEach(s.getParallelGateways(), g -> children.getNodes().add(simpleNode(g.getId(), g.getName(), "parallelGateway", g.getIncoming(), g.getOutgoing())));
        children.setFlows(mapFlows(s.getFlows()));
        attachBoundaryEvents(s.getBoundaryEvents(), refs, children.getNodes());
        node.setChildren(children);
        return node;
    }

    // --- boundary events ------------------------------------------------------------------------

    private void attachBoundaryEvents(List<BpmnBoundaryEventModel> boundaries, Refs refs, List<BpmnNode> nodes) {
        if (boundaries == null) {
            return;
        }
        Map<String, BpmnNode> byId = new HashMap<>();
        index(nodes, byId);
        for (BpmnBoundaryEventModel b : boundaries) {
            BpmnNode node = simpleNode(b.getId(), b.getName(), "boundaryEvent", b.getIncoming(), b.getOutgoing());
            put(node, "attachedToRef", b.getAttachedToRef());
            // BPMN: cancelActivity defaults to true (interrupting) when absent
            node.getProperties().put("cancelActivity", b.getCancelActivity() == null || b.getCancelActivity());
            resolveEvent(node, refs, b.getMessageEventDefinition(), b.getTimerEventDefinition(), b.getErrorEventDefinition(),
                b.getSignalEventDefinition(), b.getEscalationEventDefinition(), b.getConditionalEventDefinition(), null,
                b.getCompensateEventDefinition(), false);
            BpmnNode host = byId.get(b.getAttachedToRef());
            if (host != null) {
                host.getBoundaryEvents().add(node);
            } else {
                // host not found (should not happen in valid BPMN): keep it visible at top level
                nodes.add(node);
            }
        }
    }

    /** Indexes top-level nodes and sub-process children so a boundary can find its host activity. */
    private void index(List<BpmnNode> nodes, Map<String, BpmnNode> byId) {
        for (BpmnNode n : nodes) {
            byId.put(n.getId(), n);
            if (n.getChildren() != null) {
                index(n.getChildren().getNodes(), byId);
            }
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private BpmnNode simpleNode(String id, String name, String type, List<String> incoming, List<String> outgoing) {
        BpmnNode node = new BpmnNode();
        node.setId(id);
        node.setName(name);
        node.setType(type);
        if (incoming != null) {
            node.setIncoming(new ArrayList<>(incoming));
        }
        if (outgoing != null) {
            node.setOutgoing(new ArrayList<>(outgoing));
        }
        return node;
    }

    private void put(BpmnNode node, String key, Object value) {
        if (value != null) {
            node.getProperties().put(key, value);
        }
    }

    /**
     * WO-ENG-14: static ioMapping declaration ({@code source → target} from the
     * BPMN itself, before any execution). Only non-empty lists are stored —
     * elements without ioMapping get no keys at all (like other properties here).
     */
    private void putIoMapping(BpmnNode node, ExtensionElements ext) {
        if (ext == null || ext.getIoMapping() == null) {
            return;
        }
        IoMappingModel io = ext.getIoMapping();
        if (io.getInputs() != null && !io.getInputs().isEmpty()) {
            put(node, "inputMappings", io.getInputs().stream()
                .map(m -> Map.of("source", String.valueOf(m.getSource()), "target", String.valueOf(m.getTarget())))
                .toList());
        }
        if (io.getOutputs() != null && !io.getOutputs().isEmpty()) {
            put(node, "outputMappings", io.getOutputs().stream()
                .map(m -> Map.of("source", String.valueOf(m.getSource()), "target", String.valueOf(m.getTarget())))
                .toList());
        }
    }

    private List<BpmnFlow> mapFlows(List<BpmnSequenceFlowModel> flows) {
        List<BpmnFlow> result = new ArrayList<>();
        if (flows == null) {
            return result;
        }
        for (BpmnSequenceFlowModel f : flows) {
            BpmnFlow flow = new BpmnFlow();
            flow.setId(f.getId());
            flow.setSourceRef(f.getSourceRef());
            flow.setTargetRef(f.getTargetRef());
            if (f.getConditionExpression() != null) {
                flow.setConditionExpression(f.getConditionExpression().getExpression());
            }
            result.add(flow);
        }
        return result;
    }

    /**
     * Sets {@code eventDefinition} and the resolved name/code/expression properties on the node, given
     * whichever raw event definitions were present (all but one are typically null).
     */
    private void resolveEvent(BpmnNode node, Refs refs,
                              BpmnMessageEventDefinitionModel message,
                              BpmnTimerEventDefinitionModel timer,
                              BpmnErrorEventDefinitionModel error,
                              BpmnSignalEventDefinitionModel signal,
                              BpmnEscalationEventDefinitionModel escalation,
                              BpmnConditionalEventDefinitionModel conditional,
                              BpmnLinkEventDefinitionModel link,
                              BpmnCompensateEventDefinitionModel compensate,
                              boolean terminate) {
        if (terminate) {
            node.setEventDefinition("terminate");
        } else if (message != null) {
            node.setEventDefinition("message");
            put(node, "messageName", refs.messageNames().getOrDefault(message.getMessageRef(), message.getMessageRef()));
        } else if (timer != null) {
            node.setEventDefinition("timer");
            if (timer.getTimeDate() != null) {
                put(node, "timerType", "date");
                put(node, "timerExpression", timer.getTimeDate());
            } else if (timer.getTimeDuration() != null) {
                put(node, "timerType", "duration");
                put(node, "timerExpression", timer.getTimeDuration());
            }
        } else if (error != null) {
            node.setEventDefinition("error");
            put(node, "errorCode", refs.errorCodes().getOrDefault(error.getErrorRef(), error.getErrorRef()));
        } else if (signal != null) {
            node.setEventDefinition("signal");
            put(node, "signalName", refs.signalNames().getOrDefault(signal.getSignalRef(), signal.getSignalRef()));
        } else if (escalation != null) {
            node.setEventDefinition("escalation");
            put(node, "escalationCode", refs.escalationCodes().getOrDefault(escalation.getEscalationRef(), escalation.getEscalationRef()));
        } else if (conditional != null) {
            node.setEventDefinition("conditional");
            put(node, "condition", conditional.getCondition());
        } else if (link != null) {
            node.setEventDefinition("link");
            put(node, "linkName", link.getName());
        } else if (compensate != null) {
            node.setEventDefinition("compensate");
        }
    }
}
