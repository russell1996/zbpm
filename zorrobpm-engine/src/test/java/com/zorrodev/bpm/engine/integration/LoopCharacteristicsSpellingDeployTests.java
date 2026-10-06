package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WO-ENG-34, CTO HOLD раунда 9 (Б-1): the refusal matched the CHILD ELEMENT NAME only, so the
 * schema-legal spelling of a loop marker walked straight past all three loop checks.
 *
 * <p>BPMN 2.0 declares the loop marker as a substitution group: {@code tLoopCharacteristics} is
 * {@code abstract="true"} and the two concrete types
 * ({@code standardLoopCharacteristics}, {@code multiInstanceLoopCharacteristics}) are members
 * ({@code Semantic.xsd:974} head element, {@code :1409} / {@code :1039} members, and {@code tActivity}
 * has {@code <xsd:element ref="loopCharacteristics" minOccurs="0"/>}). A document may therefore write
 * the marker either way, and because the head type is abstract the head spelling is only legal WITH
 * {@code xsi:type} — which is exactly how real exporters write it. The scanner looked at element
 * names, so the head spelling produced NO finding: a model with {@code loopMaximum="3"} deployed,
 * the definition went ACTIVE, and the activity ran exactly ONCE. That is the defect this WO exists
 * to close (CR-08), so a check that one attribute defeats is not a fix.
 *
 * <p>The red-team proof of the bypass (independent pass 1) covered 22 combinations —
 * 11 activity kinds × {standard loop, multi-instance} — all accepted. Both spellings of all 22 are
 * pinned here, through the production deploy path
 * ({@code ProcessDefinitionService.addProcessDefinition} — what REST deploy, batch deploy and
 * submission approval all call).
 *
 * <p>XSD evidence for the claim "the head spelling is legal, not exotic" (official BPMN 2.0 schema,
 * {@code bpmn-moddle} {@code resources/bpmn/xsd/BPMN20.xsd}, validated with
 * {@code javax.xml.validation}): a document with
 * {@code <bpmn:loopCharacteristics xsi:type="bpmn:tStandardLoopCharacteristics">} on a
 * {@code <bpmn:transaction>} is {@code VALID}. No fixture in this repository used that spelling
 * before ({@code grep -rn "xsi:type" --include=*.bpmn} → 0 hits), which is why no test caught it.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
class LoopCharacteristicsSpellingDeployTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private BpmnParseService bpmnParseService;

    /**
     * Every element kind that carries {@code <xsd:element ref="loopCharacteristics">} (i.e. every
     * concrete {@code tActivity} kind that a modeller can hang a loop on), plus
     * {@code adHocSubProcess} — the fourth member of the refused-host set. The first eleven are the
     * combination set the independent red-team proved bypassed (22 = 11 × 2).
     */
    private static final List<String> HOST_KINDS = List.of(
        "task", "scriptTask", "serviceTask", "userTask", "manualTask", "businessRuleTask",
        "sendTask", "receiveTask", "subProcess", "transaction", "callActivity", "adHocSubProcess");

    /** The kind of activity a multi-instance marker is refused on (see MULTI_INSTANCE_UNSUPPORTED_HOSTS). */
    private static final List<String> CONTAINER_KINDS =
        List.of("subProcess", "transaction", "callActivity", "adHocSubProcess");

    // ─── the bypass: head element + xsi:type, standard loop (criterion 1) ─────────────

    @Test
    void criterion1_standardLoopWrittenAsAbstractHeadElement_rejectedOnEveryHostKind() {
        for (String host : HOST_KINDS) {
            ApiException refusal = catchThrowableOfDeploy(bpmnWith(host, headElementStandardLoop()));

            assertThat(refusal)
                .as("%s + <loopCharacteristics xsi:type=tStandardLoopCharacteristics> must be refused"
                    , host)
                .isNotNull();
            assertThat(refusal.getStatus().value()).as("%s status", host).isEqualTo(400);
            assertThat(refusal.getCode()).as("%s code", host).isEqualTo("UNSUPPORTED_STANDARD_LOOP");
            assertThat(refusal.getParams().get("elementIds")).as("%s elementIds", host)
                .asList().containsExactly("loopTask");
        }
    }

    // ─── the bypass: head element + xsi:type, multi-instance ─────────────────────────────

    /**
     * The same bypass for multi-instance, and it is worse than a container: on
     * {@code serviceTask}/{@code userTask} — the two kinds that DO implement MI — the direct
     * spelling binds the MI extension and this one does not, so the marker is dropped and the
     * activity runs once (proved in this file's javadoc and in the report). Refused on every host.
     *
     * <p>The code differs by host on purpose. On a refused container the truthful reason is the
     * container rule, so that is what the author is told; everywhere else the marker is refused
     * because it is UNREADABLE, and the code says exactly that instead of blaming the host kind.
     */
    @Test
    void multiInstanceWrittenAsAbstractHeadElement_rejectedOnEveryHostKind() {
        for (String host : HOST_KINDS) {
            ApiException refusal = catchThrowableOfDeploy(bpmnWith(host, headElementMultiInstance()));
            String expected = CONTAINER_KINDS.contains(host)
                ? "UNSUPPORTED_MULTI_INSTANCE_CONTAINER"
                : "UNSUPPORTED_MULTI_INSTANCE_XSI_TYPE";

            assertThat(refusal)
                .as("%s + <loopCharacteristics xsi:type=tMultiInstanceLoopCharacteristics> must be refused"
                    , host)
                .isNotNull();
            assertThat(refusal.getCode()).as("%s code", host).isEqualTo(expected);
            assertThat(refusal.getParams().get("elementIds")).as("%s elementIds", host)
                .asList().containsExactly("loopTask");
        }
    }

    // ─── the supported spelling keeps working (the fix must not be a regression) ───────────

    @Test
    void criterion1_standardLoopWrittenDirectly_rejectedOnEveryHostKind() {
        for (String host : HOST_KINDS) {
            ApiException refusal = catchThrowableOfDeploy(bpmnWith(host, directStandardLoop()));

            assertThat(refusal).as("%s + <standardLoopCharacteristics>", host).isNotNull();
            assertThat(refusal.getCode()).as("%s code", host).isEqualTo("UNSUPPORTED_STANDARD_LOOP");
            assertThat(refusal.getParams().get("elementIds")).as("%s elementIds", host)
                .asList().containsExactly("loopTask");
        }
    }

    /**
     * Multi-instance written directly: refused on the container kinds (unchanged), and on the
     * activity kinds it keeps today's behaviour — an unrefused deploy — because widening that list
     * is CTO's scope decision, not this fix's (report §6). Pinned here so the day someone widens
     * it, this test says so instead of the change arriving unnoticed.
     */
    @Test
    void multiInstanceWrittenDirectly_refusedOnContainersAndAcceptedOnActivityKinds() {
        for (String host : CONTAINER_KINDS) {
            ApiException refusal = catchThrowableOfDeploy(bpmnWith(host, directMultiInstance()));

            assertThat(refusal).as("%s is a refused MI host", host).isNotNull();
            assertThat(refusal.getCode()).as("%s code", host)
                .isEqualTo("UNSUPPORTED_MULTI_INSTANCE_CONTAINER");
        }

        for (String host : HOST_KINDS) {
            if (CONTAINER_KINDS.contains(host)) {
                continue;
            }
            String key = "eng34-mi-direct-" + UUID.randomUUID().toString().substring(0, 8);
            ProcessDefinition deployed = processDefinitionService.addProcessDefinition(
                bpmnWith(host, directMultiInstance(), key));

            assertThat(deployed).as("%s keeps today's behaviour: direct MI is not refused here", host)
                .isNotNull();
        }
    }

    // ─── xsi:type is resolved through the NAMESPACE, not by the prefix text ────────────────

    @Test
    void xsiTypeIsResolvedThroughTheNamespace_notThePrefixText() {
        // (a) the very same document the red-team used, but the BPMN namespace is additionally bound
        //     to a second prefix and xsi:type names THAT prefix. A scanner comparing the type name as
        //     a string would call this "some other type" and let the model through.
        String twoPrefixes = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:zz="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xi="http://www.w3.org/2001/XMLSchema-instance"
                              id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="p" name="p" isExecutable="true">
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="loopTask" />
                <bpmn:task id="loopTask" name="loopTask">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  <bpmn:loopCharacteristics xi:type="zz:tStandardLoopCharacteristics" loopMaximum="3" />
                </bpmn:task>
                <bpmn:sequenceFlow id="f2" sourceRef="loopTask" targetRef="e" />
                <bpmn:endEvent id="e"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;
        ApiException refusal = catchThrowableOfDeploy(twoPrefixes);
        assertThat(refusal).as("xsi:type whose prefix binds the BPMN namespace is a BPMN type")
            .isNotNull();
        assertThat(refusal.getCode()).isEqualTo("UNSUPPORTED_STANDARD_LOOP");

        // (b) no prefix at all: an unprefixed QName resolves through the DEFAULT namespace, which
        //     here is the BPMN one. Resolving "empty prefix" as "unknown" would miss this.
        String defaultNamespace = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <process id="p" name="p" isExecutable="true">
                <startEvent id="s"><outgoing>f1</outgoing></startEvent>
                <sequenceFlow id="f1" sourceRef="s" targetRef="loopTask" />
                <task id="loopTask" name="loopTask">
                  <incoming>f1</incoming>
                  <outgoing>f2</outgoing>
                  <loopCharacteristics xsi:type="tStandardLoopCharacteristics" loopMaximum="3" />
                </task>
                <sequenceFlow id="f2" sourceRef="loopTask" targetRef="e" />
                <endEvent id="e"><incoming>f2</incoming></endEvent>
              </process>
            </definitions>
            """;
        ApiException viaDefault = catchThrowableOfDeploy(defaultNamespace);
        assertThat(viaDefault).as("an unprefixed xsi:type resolves through the default namespace")
            .isNotNull();
        assertThat(viaDefault.getCode()).isEqualTo("UNSUPPORTED_STANDARD_LOOP");

        // (c) the negative control, and the reason the resolution goes through the namespace: a type
        //     name that LOOKS like ours but is declared in a foreign namespace is NOT a BPMN loop.
        //     The document is invalid against the schema (the type must derive from
        //     tLoopCharacteristics) and the scanner must not invent a verdict about it — it must
        //     resolve, and find nothing to resolve.
        String foreignType = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xmlns:foo="urn:not-bpmn"
                              id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="p" name="p" isExecutable="true">
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="loopTask" />
                <bpmn:task id="loopTask" name="loopTask">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  <bpmn:loopCharacteristics xsi:type="foo:tStandardLoopCharacteristics" loopMaximum="3" />
                </bpmn:task>
                <bpmn:sequenceFlow id="f2" sourceRef="loopTask" targetRef="e" />
                <bpmn:endEvent id="e"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;
        assertThat(catchThrowableOfDeploy(foreignType))
            .as("a foreign-namespace type is not a BPMN loop construct — the scanner resolves the "
                + "namespace and reports nothing rather than guessing")
            .isNull();
    }

    // ─── negative controls: no loop marker ⇒ no finding, on every host kind ────────────────

    @Test
    void hostsWithoutALoopMarker_reportNoFindings() {
        for (String host : HOST_KINDS) {
            BpmnProcessDefinitionModel model = bpmnParseService.parse(bpmnWith(host, ""));

            assertThat(model.getUnsupportedConstructs())
                .as("%s without a loop marker must not produce a finding", host)
                .isEmpty();
        }
    }

    /**
     * A head element whose type cannot be resolved to a BPMN loop type must produce NO finding —
     * a deliberate decision, pinned so that a later change to it is a conscious one.
     *
     * <p>The principle is the same one that keeps a foreign-namespace type out of the verdict
     * (see {@link #xsiTypeIsResolvedThroughTheNamespace_notThePrefixText}): resolve the type, and
     * if there is no BPMN loop type behind it, there is no loop construct the scanner can name.
     * Guessing here would refuse a model over a type the scanner cannot read.
     *
     * <p>The three forms are all XSD-invalid — {@code tLoopCharacteristics} is abstract, so the head
     * spelling is legal only WITH a type that derives from it — and this engine does not validate
     * deploys against the BPMN XSD at all (a pre-existing property of the deploy path, recorded in
     * the report as a hardening follow-up). They are pinned because the answer must stay
     * "undeclared loop, nothing to refuse" rather than drift into either silent acceptance of a
     * declared loop or a verdict invented from an unresolvable name.
     */
    @Test
    void headElementWithAnUnresolvableType_reportsNoFindings() {
        String noType = "<bpmn:loopCharacteristics loopMaximum=\"3\" />";
        String abstractSelfReference =
            "<bpmn:loopCharacteristics xsi:type=\"bpmn:tLoopCharacteristics\" loopMaximum=\"3\" />";
        String undeclaredPrefix =
            "<bpmn:loopCharacteristics xsi:type=\"nope:tStandardLoopCharacteristics\" loopMaximum=\"3\" />";

        for (String marker : List.of(noType, abstractSelfReference, undeclaredPrefix)) {
            BpmnProcessDefinitionModel model = bpmnParseService.parse(bpmnWith("scriptTask", marker));

            assertThat(model.getUnsupportedConstructs())
                .as("%s declares no BPMN loop type, so there is no construct to refuse", marker)
                .isEmpty();
        }
    }

    /**
     * One host, both loop kinds, written directly — the two codes must stay distinct, and the
     * code chosen must be the one whose message is TRUE for that host. Folding them into one code
     * would tell a service-task author to move the marker inside a container, which is nonsense
     * there.
     */
    @Test
    void theTwoMultiInstanceRefusals_carryDifferentCodes() {
        ApiException onContainer = catchThrowableOfDeploy(bpmnWith("subProcess", headElementMultiInstance()));
        ApiException onTask = catchThrowableOfDeploy(bpmnWith("userTask", headElementMultiInstance()));

        assertThat(onContainer).isNotNull();
        assertThat(onTask).isNotNull();
        assertThat(onContainer.getCode()).isEqualTo("UNSUPPORTED_MULTI_INSTANCE_CONTAINER");
        assertThat(onTask.getCode()).isEqualTo("UNSUPPORTED_MULTI_INSTANCE_XSI_TYPE");
        assertThat(onContainer.getMessage())
            .as("the container message keeps naming the container problem")
            .contains("Move the multi-instance marker onto a task inside the container");
        assertThat(onTask.getMessage())
            .as("the unreadable-spelling message must not blame the container")
            .doesNotContain("inside the container")
            .contains("Write the concrete element");
    }

    /**
     * The other loop-shaped element in real documents: Zeebe's
     * {@code <zeebe:loopCharacteristics>} inside {@code <extensionElements>}. It is a vendor
     * extension in another namespace, it IS supported, and a namespace-blind matcher would refuse
     * every multi-instance model exported from Camunda 8.
     */
    @Test
    void zeebeLoopCharacteristicsExtension_staysSupported() {
        BpmnProcessDefinitionModel model = bpmnParseService.parse("""
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                              id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="p" name="p" isExecutable="true">
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="loopTask" />
                <bpmn:serviceTask id="loopTask" name="loopTask">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="j" retries="3" />
                    <zeebe:loopCharacteristics inputCollection="=items" inputElement="=item" />
                  </bpmn:extensionElements>
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f2" sourceRef="loopTask" targetRef="e" />
                <bpmn:endEvent id="e"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """);

        assertThat(model.getUnsupportedConstructs())
            .as("the Camunda 8 way of configuring multi-instance is supported and must stay so")
            .isEmpty();
    }

    // ─── helpers ───────────────────────────────────────────────────────────────────────────

    private ApiException catchThrowableOfDeploy(String bpmn) {
        return (ApiException) catchThrowable(() -> processDefinitionService.addProcessDefinition(bpmn));
    }

    /** The four spellings under test, all schema-legal ways to hang a loop marker on a host. */
    private static String headElementStandardLoop() {
        return "<bpmn:loopCharacteristics xsi:type=\"bpmn:tStandardLoopCharacteristics\" "
            + "testBefore=\"false\" loopMaximum=\"3\">"
            + "<bpmn:loopCondition>x &lt; 3</bpmn:loopCondition>"
            + "</bpmn:loopCharacteristics>";
    }

    private static String headElementMultiInstance() {
        return "<bpmn:loopCharacteristics xsi:type=\"bpmn:tMultiInstanceLoopCharacteristics\" "
            + "isSequential=\"false\">"
            + "<bpmn:loopCardinality>3</bpmn:loopCardinality>"
            + "</bpmn:loopCharacteristics>";
    }

    private static String directStandardLoop() {
        return "<bpmn:standardLoopCharacteristics testBefore=\"false\" loopMaximum=\"3\">"
            + "<bpmn:loopCondition>x &lt; 3</bpmn:loopCondition>"
            + "</bpmn:standardLoopCharacteristics>";
    }

    private static String directMultiInstance() {
        return "<bpmn:multiInstanceLoopCharacteristics isSequential=\"false\">"
            + "<bpmn:loopCardinality>3</bpmn:loopCardinality>"
            + "</bpmn:multiInstanceLoopCharacteristics>";
    }

    /**
     * A minimal model around one activity of kind {@code host}, carrying {@code loopChild} as its
     * only loop marker. Element order follows the schema sequence (incoming, outgoing, then the
     * activity's own content, {@code script} last), so the generated documents are valid against
     * the official BPMN 2.0 XSD and not merely parseable.
     */
    private static String bpmnWith(String host, String loopChild) {
        return bpmnWith(host, loopChild, "eng34-loop-spelling-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static String bpmnWith(String host, String loopChild, String key) {
        String extension = "serviceTask".equals(host)
            ? "      <bpmn:extensionElements>"
                + "<zeebe:taskDefinition type=\"mi-service-job\" retries=\"3\" />"
                + "</bpmn:extensionElements>\n"
            : "";
        String script = "scriptTask".equals(host) ? "      <bpmn:script>1</bpmn:script>\n" : "";
        String attributes = "scriptTask".equals(host) ? " scriptFormat=\"feel\"" : "";

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                              id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="KEY" name="KEY" isExecutable="true">
                <bpmn:startEvent id="startEvent" name="startEvent">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" name="f1" sourceRef="startEvent" targetRef="loopTask" />
                <bpmn:HOST id="loopTask" name="loopTask"ATTRS>
            EXT      <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  LOOP
            SCRIPT    </bpmn:HOST>
                <bpmn:sequenceFlow id="f2" name="f2" sourceRef="loopTask" targetRef="endEvent" />
                <bpmn:endEvent id="endEvent" name="endEvent">
                  <bpmn:incoming>f2</bpmn:incoming>
                </bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """
            .replace("KEY", key)
            .replace("HOST", host)
            .replace("ATTRS", attributes)
            .replace("EXT", extension)
            .replace("LOOP", loopChild.isEmpty() ? "" : "      " + loopChild + "\n")
            .replace("SCRIPT", script);
    }
}
