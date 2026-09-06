package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
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
class CompletionServiceTest {

    @Mock
    private DBService dbService;
    @Mock
    private BpmnService bpmnService;
    @Mock
    private ServiceTaskEnqueueService serviceTaskEnqueueService;
    @Mock
    private ElementSupport elementSupport;
    @Mock
    private MultiInstanceExecutor multiInstanceExecutor;
    @Mock
    private FlowNavigator flowNavigator;
    @Mock
    private EventTrigger eventTrigger;
    @Mock
    private ExecutionContext executionContext;

    // WO-C8-25: phase-first branches return empty/false by Mockito default —
    // existing tests exercise the pre-phase paths unchanged.
    @Mock
    private ElementListenerPhaseService elementListenerPhaseService;

    @InjectMocks
    private CompletionService completionService;

    @Test
    void failServiceTask_retriesExhausted_raisesIncident() {
        // Given
        UUID serviceTaskId = UUID.randomUUID();
        String errorMessage = "Worker failed";
        Integer retries = 0; // Exhausted

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setStatus(ActivityStatus.CREATED);
        activity.setProcessInstanceId(UUID.randomUUID());
        activity.setToken(UUID.randomUUID());
        activity.setBpmnElementId("serviceTask1");

        when(elementSupport.lockAndReload(serviceTaskId)).thenReturn(activity);

        // When
        completionService.failServiceTask(serviceTaskId, errorMessage, retries);

        // Then
        verify(dbService).setServiceTaskRetries(serviceTaskId, 0);
        verify(dbService).errorActivity(serviceTaskId);
        verify(dbService).createIncident(eq(serviceTaskId), eq("Worker failed"));
        verify(serviceTaskEnqueueService, never()).enqueueAfterCommit(any());
    }

    @Test
    void failServiceTask_retriesLeft_redispaches() {
        // Given
        UUID serviceTaskId = UUID.randomUUID();
        String errorMessage = "Worker failed";
        Integer retries = 3; // Still have retries

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setStatus(ActivityStatus.CREATED);
        activity.setProcessInstanceId(UUID.randomUUID());
        activity.setToken(UUID.randomUUID());
        activity.setBpmnElementId("serviceTask1");

        when(elementSupport.lockAndReload(serviceTaskId)).thenReturn(activity);

        // When
        completionService.failServiceTask(serviceTaskId, errorMessage, retries);

        // Then
        verify(dbService).setServiceTaskRetries(serviceTaskId, 3);
        verify(dbService, never()).errorActivity(any());
        verify(dbService, never()).createIncident(any(), any());
        verify(serviceTaskEnqueueService).enqueueAfterCommit(serviceTaskId);
    }

    @Test
    void failServiceTask_ignoresCompletedTask() {
        // Given
        UUID serviceTaskId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setStatus(ActivityStatus.COMPLETED); // Already completed

        when(elementSupport.lockAndReload(serviceTaskId)).thenReturn(activity);

        // When
        completionService.failServiceTask(serviceTaskId, "error", 0);

        // Then
        verify(dbService, never()).setServiceTaskRetries(eq(serviceTaskId), org.mockito.ArgumentMatchers.anyInt());
        verify(dbService, never()).errorActivity(any());
        verify(dbService, never()).createIncident(any(), any());
    }
}