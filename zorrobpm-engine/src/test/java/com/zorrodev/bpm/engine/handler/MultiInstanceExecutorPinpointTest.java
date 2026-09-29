package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-PERF-9 (B-8, full-scan): every MI variable read is a pinpoint SELECT
 * for exactly the FEEL-referenced names — never {@code getVariables} over
 * the whole scope.
 */
@ExtendWith(MockitoExtension.class)
class MultiInstanceExecutorPinpointTest {

    @Mock private DBService dbService;
    @Mock private ScriptService scriptService;
    @Mock private ServiceTaskEnqueueService serviceTaskEnqueueService;
    @Mock private ObjectMapper objectMapper;
    @Mock private BpmnService bpmnService;
    @Mock private ElementSupport elementSupport;
    @Mock private BoundaryScheduler boundaryScheduler;
    @Mock private FlowNavigator flowNavigator;

    @InjectMocks private MultiInstanceExecutor executor;

    private static BpmnElementModel miElement(String cardinality, String inputCollection,
            String inputElement, String completionCondition) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId("mi");
        element.setType(BpmnElementType.USER_TASK);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        MultiInstanceExtensionModel mi = new MultiInstanceExtensionModel();
        mi.setSequential(false);
        mi.setCardinality(cardinality);
        mi.setInputCollection(inputCollection);
        mi.setInputElement(inputElement);
        mi.setCompletionCondition(completionCondition);
        ext.setMultiInstanceExtension(mi);
        element.setExtensions(ext);
        return element;
    }

    private static ProcessVariable pv(String name, String value) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(value);
        pv.setType(ProcessVariableType.LONG);
        return pv;
    }

    // ─── Extractor ───────────────────────────────────────────────────

    @Test
    void extractor_plainIdentifiers() {
        assertThat(MultiInstanceExecutor.extractFeelVariableNames("=items")).containsExactly("items");
        assertThat(MultiInstanceExecutor.extractFeelVariableNames("=completedInstances = totalInstances"))
            .containsExactly("completedInstances", "totalInstances");
    }

    @Test
    void extractor_stripsStringLiterals() {
        // "items" is a literal here — but the extractor is a conservative
        // SUPERSET: it must never DROP a needed name; extra fetches are
        // harmless. The literal must at least not break tokenising.
        assertThat(MultiInstanceExecutor.extractFeelVariableNames("=if x = \"items\" then y else z"))
            .contains("x", "y", "z");
        assertThat(MultiInstanceExecutor.extractFeelVariableNames("=\"a,b\""))
            .isEmpty();
    }

    @Test
    void extractor_dottedPathKeepsRoot() {
        // The engine resolves the root `order`; the path remainder is
        // navigation, not a binding — both parts fetched keeps it correct.
        assertThat(MultiInstanceExecutor.extractFeelVariableNames("=order.total > 100"))
            .contains("order", "total");
    }

    @Test
    void extractor_keywordsAndNumbersAreNotNames() {
        assertThat(MultiInstanceExecutor.extractFeelVariableNames("=count(items) > 3 and for i in 1..n"))
            .contains("items", "n", "i");
        assertThat(MultiInstanceExecutor.extractFeelVariableNames("3")).isEmpty();
        assertThat(MultiInstanceExecutor.extractFeelVariableNames(null)).isEmpty();
        assertThat(MultiInstanceExecutor.extractFeelVariableNames("  ")).isEmpty();
    }

    // ─── enter: loopCardinality ──────────────────────────────────────

    @Test
    void enter_loopCardinality_readsOnlyReferencedName() {
        UUID pi = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        BpmnElementModel element = miElement("=k", null, null, null);
        when(dbService.getVariablesByNames(eq(pi), argThat(names -> names != null && names.equals(Set.of("k")))))
            .thenReturn(List.of(pv("k", "2")));
        when(scriptService.evaluateExpression(eq("k"), any())).thenReturn(2L);

        executor.enter(pi, token, element, mock(TokenExecutor.class));

        verify(dbService).getVariablesByNames(eq(pi), argThat(names -> names != null && names.equals(Set.of("k"))));
        verify(dbService, never()).getVariables(any(UUID.class));
        verify(dbService).recordInclusiveExpected(eq(pi), argThat(k -> k != null && k.startsWith("mi::")), eq(2));
    }

    @Test
    void enter_literalCardinality_noReadAtAll() {
        UUID pi = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        // "=2": no identifiers → empty name set → no DB read (impl short-circuits).
        BpmnElementModel element = miElement("=2", null, null, null);
        when(scriptService.evaluateExpression(eq("2"), any())).thenReturn(2L);

        executor.enter(pi, token, element, mock(TokenExecutor.class));

        verify(dbService).getVariablesByNames(eq(pi), argThat(Collection::isEmpty));
        verify(dbService, never()).getVariables(any(UUID.class));
        verify(dbService).recordInclusiveExpected(eq(pi), argThat(k -> k != null && k.startsWith("mi::")), eq(2));
    }

    // ─── completionCondition ─────────────────────────────────────────

    @Test
    void continue_completionCondition_readsOnlyReferencedNames() {
        UUID pi = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        BpmnElementModel element =
            miElement("=3", null, null, "=completedInstances = totalInstances");
        when(dbService.getVariableTextValue(pi, "_mi_batch_mi")).thenReturn(Optional.of("batch-1"));
        when(dbService.getInclusiveExpected(pi, "mi::batch-1")).thenReturn(3);
        when(dbService.getParallelGatewayArrivedFlows(pi, "mi::batch-1"))
            .thenReturn(Set.of(completed.toString()));
        when(dbService.getVariablesByNames(eq(pi),
            argThat(names -> names != null && names.containsAll(Set.of("completedInstances", "totalInstances")))))
            .thenReturn(List.of());
        when(scriptService.evaluateExpression(eq("completedInstances = totalInstances"), any()))
            .thenReturn(Boolean.TRUE);

        boolean done = executor.multiInstanceContinue(pi, token, element, completed);

        assertThat(done).isTrue();
        verify(dbService).getVariablesByNames(eq(pi),
            argThat(names -> names != null && names.containsAll(Set.of("completedInstances", "totalInstances"))));
        verify(dbService, never()).getVariables(any(UUID.class));
        verify(dbService).clearParallelGatewayArrivals(pi, "mi::batch-1");
    }

    // ─── aggregate (outputElement + loopCounter slot) ────────────────

    @Test
    void aggregate_readsOnlyOutputElementNamesPlusLoopCounter() {
        UUID pi = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        BpmnElementModel element = miElement("=2", null, null, null);
        element.getExtensions().getMultiInstanceExtension().setOutputCollection("doubled");
        element.getExtensions().getMultiInstanceExtension().setOutputElement("=item * 2");
        when(dbService.getScopedVariablesByNames(eq(pi), eq(scope),
            argThat(names -> names != null && names.equals(Set.of("item")))))
            .thenReturn(List.of());
        when(dbService.getScopedVariablesByNames(eq(pi), eq(scope), eq(Set.of("loopCounter"))))
            .thenReturn(List.of(pv("loopCounter", "1")));
        when(scriptService.evaluateExpression(eq("=item * 2"), any())).thenReturn(4L);
        when(elementSupport.toJavaStructure(4L)).thenReturn(4L);
        when(objectMapper.writeValueAsString(4L)).thenReturn("4");

        executor.aggregateMultiInstanceOutput(pi, scope, element);

        verify(dbService).getScopedVariablesByNames(eq(pi), eq(scope),
            argThat(names -> names != null && names.equals(Set.of("item"))));
        verify(dbService).getScopedVariablesByNames(eq(pi), eq(scope), eq(Set.of("loopCounter")));
        verify(dbService, never()).getVariables(any(UUID.class), any(UUID.class));
        verify(dbService).setJsonElementAt(eq(pi), eq("doubled"), eq(0), eq("4"));
    }

    // ─── sequential continue (miInputCollection) ─────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void continue_sequential_rereadsOnlyCollectionName() {
        UUID pi = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        BpmnElementModel element = miElement(null, "=items", "item", null);
        element.getExtensions().getMultiInstanceExtension().setSequential(true);
        when(dbService.getVariableTextValue(pi, "_mi_batch_mi")).thenReturn(Optional.of("batch-1"));
        when(dbService.getInclusiveExpected(pi, "mi::batch-1")).thenReturn(3);
        when(dbService.getParallelGatewayArrivedFlows(pi, "mi::batch-1"))
            .thenReturn(Set.of(completed.toString()));
        // 1 of 3 arrived, no completion condition → not done → sequential
        // path re-evaluates the inputCollection pinpoint.
        when(dbService.getVariablesByNames(eq(pi), argThat(names -> names != null && names.equals(Set.of("items")))))
            .thenReturn(List.of());
        when(scriptService.evaluateExpression(eq("=items"), any()))
            .thenReturn(List.of(10, 20, 30));
        when(dbService.createActivity(eq(pi), eq(token), eq(element))).thenReturn(UUID.randomUUID());

        boolean done = executor.multiInstanceContinue(pi, token, element, completed);

        assertThat(done).isFalse();
        ArgumentCaptor<Collection<String>> names = ArgumentCaptor.forClass(Collection.class);
        verify(dbService).getVariablesByNames(eq(pi), names.capture());
        assertThat(names.getValue()).isEqualTo(Set.of("items"));
        verify(dbService, never()).getVariables(any(UUID.class));
    }
}
