package com.zorrodev.bpm.engine.bpmn.xml;

import com.zorrodev.bpm.engine.bpmn.model.UnsupportedBpmnConstruct;
import com.zorrodev.bpm.engine.xml.SecureXmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WO-ENG-34: finds the executable constructs this engine does NOT implement, so deploy can refuse
 * the model instead of executing it with different meaning.
 *
 * <p>Why a document walk and not more JAXB fields: JAXB binds a fixed shape and drops everything
 * outside it silently — that silent drop IS the defect this WO closes (a {@code standardLoop} ran
 * once, a {@code complexGateway} vanished, a multi-instance container ran once). No amount of
 * field-adding in the bound classes makes the check honest for {@code <complexGateway>}, and adding
 * fields risks rebinding quirks for models that work today. The document is what the author
 * actually wrote, so it is what gets inspected.
 *
 * <p>Scope: EXECUTABLE constructs only. Graphical DI ({@code bpmndi:*}), {@code documentation},
 * {@code <extensionElements>} and vendor extensions are deliberately untouched — they are
 * decorative and ignoring them is correct, so they must never be reported here.
 *
 * <p>The scan never throws and never rejects: it RECORDS findings on the parsed model, and
 * {@code ProcessDefinitionServiceImpl} is the single place that turns them into an HTTP 400.
 * Models stored by an earlier release therefore keep parsing (no runtime regression on upgrade)
 * while every NEW deploy of an unsupported model is refused.
 */
public final class BpmnSupportScanner {

    /** The one namespace the JAXB models bind — checks use it so findings match what parsing sees. */
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /**
     * The element kinds multi-instance is NOT implemented for. MI binds only on serviceTask and
     * userTask (both call {@code attachMultiInstance}); on a container it parses into an ordinary
     * single-instance element and runs once — the silent semantic change this WO closes.
     *
     * <p>Deliberately a positive list, not "everything except serviceTask/userTask": CTO scoped this
     * WO to containers, and widening it silently would change behaviour for element kinds this WO
     * never characterised (see the report's "найдено рядом" for multiInstanceLoopCharacteristics on
     * intermediateThrowEvent, which keeps today's behaviour).
     */
    private static final Set<String> MULTI_INSTANCE_UNSUPPORTED_HOSTS =
        Set.of("subProcess", "transaction", "adHocSubProcess", "callActivity");

    private BpmnSupportScanner() {
    }

    /**
     * @param bpmn                 the raw XML exactly as it was deployed
     * @param resolvableNodeIds    ids of the flow nodes the parser actually put into the executable
     *                             model ({@code BpmnProcessDefinitionModel.getElements()}) — sequence
     *                             flow refs are resolved against THIS set, not against every {@code id}
     *                             in the document, because a ref into a graphical-DI shape or a node
     *                             the engine drops is exactly the unresolvable case
     * @return findings in a fixed order (see {@link #CODES}); empty when the model is fully supported
     */
    public static List<UnsupportedBpmnConstruct> scan(String bpmn, Set<String> resolvableNodeIds) {
        Document document = SecureXmlParser.parseDocument(bpmn);
        List<UnsupportedBpmnConstruct> findings = new ArrayList<>();

        // 1. several <process> in one resource: the executable model holds exactly one, so the
        //    others were silently dropped (JAXB keeps the last one in document order).
        List<String> processIds = localNames(document.getDocumentElement(), "process");
        if (processIds.size() > 1) {
            findings.add(new UnsupportedBpmnConstruct(
                UnsupportedBpmnConstructCodes.MULTIPLE_PROCESSES_IN_RESOURCE, processIds,
                "BPMN resource declares " + processIds.size() + " executable <process> elements ("
                    + String.join(", ", processIds) + ") but a deployment holds exactly one process. "
                    + "Deploy each <process> as its own BPMN file."));
        }

        // 2. <complexGateway> is not an element kind in this engine at all: the flow parked on a
        //    "target not found" incident instead. Refuse the model, not the first token reaching it.
        //    Collected by element NAME (the element IS the construct), unlike the ones below which
        //    are children of a host element.
        List<String> complexGateways = localNames(document.getDocumentElement(), "complexGateway");
        if (!complexGateways.isEmpty()) {
            findings.add(new UnsupportedBpmnConstruct(
                UnsupportedBpmnConstructCodes.UNSUPPORTED_COMPLEX_GATEWAY, complexGateways,
                "Complex gateway is not supported (like Camunda 8): <complexGateway> "
                    + String.join(", ", complexGateways) + " would be dropped and the flow would park "
                    + "on a 'target not found' incident. Replace it with an inclusive or parallel gateway."));
        }

        // 3. the standard BPMN loop: no loop field exists anywhere in the model, so the activity
        //    executed exactly ONCE while the XML asked for up to loopMaximum iterations.
        List<String> standardLoops = idsOfElementsWithChild(document, null, "standardLoopCharacteristics");
        if (!standardLoops.isEmpty()) {
            findings.add(new UnsupportedBpmnConstruct(
                UnsupportedBpmnConstructCodes.UNSUPPORTED_STANDARD_LOOP, standardLoops,
                "Standard loop (standardLoopCharacteristics) is not supported: "
                    + String.join(", ", standardLoops) + " would run once instead of looping. "
                    + "Use multi-instance (supported on serviceTask/userTask) or model the repetition "
                    + "explicitly."));
        }

        // 4. multi-instance on a container: parsed into an ordinary single-instance container.
        List<String> multiInstanceOnContainers = idsOfElementsWithChild(
            document, MULTI_INSTANCE_UNSUPPORTED_HOSTS, "multiInstanceLoopCharacteristics");
        if (!multiInstanceOnContainers.isEmpty()) {
            findings.add(new UnsupportedBpmnConstruct(
                UnsupportedBpmnConstructCodes.UNSUPPORTED_MULTI_INSTANCE_CONTAINER,
                multiInstanceOnContainers,
                "Multi-instance is supported on serviceTask and userTask only: "
                    + String.join(", ", multiInstanceOnContainers) + " carries "
                    + "multiInstanceLoopCharacteristics on a container and would run once. "
                    + "Move the multi-instance marker onto a task inside the container, or drop it."));
        }

        // 5. conditional start event: became an ordinary none-start (the condition was parsed but
        //    nothing ever read it), and it even consumed the "at most one plain start" budget.
        List<String> conditionalStarts = idsOfElementsWithChild(
            document, Set.of("startEvent"), "conditionalEventDefinition");
        if (!conditionalStarts.isEmpty()) {
            findings.add(new UnsupportedBpmnConstruct(
                UnsupportedBpmnConstructCodes.UNSUPPORTED_CONDITIONAL_START_EVENT, conditionalStarts,
                "Conditional start event is not supported: " + String.join(", ", conditionalStarts)
                    + " would start the process unconditionally instead of waiting for its condition. "
                    + "Use a message or timer start event."));
        }

        // 6. sequence flow refs that resolve to nothing in the executable model: accepted at deploy,
        //    parked the instance on an incident at the first token that followed the flow.
        List<String> danglingFlows = unresolvedFlows(document, resolvableNodeIds);
        if (!danglingFlows.isEmpty()) {
            findings.add(new UnsupportedBpmnConstruct(
                UnsupportedBpmnConstructCodes.UNRESOLVED_SEQUENCE_FLOW_REF, danglingFlows,
                "Sequence flow(s) " + String.join(", ", danglingFlows) + " have a sourceRef/targetRef "
                    + "that matches no flow node of the process (including nodes nested in "
                    + "sub-processes). Fix the reference or delete the flow."));
        }

        return List.copyOf(findings);
    }

    /**
     * Ids of every BPMN element of one of {@code hostNames} that carries a {@code childName} child
     * (null = any host). Document order, de-duplicated: a stable answer keeps the 400 message
     * deterministic for the same model.
     */
    private static List<String> idsOfElementsWithChild(Document document,
                                                       Set<String> hostNames,
                                                       String childName) {
        Set<String> seen = new LinkedHashSet<>();
        collect(document.getDocumentElement(), seen, hostNames, childName);
        return List.copyOf(seen);
    }

    private static void collect(Element element, Set<String> ids, Set<String> hostNames,
                                String childName) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element childElement = (Element) child;
            if (!isBpmn(childElement)) {
                continue;
            }
            String name = localName(childElement);
            if ((hostNames == null || hostNames.contains(name))
                && hasBpmnChild(childElement, childName)) {
                ids.add(label(childElement));
            }
            // recurse: sub-process/transaction/adHoc bodies carry the same element kinds, and a
            // construct hidden inside a nested container must be found exactly like a top-level one
            collect(childElement, ids, hostNames, childName);
        }
    }

    /**
     * Flows whose {@code sourceRef}/{@code targetRef} is absent, or points at an id the executable
     * model does not contain. Reported per FLOW (one id, both endpoints folded in) so the message
     * names the thing the modeller has to edit.
     */
    private static List<String> unresolvedFlows(Document document, Set<String> resolvableNodeIds) {
        List<String> flows = new ArrayList<>();
        collectFlows(document.getDocumentElement(), flows, resolvableNodeIds);
        return flows;
    }

    private static void collectFlows(Element element, List<String> flows, Set<String> resolvable) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element childElement = (Element) child;
            if (!isBpmn(childElement)) {
                continue;
            }
            if ("sequenceFlow".equals(localName(childElement))
                && (!resolves(childElement.getAttribute("sourceRef"), resolvable)
                    || !resolves(childElement.getAttribute("targetRef"), resolvable))) {
                flows.add(label(childElement));
            }
            collectFlows(childElement, flows, resolvable);
        }
    }

    /** A ref must be present and name a node of the executable model. */
    private static boolean resolves(String ref, Set<String> resolvableNodeIds) {
        return ref != null && !ref.isBlank() && resolvableNodeIds.contains(ref);
    }

    private static boolean hasBpmnChild(Element element, String childName) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                && isBpmn((Element) child)
                && childName.equals(localName((Element) child))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> localNames(Element root, String name) {
        List<String> found = new ArrayList<>();
        collectNames(root, found, name);
        return found;
    }

    private static void collectNames(Element element, List<String> found, String name) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element childElement = (Element) child;
            if (!isBpmn(childElement)) {
                continue;
            }
            if (name.equals(localName(childElement))) {
                found.add(label(childElement));
            }
            collectNames(childElement, found, name);
        }
    }

    private static boolean isBpmn(Element element) {
        String ns = element.getNamespaceURI();
        return ns == null ? element.getPrefix() == null : BPMN_NS.equals(ns);
    }

    private static String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
    }

    /** The element id, or {@code <kind>} when the author left the id out — the message stays actionable. */
    private static String label(Element element) {
        String id = element.getAttribute("id");
        return id == null || id.isBlank() ? "<" + localName(element) + ">" : id;
    }

    /** Stable finding order, so a model with several defects always reports the same one first. */
    public static final List<String> CODES = List.of(
        UnsupportedBpmnConstructCodes.MULTIPLE_PROCESSES_IN_RESOURCE,
        UnsupportedBpmnConstructCodes.UNSUPPORTED_COMPLEX_GATEWAY,
        UnsupportedBpmnConstructCodes.UNSUPPORTED_STANDARD_LOOP,
        UnsupportedBpmnConstructCodes.UNSUPPORTED_MULTI_INSTANCE_CONTAINER,
        UnsupportedBpmnConstructCodes.UNSUPPORTED_CONDITIONAL_START_EVENT,
        UnsupportedBpmnConstructCodes.UNRESOLVED_SEQUENCE_FLOW_REF);

    /** Distinct, never reused: an API client keys its message on these. */
    public static final class UnsupportedBpmnConstructCodes {
        public static final String MULTIPLE_PROCESSES_IN_RESOURCE = "MULTIPLE_PROCESSES_IN_RESOURCE";
        public static final String UNSUPPORTED_COMPLEX_GATEWAY = "UNSUPPORTED_COMPLEX_GATEWAY";
        public static final String UNSUPPORTED_STANDARD_LOOP = "UNSUPPORTED_STANDARD_LOOP";
        public static final String UNSUPPORTED_MULTI_INSTANCE_CONTAINER = "UNSUPPORTED_MULTI_INSTANCE_CONTAINER";
        public static final String UNSUPPORTED_CONDITIONAL_START_EVENT = "UNSUPPORTED_CONDITIONAL_START_EVENT";
        public static final String UNRESOLVED_SEQUENCE_FLOW_REF = "UNRESOLVED_SEQUENCE_FLOW_REF";

        private UnsupportedBpmnConstructCodes() {
        }
    }
}
