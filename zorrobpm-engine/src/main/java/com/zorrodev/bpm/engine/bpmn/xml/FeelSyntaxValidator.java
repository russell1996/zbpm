package com.zorrodev.bpm.engine.bpmn.xml;

import com.zorrodev.bpm.engine.bpmn.model.AdHocSubProcessExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnConditionExpressionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BusinessRuleExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.CallActivityExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionType;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.InvalidFeelExpression;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ScriptTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.ParseResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * WO-C8-56: deploy-time FEEL syntax validation — the same question Zeebe 8.5 asks at deploy
 * ("does the FEEL parser accept this expression?") asked of the PARSED MODEL, not the raw XML,
 * because the model is what the runtime evaluates and the parser's own transformations (one
 * leading {@code =} stripped from the fields it strips, {@code BpmnParseServiceImpl.stripLeadingEquals})
 * are already applied to it.
 *
 * <p>Reuses the engine's FEEL parser — the singleton {@link FeelEngineApi} built by
 * {@code DmnEngineConfig} (feel-engine, the same feel-scala line Camunda 8 runs) — via its
 * parse-only API ({@code parseExpression}/{@code parseUnaryTests}). Deliberately NOT
 * {@code ScriptService.evaluate*}: evaluation would report undefined VARIABLES as failures,
 * while this validator's only question is syntax. Zeebe parity (rejecting {@code =x = 'a'} at
 * deploy) was verified live on Zeebe 8.5.0 in the WO-C8-56 escalation; the parse-only question
 * is exactly what that parity is about.
 *
 * <p>Runtime mirror, field by field (checked in the handlers this WO covers):
 * <ul>
 *   <li>sequence-flow condition — {@code FlowNavigator.processFlow} / {@code InclusiveGatewayHandler.isFlowActive}:
 *       non-empty, unconditional {@code substring(1)}, evaluated as UNARY tests;</li>
 *   <li>conditional event condition — {@code EventTrigger.conditionHolds} / {@code ConditionalCatchHandler.conditionHolds}:
 *       non-blank, strip a leading {@code =}, UNARY tests;</li>
 *   <li>user-task assignee/candidateUsers/candidateGroups/dueDate/followUpDate/priority —
 *       {@code ElementSupport.resolveExpression*}: FEEL only when the value starts with {@code =}
 *       ({@code ${var}} and literals are not FEEL);</li>
 *   <li>service-task + process default priority — same {@code =}-only rule;</li>
 *   <li>ioMapping input/output source — always FEEL when non-blank (strip a leading {@code =});</li>
 *   <li>message correlationKey — always FEEL when non-blank (strip a leading {@code =});</li>
 *   <li>multi-instance inputCollection/outputElement — raw: the PARSER already stripped the
 *       {@code =} ({@code BpmnParseServiceImpl} attachMultiInstance), the runtime evaluates raw;</li>
 *   <li>multi-instance loopCardinality — FEEL only while inputCollection is blank (the collection
 *       path wins, {@code MultiInstanceExecutor}); strip a leading {@code =};</li>
 *   <li>multi-instance + ad-hoc completionCondition — MI: strip a leading {@code =} (runtime
 *       strips, parser does not); ad-hoc: raw (parser strips, runtime evaluates raw);</li>
 *   <li>ad-hoc activeElementsCollection/outputElement — raw (parser strips, runtime evaluates raw);</li>
 *   <li>script task script — raw (the zeebe:script form is parser-stripped, the inline
 *       {@code <script>} form is not; the runtime evaluates whatever the model holds, raw);</li>
 *   <li>timer / event-subprocess triggerTimer — FEEL only when the value starts with {@code =}
 *       (ISO durations/dates/cron are not FEEL, {@code ElementSupport.computeDueAt});</li>
 *   <li>business-rule expression — FEEL only when non-blank AND decisionId is blank (the
 *       runtime's else-branch: the decision wins, the expression is dead text); strip a leading
 *       {@code =}; decisionId — FEEL only when it starts with {@code =} (it may be a resolved
 *       expression, WO-C8-2);</li>
 *   <li>call activity processId — FEEL only when it starts with {@code =} (WO-C8-2);</li>
 *   <li>messageName — a literal subscription/throw name at runtime, NOT FEEL: not validated.</li>
 * </ul>
 *
 * <p>Findings are collected, never thrown here: the refusal lives in
 * {@code ProcessDefinitionServiceImpl.addProcessDefinition}, the single deploy gate, so that an
 * already-deployed definition with a broken expression keeps running exactly as today (no
 * upgrade-induced parse failure on the runtime model load, the same discipline as
 * {@code BpmnSupportScanner}'s recorded findings).
 */
@Component
public class FeelSyntaxValidator {

    /**
     * How a raw model value becomes the string the FEEL parser must accept, and with which
     * grammar. The split mirrors the runtime evaluation call, field by field (see the class
     * javadoc) — not a guess about what "looks like FEEL".
     */
    public enum FeelExpressionKind {
        /** Sequence-flow condition: the runtime does an unconditional {@code substring(1)} and
         *  evaluates the rest as UNARY tests ({@code scriptService.evaluateScript}). */
        FLOW_CONDITION,
        /** Conditional event condition: strip ONE leading {@code =} if present, UNARY tests. */
        UNARY_CONDITION,
        /** Strip ONE leading {@code =} if present, then a FEEL expression. The kind shared by
         *  every runtime field that strips conditionally (ioMapping source, correlationKey,
         *  cardinality, MI completionCondition, assignee-likes, timers, …). Idempotent for
         *  values the parser already stripped. */
        FEEL_EXPRESSION,
        /** No strip at all: the model value is already exactly what the runtime evaluates
         *  (parser-stripped fields, inline {@code <script>}). */
        FEEL_EXPRESSION_PLAIN
    }

    private final FeelEngineApi feelEngineApi;

    public FeelSyntaxValidator(FeelEngineApi feelEngineApi) {
        this.feelEngineApi = feelEngineApi;
    }

    /**
     * Parses one value with the grammar the runtime would use for it and returns the FEEL parser's
     * failure message verbatim (it carries the expression text and the line:column position),
     * or empty when the value parses. The single entry point both the model walk and the
     * corpus/fixture tests go through, so the strip policy and grammar choice live in exactly one
     * place (G-N: the tests assert behaviour, they do not re-implement it).
     */
    public Optional<String> parseError(FeelExpressionKind kind, String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return Optional.empty();
        }
        String toParse = switch (kind) {
            case FLOW_CONDITION -> rawValue.substring(1);
            case UNARY_CONDITION, FEEL_EXPRESSION ->
                rawValue.startsWith("=") ? rawValue.substring(1) : rawValue;
            case FEEL_EXPRESSION_PLAIN -> rawValue;
        };
        ParseResult result = switch (kind) {
            case FLOW_CONDITION, UNARY_CONDITION -> feelEngineApi.parseUnaryTests(toParse);
            case FEEL_EXPRESSION, FEEL_EXPRESSION_PLAIN -> feelEngineApi.parseExpression(toParse);
        };
        return result.isFailure() ? Optional.of(result.failure().message()) : Optional.empty();
    }

    /**
     * Walks the parsed model the same way the runtime would meet each expression and collects
     * every value the engine's own FEEL parser rejects. Empty for a deployable model.
     */
    public List<InvalidFeelExpression> validate(BpmnProcessDefinitionModel model) {
        List<InvalidFeelExpression> findings = new ArrayList<>();

        // process level: defaultJobPriority is FEEL only when written as =expr
        // (ElementSupport.resolvePriority falls back to it, same =-only rule as task priority)
        String defaultJobPriority = model.getDefaultJobPriority();
        if (defaultJobPriority != null && defaultJobPriority.startsWith("=")) {
            parseError(FeelExpressionKind.FEEL_EXPRESSION, defaultJobPriority)
                .ifPresent(error -> findings.add(new InvalidFeelExpression(
                    model.getKey(), "defaultJobPriority", defaultJobPriority, error)));
        }

        // sequence flows: condition is unconditional substring(1) + unary tests
        for (BpmnFlowModel flow : model.getFlows()) {
            String condition = Optional.ofNullable(flow.getConditionExpression())
                .map(BpmnConditionExpressionModel::getExpression)
                .filter(str -> !str.isEmpty())
                .orElse(null);
            if (condition == null) {
                continue;
            }
            parseError(FeelExpressionKind.FLOW_CONDITION, condition)
                .ifPresent(error -> findings.add(new InvalidFeelExpression(
                    flow.getFlowId(), "condition", condition, error)));
        }

        for (BpmnElementModel element : model.getElements()) {
            BpmnElementExtensionModel ext = element.getExtensions();
            if (ext == null) {
                continue;
            }
            validateIoMapping(element, ext, findings);
            validateMultiInstance(element, ext, findings);
            validateAdHoc(element, ext, findings);
            validateScriptTask(element, ext, findings);
            validateUserTask(element, ext, findings);
            validateServiceTask(element, ext, findings);
            validateMessageEvent(element, ext, findings);
            validateTimers(element, ext, findings);
            validateBusinessRule(element, ext, findings);
            validateCallActivity(element, ext, findings);
            validateConditionalEvent(element, ext, findings);
        }
        return List.copyOf(findings);
    }

    private void validateIoMapping(BpmnElementModel element, BpmnElementExtensionModel ext,
                                   List<InvalidFeelExpression> findings) {
        IoMappingExtensionModel io = ext.getIoMappingExtension();
        if (io == null) {
            return;
        }
        collectMappingSources(element, io.getInputs(), "ioMapping.input.source", ext, findings, io);
        collectMappingSources(element, io.getOutputs(), "ioMapping.output.source", ext, findings, io);
    }

    private void collectMappingSources(BpmnElementModel element, List<IoMappingExtensionModel.Mapping> mappings,
                                       String field, BpmnElementExtensionModel ext,
                                       List<InvalidFeelExpression> findings, IoMappingExtensionModel io) {
        if (mappings == null) {
            return;
        }
        for (IoMappingExtensionModel.Mapping mapping : mappings) {
            String source = mapping.getSource();
            if (source == null || source.isBlank()) {
                continue;
            }
            parseError(FeelExpressionKind.FEEL_EXPRESSION, source)
                .ifPresent(error -> findings.add(new InvalidFeelExpression(
                    element.getId(), field, source, error)));
        }
    }

    private void validateMultiInstance(BpmnElementModel element, BpmnElementExtensionModel ext,
                                       List<InvalidFeelExpression> findings) {
        MultiInstanceExtensionModel mi = ext.getMultiInstanceExtension();
        if (mi == null) {
            return;
        }
        // raw: the parser stripped the leading '=' at parse (attachMultiInstance), the runtime
        // evaluates the model value as-is
        checkPlain(element.getId(), "multiInstance.inputCollection", mi.getInputCollection(), findings);
        checkPlain(element.getId(), "multiInstance.outputElement", mi.getOutputElement(), findings);
        // cardinality is FEEL only while inputCollection is blank — the collection path wins
        // (MultiInstanceExecutor), so a broken cardinality next to a good collection never runs
        String cardinality = mi.getCardinality();
        if (cardinality != null && !cardinality.isBlank()
            && (mi.getInputCollection() == null || mi.getInputCollection().isBlank())) {
            parseError(FeelExpressionKind.FEEL_EXPRESSION, cardinality)
                .ifPresent(error -> findings.add(new InvalidFeelExpression(
                    element.getId(), "multiInstance.loopCardinality", cardinality, error)));
        }
        // the parser does NOT strip MI completionCondition — the runtime strips a leading '='
        checkStripIfPrefixed(element.getId(), "multiInstance.completionCondition",
            mi.getCompletionCondition(), findings);
    }

    private void validateAdHoc(BpmnElementModel element, BpmnElementExtensionModel ext,
                               List<InvalidFeelExpression> findings) {
        AdHocSubProcessExtensionModel adHoc = ext.getAdHocSubProcessExtension();
        if (adHoc == null) {
            return;
        }
        // raw: the parser stripped the leading '=' at parse, the runtime evaluates the model
        // value as-is
        checkPlain(element.getId(), "adHoc.activeElementsCollection",
            adHoc.getActiveElementsCollection(), findings);
        checkPlain(element.getId(), "adHoc.outputElement", adHoc.getOutputElement(), findings);
        checkPlain(element.getId(), "adHoc.completionCondition", adHoc.getCompletionCondition(), findings);
    }

    private void validateScriptTask(BpmnElementModel element, BpmnElementExtensionModel ext,
                                    List<InvalidFeelExpression> findings) {
        ScriptTaskExtensionModel script = ext.getScriptTaskExtension();
        if (script == null) {
            return;
        }
        // raw: the zeebe:script form is parser-stripped, the inline <script> form is not — the
        // runtime (SyncTaskHandler) evaluates whatever the model holds, with no further strip
        checkPlain(element.getId(), "script", script.getScript(), findings);
    }

    private void validateUserTask(BpmnElementModel element, BpmnElementExtensionModel ext,
                                  List<InvalidFeelExpression> findings) {
        UserTaskExtensionModel task = ext.getUserTaskExtension();
        if (task == null) {
            return;
        }
        // all six are FEEL only when written as =expr (${var} and literals resolve without FEEL)
        checkFeelOnlyIfPrefixed(element.getId(), "assignee", task.getAssignee(), findings);
        checkFeelOnlyIfPrefixed(element.getId(), "candidateUsers", task.getCandidateUsers(), findings);
        checkFeelOnlyIfPrefixed(element.getId(), "candidateGroups", task.getCandidateGroups(), findings);
        checkFeelOnlyIfPrefixed(element.getId(), "dueDate", task.getDueDate(), findings);
        checkFeelOnlyIfPrefixed(element.getId(), "followUpDate", task.getFollowUpDate(), findings);
        checkFeelOnlyIfPrefixed(element.getId(), "priorityDefinition", task.getPriority(), findings);
    }

    private void validateServiceTask(BpmnElementModel element, BpmnElementExtensionModel ext,
                                     List<InvalidFeelExpression> findings) {
        ServiceTaskExtensionModel task = ext.getServiceTaskExtension();
        if (task == null) {
            return;
        }
        checkFeelOnlyIfPrefixed(element.getId(), "jobPriorityDefinition", task.getPriority(), findings);
    }

    private void validateMessageEvent(BpmnElementModel element, BpmnElementExtensionModel ext,
                                      List<InvalidFeelExpression> findings) {
        MessageEventExtensionModel message = ext.getMessageEventExtension();
        if (message == null) {
            return;
        }
        // messageName is a literal subscription/throw name at runtime — not FEEL, not validated
        checkStripIfPrefixed(element.getId(), "correlationKey",
            message.getCorrelationKeyExpression(), findings);
    }

    private void validateTimers(BpmnElementModel element, BpmnElementExtensionModel ext,
                                List<InvalidFeelExpression> findings) {
        // FEEL only when written as =expr — ISO durations/dates/cron are not FEEL (computeDueAt)
        checkFeelOnlyIfPrefixed(element.getId(), "timer",
            ext.getTimerEventExtension() == null ? null : ext.getTimerEventExtension().getExpression(),
            findings);
        SubProcessExtensionModel subProcess = ext.getSubProcessExtension();
        if (subProcess != null && subProcess.getTriggerTimer() != null) {
            checkFeelOnlyIfPrefixed(element.getId(), "triggerTimer",
                subProcess.getTriggerTimer().getExpression(), findings);
        }
    }

    private void validateBusinessRule(BpmnElementModel element, BpmnElementExtensionModel ext,
                                      List<InvalidFeelExpression> findings) {
        BusinessRuleExtensionModel rule = ext.getBusinessRuleExtension();
        if (rule == null) {
            return;
        }
        // the runtime (SyncTaskHandler) evaluates the expression ONLY in the else-branch — when
        // decisionId is blank. A broken expression next to a decision is dead text at runtime, so
        // it is not refused either: refusing it would eat a model that runs fine today.
        boolean decisionIdBlank = rule.getDecisionId() == null || rule.getDecisionId().isBlank();
        if (decisionIdBlank) {
            checkStripIfPrefixed(element.getId(), "expression", rule.getExpression(), findings);
        }
        // decisionId may be a FEEL expression resolving to the decision (WO-C8-2), but a plain
        // id is not FEEL — same =-only rule as the other resolveExpression fields
        checkFeelOnlyIfPrefixed(element.getId(), "decisionId", rule.getDecisionId(), findings);
    }

    private void validateCallActivity(BpmnElementModel element, BpmnElementExtensionModel ext,
                                      List<InvalidFeelExpression> findings) {
        CallActivityExtensionModel call = ext.getCallActivityExtension();
        if (call == null) {
            return;
        }
        checkFeelOnlyIfPrefixed(element.getId(), "processId", call.getProcessId(), findings);
    }

    private void validateConditionalEvent(BpmnElementModel element, BpmnElementExtensionModel ext,
                                          List<InvalidFeelExpression> findings) {
        EventDefinitionExtensionModel definition = ext.getEventDefinition();
        if (definition == null || definition.getType() != EventDefinitionType.CONDITIONAL) {
            return;
        }
        String condition = definition.getExpression();
        if (condition == null || condition.isBlank()) {
            return;
        }
        parseError(FeelExpressionKind.UNARY_CONDITION, condition)
            .ifPresent(error -> findings.add(new InvalidFeelExpression(
                element.getId(), "condition", condition, error)));
    }

    // ─── gates, one per runtime evaluation policy ─────────────────────────

    private void checkPlain(String elementId, String field, String raw,
                            List<InvalidFeelExpression> findings) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        parseError(FeelExpressionKind.FEEL_EXPRESSION_PLAIN, raw)
            .ifPresent(error -> findings.add(new InvalidFeelExpression(elementId, field, raw, error)));
    }

    private void checkStripIfPrefixed(String elementId, String field, String raw,
                                      List<InvalidFeelExpression> findings) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        parseError(FeelExpressionKind.FEEL_EXPRESSION, raw)
            .ifPresent(error -> findings.add(new InvalidFeelExpression(elementId, field, raw, error)));
    }

    private void checkFeelOnlyIfPrefixed(String elementId, String field, String raw,
                                         List<InvalidFeelExpression> findings) {
        if (raw == null || !raw.startsWith("=")) {
            return;
        }
        parseError(FeelExpressionKind.FEEL_EXPRESSION, raw)
            .ifPresent(error -> findings.add(new InvalidFeelExpression(elementId, field, raw, error)));
    }
}
