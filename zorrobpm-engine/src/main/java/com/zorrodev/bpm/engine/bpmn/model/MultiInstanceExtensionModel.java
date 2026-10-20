package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Execution model for a multi-instance activity: whether it is sequential or parallel and the (literal or
 * FEEL) {@code cardinality} expression giving the number of instances.
 */
@Getter
@Setter
public class MultiInstanceExtensionModel {
    private boolean sequential;
    private String cardinality;
    /** Optional FEEL boolean: when it evaluates true after an instance completes, the multi-instance
     *  completes early (no further instances are started). */
    private String completionCondition;
    /** Camunda 8 {@code zeebe:loopCharacteristics}: the instance count is the size of this FEEL collection. */
    private String inputCollection;
    /** Per-instance variable name for the current collection element (requires scoped variables — pending). */
    private String inputElement;
    /** Variable to aggregate per-instance results into (requires scoped variables — pending). */
    private String outputCollection;
    /** FEEL expression for the per-instance result added to {@code outputCollection} (pending). */
    private String outputElement;

    /**
     * WO-C8-55: was {@code inputCollection} written as an EXPRESSION ({@code =…}) in the source
     * XML? The parser strips that leading {@code =}, so the {@link #inputCollection} VALUE alone
     * cannot tell {@code =items} from {@code items} — and live Zeebe 8.5 treats the two
     * differently: the first is the only legal form, the second is a static value the model is
     * REFUSED for at deploy («Expected expression but found static value 'items'. An expression
     * must start with '='»). The deploy gate needs the original spelling, the runtime does not.
     */
    private boolean inputCollectionExpression;
    /**
     * WO-C8-55: the same marker for {@code outputElement} — see
     * {@link #inputCollectionExpression}. {@code inputElement} and {@code outputCollection} need
     * no marker: they are variable NAMES, not expressions, and live 8.5 accepts them as they are.
     */
    private boolean outputElementExpression;
}
