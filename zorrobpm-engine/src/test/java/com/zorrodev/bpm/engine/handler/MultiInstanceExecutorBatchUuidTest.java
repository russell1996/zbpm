package com.zorrodev.bpm.engine.handler;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-41 (B-8, п.2): the batch-UUID lookup is ONE pinpoint read.
 */
@ExtendWith(MockitoExtension.class)
class MultiInstanceExecutorBatchUuidTest {

    @Mock private DBService dbService;
    @Mock private ScriptService scriptService;
    @Mock private ServiceTaskEnqueueService serviceTaskEnqueueService;
    @Mock private ObjectMapper objectMapper;
    @Mock private BpmnService bpmnService;
    @Mock private ElementSupport elementSupport;
    @Mock private BoundaryScheduler boundaryScheduler;
    @Mock private FlowNavigator flowNavigator;

    @InjectMocks private MultiInstanceExecutor executor;

    private static BpmnElementModel miElement() {
        BpmnElementModel element = new BpmnElementModel();
        element.setId("mi");
        element.setType(BpmnElementType.USER_TASK);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        MultiInstanceExtensionModel mi = new MultiInstanceExtensionModel();
        mi.setSequential(false);
        mi.setCardinality("=3");
        ext.setMultiInstanceExtension(mi);
        element.setExtensions(ext);
        return element;
    }

    @Test
    void multiInstanceContinue_readsBatchUuidWithSinglePinpointQuery() {
        // Given: 1 of 3 arrived — not done, no completion condition.
        UUID pi = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        BpmnElementModel element = miElement();
        when(dbService.getVariableTextValue(pi, "_mi_batch_mi")).thenReturn(Optional.of("batch-1"));
        when(dbService.getInclusiveExpected(pi, "mi::batch-1")).thenReturn(3);
        when(dbService.getParallelGatewayArrivedFlows(pi, "mi::batch-1")).thenReturn(Set.of(completed.toString()));
        // Spare branch for the POF mutant (full getVariables → legacy miId key):
        // lenient, so GREEN stays strict while the mutant still reaches the
        // never-getVariables verification below instead of dying on a stub mismatch.
        lenient().when(dbService.getInclusiveExpected(pi, "mi::mi")).thenReturn(3);
        lenient().when(dbService.getParallelGatewayArrivedFlows(pi, "mi::mi"))
            .thenReturn(Set.of(completed.toString()));

        // When
        boolean done = executor.multiInstanceContinue(pi, token, element, completed);

        // Then — WO-REL-41 п.2: ровно один точечный SELECT, полной выборки нет.
        assertThat(done).isFalse();
        verify(dbService).getVariableTextValue(pi, "_mi_batch_mi");
        verify(dbService, never()).getVariables(any(UUID.class));
        verify(dbService, never()).getVariables(any(UUID.class), any(UUID.class));
        // ...и bookkeeping идёт под batch-ключом, не под legacy-fallback.
        verify(dbService).recordParallelGatewayArrival(pi, "mi::batch-1", completed.toString());
    }

    @Test
    void multiInstanceContinue_fallsBackToMiIdWhenBatchRowAbsent() {
        // Given: legacy instance without the batch row.
        UUID pi = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        BpmnElementModel element = miElement();
        // Lenient: the POF mutant (full getVariables) never calls this — the
        // mutant must die on the never-getVariables verification, not on an
        // unused-stub technicality.
        lenient().when(dbService.getVariableTextValue(pi, "_mi_batch_mi")).thenReturn(Optional.empty());
        when(dbService.getInclusiveExpected(pi, "mi::mi")).thenReturn(3);
        when(dbService.getParallelGatewayArrivedFlows(pi, "mi::mi")).thenReturn(Set.of(completed.toString()));

        // When
        boolean done = executor.multiInstanceContinue(pi, token, element, completed);

        // Then — WO-ENG-7 fallback сохранён.
        assertThat(done).isFalse();
        verify(dbService).recordParallelGatewayArrival(pi, "mi::mi", completed.toString());
        verify(dbService, never()).getVariables(any(UUID.class));
    }

    @Test
    void enter_evaluatesInputCollectionOnce() {
        // Given: parallel MI over a 2-item collection.
        UUID pi = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        BpmnElementModel element = miElement();
        element.getExtensions().getMultiInstanceExtension().setCardinality(null);
        element.getExtensions().getMultiInstanceExtension().setInputCollection("=items");
        element.getExtensions().getMultiInstanceExtension().setInputElement("item");
        when(dbService.getVariables(pi)).thenReturn(List.of());
        when(scriptService.evaluateExpression(eq("=items"), any())).thenReturn(List.of(10, 20));

        // When
        executor.enter(pi, token, element, org.mockito.Mockito.mock(TokenExecutor.class));

        // Then — WO-REL-41 п.3: один корневой read, один FEEL-eval коллекции.
        verify(dbService).getVariables(pi);
        verify(scriptService).evaluateExpression(eq("=items"), any());
        verify(dbService).recordInclusiveExpected(eq(pi), argThat(k -> k != null && k.startsWith("mi::")), eq(2));
    }
}
