package com.zorrodev.bpm.engine.bpmn.xml;

import com.zorrodev.bpm.engine.bpmn.model.UnsupportedBpmnConstruct;
import com.zorrodev.bpm.engine.xml.SecureXmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;

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
     * never characterised. WO-ENG-34 раунд 10 уточнил формулировку: прежняя версия этого javadoc
     * обосновывала сужение тем, что multiInstanceLoopCharacteristics встречается на
     * {@code intermediateThrowEvent}, — но {@code tThrowEvent} по XSD не содержит
     * {@code loopCharacteristics}, так что такого документа не бывает. Реальное основание: MI не
     * привязывается ещё на шести видах activity ({@code task}, {@code scriptTask},
     * {@code manualTask}, {@code businessRuleTask}, {@code sendTask}, {@code receiveTask}), и они
     * сохраняют сегодняшнее поведение; решение о расширении списка — за CTO (рецензия Н-2,
     * follow-up WO).
     */
    private static final Set<String> MULTI_INSTANCE_UNSUPPORTED_HOSTS =
        Set.of("subProcess", "transaction", "adHocSubProcess", "callActivity");

    /**
     * The BPMN 2.0 flow-node kinds — the only elements a {@code sequenceFlow} may legally point at,
     * so the only ones that make a {@code sourceRef}/{@code targetRef} resolvable.
     *
     * <p>Derived from the {@code tFlowNode} closure of the BPMN 2.0 XSD that ships in this repo
     * ({@code zorrobpm-frontend/node_modules/bpmn-moddle/resources/bpmn/xsd/Semantic.xsd}),
     * narrowed to the element names a document can actually contain:
     * <ul>
     *   <li><b>omitted on purpose:</b> every element whose XSD type is {@code abstract="true"} —
     *       {@code activity}, {@code event}, {@code flowNode}, {@code gateway}, {@code catchEvent},
     *       {@code throwEvent} and {@code choreographyActivity} ({@code tChoreographyActivity},
     *       {@code Semantic.xsd:234}). They cannot appear in a document, so listing them would
     *       contradict this rule;</li>
     *   <li><b>added beyond the XSD:</b> the {@code *StartEvent}/{@code terminateEndEvent} TAG
     *       spellings — not BPMN 2.0 elements (the XSD has only {@code startEvent}/{@code endEvent}
     *       plus the definitions), but they are what Zeebe/Camunda exports contain, and this engine's
     *       JAXB model binds only {@code <startEvent>}, so such a node is in the document and NOT in
     *       the executable model. Naming it here is what keeps the refusal message truthful: the
     *       question this check answers is "does the DOCUMENT declare that node", not "did the parser
     *       manage to model it" — see {@link #unresolvedFlows}. The elements themselves remain
     *       unsupported by the engine (a separate finding for CTO), they just must not be mislabelled
     *       as a typo.</li>
     * </ul>
     *
     * <p>An absent kind is not cosmetic: every flow pointing at it becomes a false "dangling" finding,
     * i.e. the deploy gate rejects a valid model and tells its author to look for a typo that is not
     * there. That is precisely the failure mode of the first cut of this check, which resolved refs
     * against the parsed model and reported every flow out of a {@code <messageStartEvent>} as
     * unresolvable. Verified against the XSD: {@code implicitThrowEvent} (substitutable, and the one
     * the verifier caught missing) and the concrete choreography nodes are included.
     */
    private static final Set<String> FLOW_NODE_NAMES = Set.of(
        // events
        "startEvent", "endEvent", "boundaryEvent", "intermediateCatchEvent", "intermediateThrowEvent",
        "implicitThrowEvent",
        // activities
        "task", "serviceTask", "userTask", "scriptTask", "manualTask", "businessRuleTask",
        "sendTask", "receiveTask", "subProcess", "transaction", "adHocSubProcess", "callActivity",
        // gateways
        "exclusiveGateway", "inclusiveGateway", "parallelGateway", "complexGateway",
        "eventBasedGateway",
        // choreography activities (concrete ones from the same closure)
        "choreographyTask", "subChoreography", "callChoreography",
        // NOT in BPMN 2.0 XSD, but real in Zeebe/Camunda exports — see the javadoc above
        "messageStartEvent", "timerStartEvent", "signalStartEvent", "terminateEndEvent");

    private BpmnSupportScanner() {
    }

    /**
     * @param bpmn  the raw XML exactly as it was deployed
     * @return findings in a fixed order (see {@link #CODES}); empty when the model is fully supported
     */
    public static List<UnsupportedBpmnConstruct> scan(String bpmn) {
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
        //    Both schema-legal spellings count — see loopMarkerOf for why the element NAME alone
        //    was not enough (an independent red-team pass defeated the name-only check with one
        //    XSD-legal `xsi:type` attribute on 22 of 22 host × type combinations).
        List<String> standardLoops = idsOfElementsWithLoopKind(document, null, LoopKind.STANDARD);
        if (!standardLoops.isEmpty()) {
            findings.add(new UnsupportedBpmnConstruct(
                UnsupportedBpmnConstructCodes.UNSUPPORTED_STANDARD_LOOP, standardLoops,
                "Standard loop (standardLoopCharacteristics) is not supported: "
                    + String.join(", ", standardLoops) + " would run once instead of looping. "
                    + "Use multi-instance (supported on serviceTask/userTask) or model the repetition "
                    + "explicitly."));
        }

        // 4. multi-instance on a container: parsed into an ordinary single-instance container.
        List<String> multiInstanceOnContainers = idsOfElementsWithLoopKind(
            document, MULTI_INSTANCE_UNSUPPORTED_HOSTS, LoopKind.MULTI_INSTANCE);
        if (!multiInstanceOnContainers.isEmpty()) {
            findings.add(new UnsupportedBpmnConstruct(
                UnsupportedBpmnConstructCodes.UNSUPPORTED_MULTI_INSTANCE_CONTAINER,
                multiInstanceOnContainers,
                "Multi-instance is supported on serviceTask and userTask only: "
                    + String.join(", ", multiInstanceOnContainers) + " carries "
                    + "multiInstanceLoopCharacteristics on a container and would run once. "
                    + "Move the multi-instance marker onto a task inside the container, or drop it."));
        }

        // 4b. multi-instance written as the schema head element <loopCharacteristics xsi:type=…>,
        //     on a host that is NOT a refused container. Distinct code on purpose: the marker is
        //     refused here because it is UNREADABLE, not because multi-instance is unsupported on
        //     that kind — the direct spelling on the same host is checked by rule 4, and the two
        //     answers must not be folded into one code whose message would be false here.
        List<String> unreadableMultiInstance = idsOfUnreadableMultiInstance(document);
        if (!unreadableMultiInstance.isEmpty()) {
            findings.add(new UnsupportedBpmnConstruct(
                UnsupportedBpmnConstructCodes.UNSUPPORTED_MULTI_INSTANCE_XSI_TYPE,
                unreadableMultiInstance,
                "Multi-instance written as <loopCharacteristics xsi:type=\"…"
                    + "tMultiInstanceLoopCharacteristics\"> is not readable by this engine: "
                    + String.join(", ", unreadableMultiInstance) + " would run once instead of "
                    + "creating one instance per item, on every activity kind including the ones "
                    + "that do support multi-instance. Write the concrete element "
                    + "<multiInstanceLoopCharacteristics> instead."));
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

        // 6. sequence flow refs that resolve to nothing in the process they belong to: accepted at
        //    deploy, parked the instance on an incident at the first token that followed the flow.
        List<String> danglingFlows = unresolvedFlows(document);
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
     * Ids of every BPMN element of one of {@code hostNames} that carries a loop marker of
     * {@code kind} (null = any host). Document order, de-duplicated: a stable answer keeps the 400
     * message deterministic for the same model.
     */
    private static List<String> idsOfElementsWithLoopKind(Document document,
                                                           Set<String> hostNames,
                                                           LoopKind kind) {
        Set<String> seen = new LinkedHashSet<>();
        collectLoopMarkers(document.getDocumentElement(), seen, hostNames, kind);
        return List.copyOf(seen);
    }

    /**
     * Ids of the hosts whose multi-instance marker is written in the spelling the parser cannot
     * read, and which rule 4 does not already refuse as containers — i.e. the honest remainder,
     * with no overlap between the two findings.
     */
    private static List<String> idsOfUnreadableMultiInstance(Document document) {
        Set<String> seen = new LinkedHashSet<>();
        collectUnreadableMultiInstance(document.getDocumentElement(), seen);
        return List.copyOf(seen);
    }

    private static void collectUnreadableMultiInstance(Element element, Set<String> ids) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !isBpmn((Element) child)) {
                continue;
            }
            Element childElement = (Element) child;
            LoopMarker marker = loopMarkerOf(childElement);
            if (marker.kind() == LoopKind.MULTI_INSTANCE && marker.unreadableSpelling()
                && !MULTI_INSTANCE_UNSUPPORTED_HOSTS.contains(localName(childElement))) {
                ids.add(label(childElement));
            }
            collectUnreadableMultiInstance(childElement, ids);
        }
    }

    private static void collectLoopMarkers(Element element, Set<String> ids, Set<String> hostNames,
                                           LoopKind kind) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !isBpmn((Element) child)) {
                continue;
            }
            Element childElement = (Element) child;
            if ((hostNames == null || hostNames.contains(localName(childElement)))
                && loopMarkerOf(childElement).kind() == kind) {
                ids.add(label(childElement));
            }
            // recurse: sub-process/transaction/adHoc bodies carry the same element kinds, and a
            // construct hidden inside a nested container must be found exactly like a top-level one
            collectLoopMarkers(childElement, ids, hostNames, kind);
        }
    }

    /** Which loop an element declares, if any: the two concrete kinds of {@code tLoopCharacteristics}. */
    private enum LoopKind {
        NONE, STANDARD, MULTI_INSTANCE
    }

    /**
     * A loop marker on one host: which kind of loop it declares, and whether it is spelled in the
     * form the parser cannot read.
     *
     * @param unreadableSpelling  true for {@code <loopCharacteristics xsi:type="…">} — the schema
     *                             head element, which JAXB binds by ELEMENT NAME and therefore
     *                             silently drops (a multi-instance marker this way leaves the
     *                             activity running once, on every kind, including serviceTask and
     *                             userTask where multi-instance itself IS implemented)
     *
     * <p>Documented limit: the FIRST loop marker child decides. A schema-VALID activity can carry at
     * most one (the schema sequence allows one), so nothing is lost in practice; a document carrying
     * both spellings is schema-invalid and the deploy path performs no XSD validation (recorded in
     * the report as a hardening follow-up), so the second marker goes unreported rather than being
     * guessed at.
     */
    private record LoopMarker(LoopKind kind, boolean unreadableSpelling) {
        private static final LoopMarker NONE = new LoopMarker(LoopKind.NONE, false);

        static LoopMarker of(LoopKind kind) {
            return kind == LoopKind.NONE ? NONE : new LoopMarker(kind, false);
        }
    }

    /**
     * The loop marker one host carries, classified by the TYPE it resolves to — never by the
     * element name alone.
     *
     * <p>Why that matters, concretely: BPMN 2.0 declares the marker as a substitution group over an
     * abstract type ({@code Semantic.xsd:974} declares {@code loopCharacteristics} of the abstract
     * {@code tLoopCharacteristics} {@code :975}; {@code standardLoopCharacteristics} {@code :1409}
     * and {@code multiInstanceLoopCharacteristics} {@code :1039} are its concrete members). Because
     * the type is abstract, the head spelling is legal only WITH an {@code xsi:type} — and real
     * exporters write it exactly that way. A name-only matcher therefore saw a loop marker, heard
     * nothing, and let a {@code loopMaximum="3"} activity deploy as a once-run activity: the check
     * this class exists for, defeated by one attribute.
     *
     * <p>{@code xsi:type} is resolved through the NAMESPACE, never through the prefix text: the
     * prefix is looked up in the document ({@link Element#lookupNamespaceURI}) and only a type
     * declared in the BPMN namespace counts. Matching the string instead would both miss a document
     * that binds the BPMN namespace to a different prefix and invent a verdict about a
     * foreign-namespace type that merely happens to be spelled the same.
     */
    private static LoopMarker loopMarkerOf(Element host) {
        NodeList children = host.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !isBpmn((Element) child)) {
                continue;
            }
            Element childElement = (Element) child;
            String name = localName(childElement);
            switch (name) {
                case "standardLoopCharacteristics" -> {
                    return LoopMarker.of(LoopKind.STANDARD);
                }
                case "multiInstanceLoopCharacteristics" -> {
                    return LoopMarker.of(LoopKind.MULTI_INSTANCE);
                }
                case "loopCharacteristics" -> {
                    LoopKind kind = loopKindOfXsiType(childElement);
                    return kind == LoopKind.NONE
                        ? LoopMarker.NONE
                        : new LoopMarker(kind, true);
                }
                default -> {
                    // not a loop marker (a conditionalEventDefinition, an extension element, …)
                }
            }
        }
        return LoopMarker.NONE;
    }

    /**
     * The loop kind the head element's {@code xsi:type} names, or {@link LoopKind#NONE}.
     *
     * <p>An unresolvable or foreign type yields NONE rather than a guess: the document would fail
     * schema validation anyway, and a scanner that decided "that looks like a loop" would refuse a
     * model over a type it could not read — the mirror image of the defect above.
     */
    private static LoopKind loopKindOfXsiType(Element head) {
        String typeName = head.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
        if (typeName == null || typeName.isBlank()) {
            return LoopKind.NONE;
        }
        int colon = typeName.indexOf(':');
        String prefix = colon < 0 ? "" : typeName.substring(0, colon);
        String local = colon < 0 ? typeName : typeName.substring(colon + 1);
        if (local.isBlank()) {
            return LoopKind.NONE;
        }
        // an unprefixed QName in an attribute value resolves through the DEFAULT namespace (XML
        // Schema Part 1, §3.4.5) — resolving "" as "unknown" would miss a document that puts BPMN
        // in the default namespace, which is how hand-written models are often written
        if (!BPMN_NS.equals(head.lookupNamespaceURI(prefix.isEmpty() ? null : prefix))) {
            return LoopKind.NONE;
        }
        return switch (local) {
            case "tStandardLoopCharacteristics" -> LoopKind.STANDARD;
            case "tMultiInstanceLoopCharacteristics" -> LoopKind.MULTI_INSTANCE;
            default -> LoopKind.NONE;
        };
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
     * Flows of one {@code <process>} whose {@code sourceRef}/{@code targetRef} names no flow node
     * THAT PROCESS declares. Reported per FLOW (one id, both endpoints folded in) so the message
     * names the thing the modeller has to edit.
     *
     * <p>Resolution is against the DOCUMENT, per process, and deliberately NOT against the ids the
     * parser put into the executable model. Two reasons, both learned from a live run:
     * <ul>
     *   <li>the message must be TRUE — "matches no flow node of the process" cannot be said about a
     *       node the document plainly declares (see {@link #FLOW_NODE_NAMES} for the kinds this
     *       check cares about); a node the parser happens not to model is a DIFFERENT defect, and it
     *       has its own finding or its own WO — mislabelling it here hid the real cause;</li>
     *   <li>resolving against the parsed model produced FALSE POSITIVES on valid models: the JAXB
     *       process model binds {@code <startEvent>} only, so a model written with the equally legal
     *       {@code <messageStartEvent>} tag (what Zeebe/Camunda exports) loses those start events at
     *       parse time, and every flow out of them was reported as dangling. Refusing such a model
     *       with the sentence "matches no flow node" would be a lie — the author would go looking
     *       for a typo that is not there.</li>
     * </ul>
     * Per process rather than per document: a flow pointing into ANOTHER {@code <process>} of the
     * same resource is genuinely broken (each deployment holds one process), and that is exactly
     * what criterion 4 asks for.
     */
    private static List<String> unresolvedFlows(Document document) {
        List<String> flows = new ArrayList<>();
        for (Element process : localNameElements(document.getDocumentElement(), "process")) {
            collectFlows(process, flows, flowNodeIdsOf(process));
        }
        return flows;
    }

    /** Ids of the flow nodes one {@code <process>} declares, nested containers included. */
    private static Set<String> flowNodeIdsOf(Element process) {
        Set<String> ids = new LinkedHashSet<>();
        collectFlowNodeIds(process, ids);
        return ids;
    }

    private static void collectFlowNodeIds(Element element, Set<String> ids) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !isBpmn((Element) child)) {
                continue;
            }
            Element childElement = (Element) child;
            if (FLOW_NODE_NAMES.contains(localName(childElement))) {
                String id = childElement.getAttribute("id");
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
            // recurse: a node nested in a sub-process/transaction/adHoc body belongs to the SAME
            // process and is therefore a legal endpoint for a flow of that process
            collectFlowNodeIds(childElement, ids);
        }
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
        return localNameElements(root, name).stream().map(BpmnSupportScanner::label).toList();
    }

    /** The BPMN elements of one kind, in document order (the {@code <process>} elements, gateways). */
    private static List<Element> localNameElements(Element root, String name) {
        List<Element> found = new ArrayList<>();
        collectElements(root, found, name);
        return found;
    }

    private static void collectElements(Element element, List<Element> found, String name) {
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
                found.add(childElement);
            }
            collectElements(childElement, found, name);
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
        UnsupportedBpmnConstructCodes.UNSUPPORTED_MULTI_INSTANCE_XSI_TYPE,
        UnsupportedBpmnConstructCodes.UNSUPPORTED_CONDITIONAL_START_EVENT,
        UnsupportedBpmnConstructCodes.UNRESOLVED_SEQUENCE_FLOW_REF);

    /** Distinct, never reused: an API client keys its message on these. */
    public static final class UnsupportedBpmnConstructCodes {
        public static final String MULTIPLE_PROCESSES_IN_RESOURCE = "MULTIPLE_PROCESSES_IN_RESOURCE";
        public static final String UNSUPPORTED_COMPLEX_GATEWAY = "UNSUPPORTED_COMPLEX_GATEWAY";
        public static final String UNSUPPORTED_STANDARD_LOOP = "UNSUPPORTED_STANDARD_LOOP";
        public static final String UNSUPPORTED_MULTI_INSTANCE_CONTAINER = "UNSUPPORTED_MULTI_INSTANCE_CONTAINER";
        /**
         * WO-ENG-34 раунд 10: multi-instance written as {@code <loopCharacteristics xsi:type=…>}.
         * Separate from {@link #UNSUPPORTED_MULTI_INSTANCE_CONTAINER} so neither code's message can
         * ever be false about the case it is now shown for.
         */
        public static final String UNSUPPORTED_MULTI_INSTANCE_XSI_TYPE = "UNSUPPORTED_MULTI_INSTANCE_XSI_TYPE";
        public static final String UNSUPPORTED_CONDITIONAL_START_EVENT = "UNSUPPORTED_CONDITIONAL_START_EVENT";
        public static final String UNRESOLVED_SEQUENCE_FLOW_REF = "UNRESOLVED_SEQUENCE_FLOW_REF";

        private UnsupportedBpmnConstructCodes() {
        }
    }
}
