package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.dto.Activity;
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
class IncidentServiceTest {

    @Mock
    private DBService dbService;

    @InjectMocks
    private IncidentService incidentService;

    @Test
    void raiseIncident_createsIncidentWhenActivityExists() {
        // Given
        UUID processInstanceId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        BpmnElementModel element = new BpmnElementModel();
        element.setId("serviceTask1");
        element.setType(BpmnElementType.SERVICE_TASK);
        Exception e = new RuntimeException("Something failed");

        Activity activity = new Activity();
        activity.setId(UUID.randomUUID());
        when(dbService.getActivitiesByTokenAndBpmnElementId(tokenId, "serviceTask1"))
            .thenReturn(List.of(activity));

        // When
        incidentService.raiseIncident(processInstanceId, tokenId, element, e);

        // Then
        verify(dbService).errorActivity(activity.getId());
        verify(dbService).createIncident(eq(activity.getId()), any(String.class));
    }

    @Test
    void raiseIncident_createsFallbackActivityWhenNoneExists() {
        // Given — WO-REL-40 (B-6): no activity for the failed element must NOT
        // drop the incident silently; a fallback activity carries it instead.
        UUID processInstanceId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        BpmnElementModel element = new BpmnElementModel();
        element.setId("serviceTask1");
        element.setType(BpmnElementType.SERVICE_TASK);
        Exception e = new RuntimeException("Something failed");

        when(dbService.getActivitiesByTokenAndBpmnElementId(tokenId, "serviceTask1"))
            .thenReturn(List.of());
        UUID fallbackId = UUID.randomUUID();
        when(dbService.createActivity(processInstanceId, tokenId, element)).thenReturn(fallbackId);

        // When
        incidentService.raiseIncident(processInstanceId, tokenId, element, e);

        // Then — the fallback activity is parked ERROR with a recorded incident
        verify(dbService).createActivity(processInstanceId, tokenId, element);
        verify(dbService).errorActivity(fallbackId);
        verify(dbService).createIncident(eq(fallbackId), any(String.class));
    }

    @Test
    void resolveIncident_isIdempotentWhenAlreadyResolved() {
        // Given
        UUID incidentId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();

        Incident incident = new Incident();
        incident.setId(incidentId);
        incident.setActivityId(activityId);
        incident.setCompletedAt(java.time.Instant.now()); // Already resolved

        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setToken(UUID.randomUUID());
        activity.setBpmnElementId("serviceTask1");

        when(dbService.getIncident(incidentId)).thenReturn(incident);
        when(dbService.getActivity(activityId)).thenReturn(activity);

        TokenExecutor executor = org.mockito.Mockito.mock(TokenExecutor.class);

        // When
        incidentService.resolveIncident(incidentId, List.of(), executor);

        // Then - should not re-execute (but lockProcessInstance IS called before the idempotency check)
        verify(dbService).lockProcessInstance(processInstanceId);
        verify(executor, never()).execute(any(), any(), any());
    }
}