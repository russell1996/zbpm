package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoundarySchedulerTest {

    @Mock
    private DBService dbService;
    @Mock
    private ElementSupport elementSupport;

    @InjectMocks
    private BoundaryScheduler boundaryScheduler;

    @Test
    void scheduleSignalBoundaries_subscribesMatchingBoundaries() {
        // Given
        UUID processInstanceId = UUID.randomUUID();
        UUID hostActivityId = UUID.randomUUID();

        BpmnElementModel host = new BpmnElementModel();
        host.setId("userTask1");

        BpmnProcessDefinitionModel pd = new BpmnProcessDefinitionModel();

        // Signal boundary attached to userTask1
        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId("signalBoundary1");
        boundary.setType(BpmnElementType.SIGNAL_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef("userTask1");
        ext.setBoundaryEventExtension(boundaryExt);
        EventDefinitionExtensionModel eventDef = new EventDefinitionExtensionModel();
        eventDef.setName("signalName1");
        ext.setEventDefinition(eventDef);
        boundary.setExtensions(ext);

        pd.addElement(boundary);
        host.setProcessDefinition(pd);

        // When
        boundaryScheduler.scheduleSignalBoundaries(processInstanceId, hostActivityId, host);

        // Then
        verify(dbService).createSignalSubscription(processInstanceId, hostActivityId, "signalName1", "signalBoundary1");
    }

    @Test
    void scheduleSignalBoundaries_ignoresUnattachedBoundaries() {
        // Given
        UUID processInstanceId = UUID.randomUUID();
        UUID hostActivityId = UUID.randomUUID();

        BpmnElementModel host = new BpmnElementModel();
        host.setId("userTask1");

        BpmnProcessDefinitionModel pd = new BpmnProcessDefinitionModel();

        // Signal boundary attached to DIFFERENT host
        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId("signalBoundary1");
        boundary.setType(BpmnElementType.SIGNAL_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef("userTask2"); // Different host
        ext.setBoundaryEventExtension(boundaryExt);
        EventDefinitionExtensionModel eventDef = new EventDefinitionExtensionModel();
        eventDef.setName("signalName1");
        ext.setEventDefinition(eventDef);
        boundary.setExtensions(ext);

        pd.addElement(boundary);
        host.setProcessDefinition(pd);

        // When
        boundaryScheduler.scheduleSignalBoundaries(processInstanceId, hostActivityId, host);

        // Then
        verify(dbService, never()).createSignalSubscription(any(), any(), any(), any());
    }
}