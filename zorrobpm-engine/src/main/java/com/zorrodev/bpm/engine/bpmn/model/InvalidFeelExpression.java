package com.zorrodev.bpm.engine.bpmn.model;

/**
 * WO-C8-56: one FEEL expression in a model that the engine's own FEEL parser (feel-engine, the
 * same singleton {@code FeelEngineApi} the DMN evaluator uses) cannot parse — i.e. an expression
 * that would fail at runtime the first time the engine tries to evaluate it (Zeebe 8.5 rejects
 * such a model at deploy; we now do the same, parity verified live in the WO-C8-56 escalation).
 *
 * <p>WO-C8-55 adds the second kind of finding: a value that is not an EXPRESSION AT ALL where
 * live 8.5 requires one — an ioMapping OUTPUT source written without a leading {@code =}
 * («Expected expression but found static value 'hello'. An expression must start with '='»).
 * Such a finding carries no parser position (there was no parse) — the message is the live
 * wording, so the modeller sees the same sentence Zeebe shows.</p>
 *
 * <p>Collected by {@code FeelSyntaxValidator} over the parsed model and refused in
 * {@code ProcessDefinitionServiceImpl.addProcessDefinition} — the single deploy gate — as the
 * same 400 + stable-code answer {@code SERVICE_TASK_MISSING_JOB} / the UNSUPPORTED_* family
 * already established. Already-deployed definitions are untouched: validation runs on the deploy
 * path only, never on the runtime model load.
 *
 * @param elementId   the element id the expression belongs to (the flow id for sequence-flow
 *                    conditions; the process-level defaultJobPriority finding names the process id)
 * @param field       the model field holding the expression, stable and machine-readable
 *                    (e.g. {@code condition}, {@code assignee}, {@code inputCollection})
 * @param expression  the RAW value as stored in the model (post-parse: the parser already
 *                    stripped one leading {@code =} from the fields it strips)
 * @param parseError  the FEEL parser's failure message verbatim — it carries the expression text
 *                    and the line:column position, so the answer is diagnosable without the log;
 *                    for the WO-C8-55 «not an expression» finding it carries the live-8.5 wording
 *                    instead (no position: there was no parse)
 */
public record InvalidFeelExpression(String elementId, String field, String expression, String parseError) {
}
