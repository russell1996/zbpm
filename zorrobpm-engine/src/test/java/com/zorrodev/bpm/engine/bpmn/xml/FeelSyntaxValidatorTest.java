package com.zorrodev.bpm.engine.bpmn.xml;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.InvalidFeelExpression;
import com.zorrodev.bpm.engine.bpmn.xml.FeelSyntaxValidator.FeelExpressionKind;
import com.zorrodev.bpm.engine.configuration.FeelBigDecimalNumberMapper;
import com.zorrodev.bpm.engine.service.impl.BpmnParseServiceImpl;
import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.FeelEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-56: the validator's own contract — unit level, NO Spring context, the real FEEL parser
 * (feel-engine, built exactly like {@code DmnEngineConfig}) and the real production parser
 * ({@code BpmnParseServiceImpl}) for the model walks. The deploy-path behaviour (400 + shape +
 * nothing persisted) is pinned separately by {@code FeelDeployValidationIntegrationTests}.
 *
 * <p>Every walker case below mirrors a RUNTIME evaluation point (the javadoc on
 * {@link FeelSyntaxValidator} lists them with file-level rationale) — the test's job is to prove
 * the walk reaches exactly the fields the engine would meet at runtime, with the strip policy
 * each runtime call actually uses.
 */
class FeelSyntaxValidatorTest {

    private static final String DEFS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n"
        + "                  xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\"\n"
        + "                  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
        + "                  targetNamespace=\"http://bpmn.io/schema/bpmn\">\n";

    private final BpmnParseServiceImpl parser = new BpmnParseServiceImpl();

    private final FeelSyntaxValidator validator = new FeelSyntaxValidator(
        FeelEngineBuilder.forJava().withCustomValueMapper(new FeelBigDecimalNumberMapper()).build());

    // ─── the four strip/grammar policies, in isolation ────────────────────

    @Test
    @DisplayName("FLOW_CONDITION: unconditional substring(1) + unary grammar, exactly like FlowNavigator")
    void flowConditionKind() {
        assertThat(validator.parseError(FeelExpressionKind.FLOW_CONDITION, "=x = \"a\"")
            .isEmpty());
        // the production defect: unary test with a single-quoted atom
        Optional<String> flowErr = validator.parseError(FeelExpressionKind.FLOW_CONDITION, "=x = 'a'");
        assertThat(flowErr).isPresent();
        assertThat(flowErr.get()).contains("'a'");
        // no '=': the runtime strips the first char ANYWAY (" = 1") — broken at runtime too,
        // so deploy refuses it as well (fail-closed mirror, not a new rule)
        assertThat(validator.parseError(FeelExpressionKind.FLOW_CONDITION, "x = 1")).isPresent();
        // 'true' without '=' → substring(1) = 'rue' — a bare variable: parses (runtime: null → false)
        assertThat(validator.parseError(FeelExpressionKind.FLOW_CONDITION, "true")).isEmpty();
    }

    @Test
    @DisplayName("UNARY_CONDITION: strip one leading '=' if present, then unary grammar")
    void unaryConditionKind() {
        assertThat(validator.parseError(FeelExpressionKind.UNARY_CONDITION, "=x = \"a\"")).isEmpty();
        assertThat(validator.parseError(FeelExpressionKind.UNARY_CONDITION, "x = \"a\"")).isEmpty();
        Optional<String> unaryErr = validator.parseError(FeelExpressionKind.UNARY_CONDITION, "=x = 'a'");
        assertThat(unaryErr).isPresent();
        assertThat(unaryErr.get()).contains("'a'");
    }

    @Test
    @DisplayName("FEEL_EXPRESSION: strip one leading '=' if present, then expression grammar")
    void feelExpressionKind() {
        assertThat(validator.parseError(FeelExpressionKind.FEEL_EXPRESSION, "=1 + 1")).isEmpty();
        assertThat(validator.parseError(FeelExpressionKind.FEEL_EXPRESSION, "1 + 1")).isEmpty();
        Optional<String> exprErr = validator.parseError(FeelExpressionKind.FEEL_EXPRESSION, "=x = 'a'");
        assertThat(exprErr).isPresent();
        assertThat(exprErr.get()).contains("'a'");
        // '=' alone → empty after strip → fails; the runtime would choke on it too
        assertThat(validator.parseError(FeelExpressionKind.FEEL_EXPRESSION, "=")).isPresent();
    }

    @Test
    @DisplayName("FEEL_EXPRESSION_PLAIN: NO strip — the model value is what the runtime evaluates")
    void feelExpressionPlainKind() {
        assertThat(validator.parseError(FeelExpressionKind.FEEL_EXPRESSION_PLAIN, "1 + 1")).isEmpty();
        // a leftover '=' (e.g. a script written as '=…' that the parser does not strip) fails raw
        assertThat(validator.parseError(FeelExpressionKind.FEEL_EXPRESSION_PLAIN, "=1 + 1")).isPresent();
        Optional<String> plainErr = validator.parseError(FeelExpressionKind.FEEL_EXPRESSION_PLAIN, "=x = 'a'");
        assertThat(plainErr).isPresent();
        assertThat(plainErr.get()).contains("'a'");
    }

    // ─── the model walk: one case per runtime field ───────────────────────

    private static List<String> elementFields(List<InvalidFeelExpression> findings) {
        return findings.stream().map(f -> f.elementId() + "/" + f.field()).toList();
    }

    @Test
    @DisplayName("sequence-flow condition: single-quoted → the FLOW is named; double-quoted → clean")
    void flowCondition() {
        BpmnProcessDefinitionModel broken = parser.parse(gatewayModel(
            "<bpmn:sequenceFlow id=\"flowCond\" sourceRef=\"gw\" targetRef=\"e1\">"
                + "<bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\">=x = 'a'</bpmn:conditionExpression>"
                + "</bpmn:sequenceFlow>"));
        List<InvalidFeelExpression> findings = validator.validate(broken);
        assertThat(elementFields(findings)).containsExactly("flowCond/condition");
        assertThat(findings.get(0).parseError()).contains("'a'");

        BpmnProcessDefinitionModel ok = parser.parse(gatewayModel(
            "<bpmn:sequenceFlow id=\"flowCond\" sourceRef=\"gw\" targetRef=\"e1\">"
                + "<bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\">=x = \"a\"</bpmn:conditionExpression>"
                + "</bpmn:sequenceFlow>"));
        assertThat(validator.validate(ok)).isEmpty();
    }

    @Test
    @DisplayName("conditional event condition: single-quoted → the EVENT is named (unary grammar)")
    void conditionalEventCondition() {
        // NB: the parser reads the <bpmn:condition> ELEMENT (BpmnConditionalEventDefinitionModel
        // @XmlElement), not the condition="…" attribute — the fixture must use the element form.
        BpmnProcessDefinitionModel model = parser.parse(process(
            "<bpmn:intermediateCatchEvent id=\"task\">"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "<bpmn:conditionalEventDefinition>"
                + "<bpmn:condition xsi:type=\"bpmn:tFormalExpression\">=x = 'a'</bpmn:condition>"
                + "</bpmn:conditionalEventDefinition>"
                + "</bpmn:intermediateCatchEvent>"));
        List<InvalidFeelExpression> findings = validator.validate(model);
        assertThat(elementFields(findings)).containsExactly("task/condition");
    }

    @Test
    @DisplayName("user task: assignee/candidateGroups/dueDate/followUpDate/priorityDefinition — FEEL only when '=…'")
    void userTaskAssignmentFields() {
        BpmnProcessDefinitionModel broken = parser.parse(process(
            "<bpmn:userTask id=\"task\">"
                + "<bpmn:extensionElements>"
                + "<zeebe:userTask/>"
                + "<zeebe:assignmentDefinition assignee=\"=u = 'a'\" candidateGroups=\"=g = 'b'\"/>"
                + "<zeebe:taskSchedule dueDate=\"=d = 'c'\" followUpDate=\"=f = 'd'\"/>"
                + "<zeebe:priorityDefinition priority=\"=p = 'e'\"/>"
                + "</bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:userTask>"));
        List<InvalidFeelExpression> findings = validator.validate(broken);
        assertThat(elementFields(findings)).containsExactly(
            "task/assignee", "task/candidateGroups", "task/dueDate",
            "task/followUpDate", "task/priorityDefinition");

        // the false-positive twin: LITERAL values (no '=') are not FEEL — the runtime resolves
        // them as ${var}/plain, never through the parser. A validator that parsed them anyway
        // would refuse every model with a static assignee.
        BpmnProcessDefinitionModel literals = parser.parse(process(
            "<bpmn:userTask id=\"task\">"
                + "<bpmn:extensionElements>"
                + "<zeebe:userTask/>"
                + "<zeebe:assignmentDefinition assignee=\"alice\" candidateGroups=\"=ops\"/>"
                + "<zeebe:taskSchedule dueDate=\"PT2H\"/>"
                + "</bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:userTask>"));
        assertThat(validator.validate(literals)).isEmpty();
    }

    @Test
    @DisplayName("service task jobPriorityDefinition: FEEL only when '=…'")
    void serviceTaskPriority() {
        BpmnProcessDefinitionModel broken = parser.parse(process(
            "<bpmn:serviceTask id=\"task\">"
                + "<bpmn:extensionElements>"
                + "<zeebe:taskDefinition type=\"someJob\"/>"
                + "<zeebe:jobPriorityDefinition priority=\"=p = 'e'\"/>"
                + "</bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:serviceTask>"));
        assertThat(elementFields(validator.validate(broken))).containsExactly("task/jobPriorityDefinition");
    }

    @Test
    @DisplayName("process defaultJobPriority: FEEL only when '=…', named by the process id")
    void defaultJobPriority() {
        BpmnProcessDefinitionModel model = parser.parse(
            DEFS
            + "  <bpmn:process id=\"p\" isExecutable=\"true\">"
            + "    <bpmn:extensionElements><zeebe:jobPriorityDefinition priority=\"=p = 'e'\"/></bpmn:extensionElements>"
            + "    <bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>"
            + "    <bpmn:endEvent id=\"end\"><bpmn:incoming>f1</bpmn:incoming></bpmn:endEvent>"
            + "    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"end\"/>"
            + "  </bpmn:process>"
            + "</bpmn:definitions>\n");
        List<InvalidFeelExpression> findings = validator.validate(model);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).elementId()).isEqualTo("p");
        assertThat(findings.get(0).field()).isEqualTo("defaultJobPriority");
    }

    @Test
    @DisplayName("ioMapping input AND output sources: FEEL when written as '=…'; bracket path is legal syntax")
    void ioMappingSources() {
        BpmnProcessDefinitionModel broken = parser.parse(process(
            "<bpmn:userTask id=\"task\">"
                + "<bpmn:extensionElements>"
                + "<zeebe:userTask/>"
                + "<zeebe:ioMapping>"
                + "<zeebe:input source=\"=a = 'x'\" target=\"t\"/>"
                + "<zeebe:output source=\"=b = 'y'\" target=\"t2\"/>"
                + "</zeebe:ioMapping>"
                + "</bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:userTask>"));
        List<InvalidFeelExpression> findings = validator.validate(broken);
        assertThat(elementFields(findings)).containsExactly(
            "task/ioMapping.input.source", "task/ioMapping.output.source");

        // documented alternative for a space-containing key — syntactically LEGAL FEEL:
        BpmnProcessDefinitionModel bracket = parser.parse(process(
            "<bpmn:userTask id=\"task\">"
                + "<bpmn:extensionElements>"
                + "<zeebe:userTask/>"
                + "<zeebe:ioMapping><zeebe:output source=\"=a[&quot;key with space&quot;]\" target=\"t\"/></zeebe:ioMapping>"
                + "</bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:userTask>"));
        assertThat(validator.validate(bracket))
            .as("a[\"key with space\"] parses — Zeebe 8.5 deploys it too (matrix path-quoted); "
                + "the divergence is at runtime, not at deploy")
            .isEmpty();
    }

    /**
     * WO-C8-55 (root 3): an INPUT source without a leading {@code =} is a STATIC VALUE, not a
     * FEEL expression — live Zeebe 8.5 deploys {@code hello} and {@code {"a":1}} and delivers
     * them verbatim (measured live, {@code docs/analysis/c8-oracle/feel-iomapping-static-nested.js}).
     * The validator must therefore NOT parse them: the second one is not even parseable FEEL,
     * and refusing it would eat a model live 8.5 accepts.
     */
    @Test
    @DisplayName("ioMapping input source without '=…' is a static value — never parsed as FEEL")
    void ioMappingInputSourceIsStaticValue() {
        BpmnProcessDefinitionModel model = parser.parse(process(
            "<bpmn:userTask id=\"task\">"
                + "<bpmn:extensionElements>"
                + "<zeebe:userTask/>"
                + "<zeebe:ioMapping>"
                + "<zeebe:input source=\"hello\" target=\"t\"/>"
                + "<zeebe:input source=\"{&quot;a&quot;:1}\" target=\"t2\"/>"
                + "<zeebe:input source=\"  spaced  \" target=\"t3\"/>"
                + "</zeebe:ioMapping>"
                + "</bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:userTask>"));
        assertThat(validator.validate(model))
            .as("a static input value is not FEEL — live 8.5 deploys it")
            .isEmpty();
    }

    /**
     * WO-C8-55 (root 3, output side): an OUTPUT source is an EXPRESSION — live Zeebe 8.5 refuses
     * the model at deploy («Expected expression but found static value 'hello'. An expression must
     * start with '=' (e.g. '=hello')»). The engine must refuse it too, naming the element and
     * the field.
     */
    @Test
    @DisplayName("ioMapping output source without '=…' is refused — an output source is an expression")
    void ioMappingOutputSourceMustBeAnExpression() {
        BpmnProcessDefinitionModel model = parser.parse(process(
            "<bpmn:userTask id=\"task\">"
                + "<bpmn:extensionElements>"
                + "<zeebe:userTask/>"
                + "<zeebe:ioMapping>"
                + "<zeebe:output source=\"hello\" target=\"t\"/>"
                + "</zeebe:ioMapping>"
                + "</bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:userTask>"));
        List<InvalidFeelExpression> findings = validator.validate(model);
        assertThat(elementFields(findings)).containsExactly("task/ioMapping.output.source");
        assertThat(findings.get(0).parseError())
            .contains("Expected expression but found static value 'hello'")
            .contains("An expression must start with '='");
    }

    @Test
    @DisplayName("multi-instance: inputCollection/outputElement raw (parser-stripped); cardinality only without a collection")
    void multiInstance() {
        // broken collection + output element; cardinality 2 is fine
        BpmnProcessDefinitionModel broken = parser.parse(process(
            "<bpmn:userTask id=\"task\">"
                + "<bpmn:extensionElements><zeebe:userTask/></bpmn:extensionElements>"
                + "<bpmn:multiInstanceLoopCharacteristics isSequential=\"false\">"
                + "<bpmn:loopCardinality>2</bpmn:loopCardinality>"
                + "<bpmn:extensionElements>"
                + "<zeebe:loopCharacteristics inputCollection=\"=coll = 'x'\" inputElement=\"item\" outputElement=\"=out = 'y'\"/>"
                + "</bpmn:extensionElements>"
                + "</bpmn:multiInstanceLoopCharacteristics>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:userTask>"));
        List<InvalidFeelExpression> findings = validator.validate(broken);
        assertThat(elementFields(findings)).containsExactly(
            "task/multiInstance.inputCollection", "task/multiInstance.outputElement");

        // broken cardinality WITHOUT a collection → the cardinality IS the FEEL → refused
        BpmnProcessDefinitionModel brokenCard = parser.parse(process(
            "<bpmn:userTask id=\"task\">"
                + "<bpmn:extensionElements><zeebe:userTask/></bpmn:extensionElements>"
                + "<bpmn:multiInstanceLoopCharacteristics isSequential=\"false\">"
                + "<bpmn:loopCardinality>=n = 'x'</bpmn:loopCardinality>"
                + "</bpmn:multiInstanceLoopCharacteristics>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:userTask>"));
        assertThat(elementFields(validator.validate(brokenCard)))
            .containsExactly("task/multiInstance.loopCardinality");

        // broken cardinality NEXT TO a good collection → the collection path wins at runtime
        // (MultiInstanceExecutor), the cardinality never evaluates → NOT refused
        BpmnProcessDefinitionModel cardWithCollection = parser.parse(process(
            "<bpmn:userTask id=\"task\">"
                + "<bpmn:extensionElements><zeebe:userTask/></bpmn:extensionElements>"
                + "<bpmn:multiInstanceLoopCharacteristics isSequential=\"false\">"
                + "<bpmn:loopCardinality>=n = 'x'</bpmn:loopCardinality>"
                + "<bpmn:extensionElements>"
                + "<zeebe:loopCharacteristics inputCollection=\"=items\" inputElement=\"item\"/>"
                + "</bpmn:extensionElements>"
                + "</bpmn:multiInstanceLoopCharacteristics>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:userTask>"));
        assertThat(validator.validate(cardWithCollection))
            .as("cardinality is dead FEEL while inputCollection exists — refusing it would eat a working model")
            .noneMatch(f -> f.field().equals("multiInstance.loopCardinality"));
    }

    @Test
    @DisplayName("multi-instance completionCondition: '=…'+atom refused (the parser does NOT strip MI CC)")
    void miCompletionCondition() {
        BpmnProcessDefinitionModel model = parser.parse(process(
            "<bpmn:userTask id=\"task\">"
                + "<bpmn:extensionElements><zeebe:userTask/></bpmn:extensionElements>"
                + "<bpmn:multiInstanceLoopCharacteristics isSequential=\"false\">"
                + "<bpmn:loopCardinality>2</bpmn:loopCardinality>"
                + "<bpmn:completionCondition>=done = 'x'</bpmn:completionCondition>"
                + "</bpmn:multiInstanceLoopCharacteristics>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:userTask>"));
        assertThat(elementFields(validator.validate(model)))
            .containsExactly("task/multiInstance.completionCondition");
    }

    @Test
    @DisplayName("ad-hoc: the three parser-stripped expression fields, raw at runtime")
    void adHocFields() {
        BpmnProcessDefinitionModel model = parser.parse(process(
            "<bpmn:adHocSubProcess id=\"task\" cancelRemainingInstances=\"false\">"
                + "<bpmn:extensionElements>"
                + "<zeebe:adHoc activeElementsCollection=\"=[&quot;a&quot;]\" outputElement=\"=out = 'y'\"/>"
                + "</bpmn:extensionElements>"
                + "<bpmn:completionCondition>=done = 'z'</bpmn:completionCondition>"
                + "<bpmn:userTask id=\"inner\"/>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:adHocSubProcess>"));
        List<InvalidFeelExpression> findings = validator.validate(model);
        assertThat(elementFields(findings)).containsExactlyInAnyOrder(
            "task/adHoc.outputElement", "task/adHoc.completionCondition");
        // the collection ["a"] is legal; only the two '…' atoms fail
        assertThat(findings).noneMatch(f -> f.field().equals("adHoc.activeElementsCollection"));
    }

    @Test
    @DisplayName("script task: zeebe:script (parser-stripped) and inline <script> (not) — both raw at runtime")
    void scriptTask() {
        BpmnProcessDefinitionModel zeebeStyle = parser.parse(process(
            "<bpmn:scriptTask id=\"task\">"
                + "<bpmn:extensionElements><zeebe:script expression=\"=a = 'x'\" resultVariable=\"r\"/></bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:scriptTask>"));
        // the parser strips the '=' → the model holds "a = 'x'" → raw parse fails
        assertThat(elementFields(validator.validate(zeebeStyle))).containsExactly("task/script");

        BpmnProcessDefinitionModel inline = parser.parse(process(
            "<bpmn:scriptTask id=\"task\">"
                + "<bpmn:script>a = 'x'</bpmn:script>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:scriptTask>"));
        assertThat(elementFields(validator.validate(inline))).containsExactly("task/script");
    }

    @Test
    @DisplayName("timer: FEEL only when '=…' — ISO durations are not FEEL")
    void timer() {
        BpmnProcessDefinitionModel feel = parser.parse(process(
            "<bpmn:intermediateCatchEvent id=\"task\">"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "<bpmn:timerEventDefinition><bpmn:timeDuration>=d = 'x'</bpmn:timeDuration></bpmn:timerEventDefinition>"
                + "</bpmn:intermediateCatchEvent>"));
        assertThat(elementFields(validator.validate(feel))).containsExactly("task/timer");

        BpmnProcessDefinitionModel iso = parser.parse(process(
            "<bpmn:intermediateCatchEvent id=\"task\">"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "<bpmn:timerEventDefinition><bpmn:timeDuration>PT5M</bpmn:timeDuration></bpmn:timerEventDefinition>"
                + "</bpmn:intermediateCatchEvent>"));
        assertThat(validator.validate(iso))
            .as("PT5M is an ISO duration, not FEEL — refusing it would eat the whole timer fleet")
            .isEmpty();
    }

    @Test
    @DisplayName("business rule: expression only without a decision; decisionId as FEEL expression is checked")
    void businessRule() {
        BpmnProcessDefinitionModel expr = parser.parse(process(
            "<bpmn:businessRuleTask id=\"task\">"
                + "<bpmn:extensionElements><zeebe:script expression=\"=a = 'x'\" resultVariable=\"r\"/></bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:businessRuleTask>"));
        assertThat(elementFields(validator.validate(expr))).containsExactly("task/expression");

        // a decision wins: the parser keeps decisionId only (the expression is dropped at parse),
        // so a broken expression next to a decision cannot even reach the validator
        BpmnProcessDefinitionModel decision = parser.parse(process(
            "<bpmn:businessRuleTask id=\"task\">"
                + "<bpmn:extensionElements>"
                + "<zeebe:calledDecision decisionId=\"dec\" resultVariable=\"r\"/>"
                + "<zeebe:script expression=\"=a = 'x'\" resultVariable=\"r\"/>"
                + "</bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:businessRuleTask>"));
        assertThat(validator.validate(decision)).isEmpty();

        BpmnProcessDefinitionModel feelDecisionId = parser.parse(process(
            "<bpmn:businessRuleTask id=\"task\">"
                + "<bpmn:extensionElements><zeebe:calledDecision decisionId=\"=dec = 'x'\" resultVariable=\"r\"/></bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:businessRuleTask>"));
        assertThat(elementFields(validator.validate(feelDecisionId))).containsExactly("task/decisionId");
    }

    @Test
    @DisplayName("call activity: processId as FEEL expression is checked; a plain id is not FEEL")
    void callActivity() {
        BpmnProcessDefinitionModel feel = parser.parse(process(
            "<bpmn:callActivity id=\"task\">"
                + "<bpmn:extensionElements><zeebe:calledElement processId=\"=pid = 'x'\"/></bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:callActivity>"));
        assertThat(elementFields(validator.validate(feel))).containsExactly("task/processId");

        BpmnProcessDefinitionModel plain = parser.parse(process(
            "<bpmn:callActivity id=\"task\">"
                + "<bpmn:extensionElements><zeebe:calledElement processId=\"child\"/></bpmn:extensionElements>"
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + "</bpmn:callActivity>"));
        assertThat(validator.validate(plain)).isEmpty();
    }

    @Test
    @DisplayName("message correlationKey (zeebe:subscription on the <message>): FEEL when present, named on the event")
    void correlationKey() {
        BpmnProcessDefinitionModel broken = parser.parse(
            DEFS
            + "  <bpmn:message id=\"m\" name=\"msg\">"
            + "    <bpmn:extensionElements><zeebe:subscription correlationKey=\"=k = 'x'\"/></bpmn:extensionElements>"
            + "  </bpmn:message>"
            + "  <bpmn:process id=\"p\" isExecutable=\"true\">"
            + "    <bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>"
            + "    <bpmn:intermediateCatchEvent id=\"task\">"
            + "      <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
            + "      <bpmn:messageEventDefinition messageRef=\"m\"/>"
            + "    </bpmn:intermediateCatchEvent>"
            + "    <bpmn:endEvent id=\"end\"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>"
            + "    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"task\"/>"
            + "    <bpmn:sequenceFlow id=\"f2\" sourceRef=\"task\" targetRef=\"end\"/>"
            + "  </bpmn:process>"
            + "</bpmn:definitions>\n");
        List<InvalidFeelExpression> findings = validator.validate(broken);
        assertThat(elementFields(findings)).containsExactly("task/correlationKey");

        BpmnProcessDefinitionModel noKey = parser.parse(
            DEFS
            + "  <bpmn:message id=\"m\" name=\"msg\"/>"
            + "  <bpmn:process id=\"p\" isExecutable=\"true\">"
            + "    <bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>"
            + "    <bpmn:intermediateCatchEvent id=\"task\">"
            + "      <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
            + "      <bpmn:messageEventDefinition messageRef=\"m\"/>"
            + "    </bpmn:intermediateCatchEvent>"
            + "    <bpmn:endEvent id=\"end\"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>"
            + "    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"task\"/>"
            + "    <bpmn:sequenceFlow id=\"f2\" sourceRef=\"task\" targetRef=\"end\"/>"
            + "  </bpmn:process>"
            + "</bpmn:definitions>\n");
        assertThat(validator.validate(noKey)).isEmpty();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    /** Standard start → task → end skeleton; {@code body} is the element under test. */
    private static String process(String body) {
        return DEFS
            + "  <bpmn:process id=\"p\" isExecutable=\"true\">\n"
            + "    <bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>\n"
            + body + "\n"
            + "    <bpmn:endEvent id=\"end\"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>\n"
            + "    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"task\"/>\n"
            + "    <bpmn:sequenceFlow id=\"f2\" sourceRef=\"task\" targetRef=\"end\"/>\n"
            + "  </bpmn:process>\n"
            + "</bpmn:definitions>\n";
    }

    private static String gatewayModel(String conditionalFlow) {
        return DEFS
            + "  <bpmn:process id=\"p\" isExecutable=\"true\">"
            + "    <bpmn:startEvent id=\"start\"><bpmn:outgoing>f0</bpmn:outgoing></bpmn:startEvent>"
            + "    <bpmn:exclusiveGateway id=\"gw\">"
            + "      <bpmn:incoming>f0</bpmn:incoming>"
            + "      <bpmn:outgoing>flowCond</bpmn:outgoing>"
            + "      <bpmn:outgoing>fOther</bpmn:outgoing>"
            + "    </bpmn:exclusiveGateway>"
            + "    <bpmn:endEvent id=\"e1\"><bpmn:incoming>flowCond</bpmn:incoming></bpmn:endEvent>"
            + "    <bpmn:endEvent id=\"e2\"><bpmn:incoming>fOther</bpmn:incoming></bpmn:endEvent>"
            + "    <bpmn:sequenceFlow id=\"f0\" sourceRef=\"start\" targetRef=\"gw\"/>"
            + conditionalFlow
            + "    <bpmn:sequenceFlow id=\"fOther\" sourceRef=\"gw\" targetRef=\"e2\"/>"
            + "  </bpmn:process>"
            + "</bpmn:definitions>\n";
    }
}
