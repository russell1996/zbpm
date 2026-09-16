package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-40 (B-7): compensation runs in reverse COMPLETION order and one
 * failing handler does not strand the rest.
 */
@ExtendWith(MockitoExtension.class)
class CompensationThrowHandlerTest {

    @Mock private DBService dbService;
    @Mock private FlowNavigator flowNavigator;
    @Mock private TokenExecutor executor;

    @InjectMocks private CompensationThrowHandler handler;

    private static BpmnElementModel boundary(String id, String attachedTo, String handlerId) {
        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId(id);
        boundary.setType(BpmnElementType.COMPENSATION_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef(attachedTo);
        boundaryExt.setCompensationHandlerId(handlerId);
        ext.setBoundaryEventExtension(boundaryExt);
        boundary.setExtensions(ext);
        return boundary;
    }

    private static BpmnElementModel element(String id, BpmnElementType type) {
        BpmnElementModel e = new BpmnElementModel();
        e.setId(id);
        e.setType(type);
        return e;
    }

    private static Activity activity(String bpmnElementId, Instant createdAt, Instant completedAt) {
        Activity a = new Activity();
        a.setId(UUID.randomUUID());
        a.setBpmnElementId(bpmnElementId);
        a.setCreatedAt(createdAt);
        a.setCompletedAt(completedAt);
        return a;
    }

    /** Two compensable tasks + their boundaries and handlers. */
    private BpmnProcessDefinitionModel bpmn() {
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(element("taskA", BpmnElementType.SERVICE_TASK));
        bpmn.addElement(element("taskB", BpmnElementType.SERVICE_TASK));
        bpmn.addElement(boundary("compA", "taskA", "handlerA"));
        bpmn.addElement(boundary("compB", "taskB", "handlerB"));
        bpmn.addElement(element("handlerA", BpmnElementType.SERVICE_TASK));
        bpmn.addElement(element("handlerB", BpmnElementType.SERVICE_TASK));
        return bpmn;
    }

    @Test
    void runCompensation_ordersByCompletedAtReversedNotCreatedAt() {
        // Given: taskA created FIRST but completed LAST; taskB created last, completed first.
        // Reverse-creation order would run B first; reverse-completion order runs A first.
        BpmnProcessDefinitionModel bpmn = bpmn();
        UUID pi = UUID.randomUUID();
        UUID runToken = UUID.randomUUID();
        Activity a = activity("taskA", Instant.ofEpochMilli(100), Instant.ofEpochMilli(300));
        Activity b = activity("taskB", Instant.ofEpochMilli(200), Instant.ofEpochMilli(150));

        // When
        handler.runCompensation(pi, runToken, bpmn, List.of(a, b), executor);

        // Then — latest-completed (A) compensates first
        InOrder inOrder = inOrder(executor);
        inOrder.verify(executor).execute(eq(pi), eq(runToken), eq(bpmn), eq(bpmn.getElement("handlerA")));
        inOrder.verify(executor).execute(eq(pi), eq(runToken), eq(bpmn), eq(bpmn.getElement("handlerB")));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void runCompensation_failingHandlerDoesNotStopTheRest() {
        // Given: handlerA throws mid-run
        BpmnProcessDefinitionModel bpmn = bpmn();
        UUID pi = UUID.randomUUID();
        UUID runToken = UUID.randomUUID();
        Activity a = activity("taskA", Instant.ofEpochMilli(100), Instant.ofEpochMilli(300));
        Activity b = activity("taskB", Instant.ofEpochMilli(200), Instant.ofEpochMilli(150));
        doThrow(new RuntimeException("handler boom"))
            .when(executor).execute(eq(pi), eq(runToken), eq(bpmn), eq(bpmn.getElement("handlerA")));
        UUID incidentActivityId = UUID.randomUUID();
        when(dbService.createActivity(eq(pi), eq(runToken), eq(bpmn.getElement("handlerA"))))
            .thenReturn(incidentActivityId);

        // When
        handler.runCompensation(pi, runToken, bpmn, List.of(a, b), executor);

        // Then — B still compensated, A's failure parked as an incident
        verify(executor).execute(eq(pi), eq(runToken), eq(bpmn), eq(bpmn.getElement("handlerB")));
        verify(dbService).errorActivity(incidentActivityId);
        verify(dbService).createIncident(eq(incidentActivityId), any(String.class));
    }

    @Test
    void runCompensation_activityWithoutCompletedAtSortsLast() {
        // Given: taskB has no completion timestamp — sorts after every completed activity
        BpmnProcessDefinitionModel bpmn = bpmn();
        UUID pi = UUID.randomUUID();
        UUID runToken = UUID.randomUUID();
        Activity a = activity("taskA", Instant.ofEpochMilli(100), Instant.ofEpochMilli(300));
        Activity b = activity("taskB", Instant.ofEpochMilli(50), null);

        // When
        handler.runCompensation(pi, runToken, bpmn, List.of(b, a), executor);

        // Then — completed A first, timestamp-less B last
        InOrder inOrder = inOrder(executor);
        inOrder.verify(executor).execute(eq(pi), eq(runToken), eq(bpmn), eq(bpmn.getElement("handlerA")));
        inOrder.verify(executor).execute(eq(pi), eq(runToken), eq(bpmn), eq(bpmn.getElement("handlerB")));
        inOrder.verifyNoMoreInteractions();
    }
}
