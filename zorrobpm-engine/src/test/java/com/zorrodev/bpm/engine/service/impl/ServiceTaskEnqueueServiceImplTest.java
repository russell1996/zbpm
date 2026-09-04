package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTaskEnqueueServiceImplTest {

    @Mock private DBService dbService;
    @Mock private BpmnService bpmnService;
    @Mock private OutboxRepository outboxRepository;
    @Mock private tools.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks
    private ServiceTaskEnqueueServiceImpl service;

    @Test
    void enqueueAfterCommit_insertsOutboxEntry() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTask1";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob("send-email");
        BpmnElementExtensionModel extensions = new BpmnElementExtensionModel();
        extensions.setServiceTaskExtension(ext);

        BpmnElementModel element = new BpmnElementModel();
        element.setId(bpmnElementId);
        element.setExtensions(extensions);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(element);

        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getVariables(processInstanceId, serviceTaskId)).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        service.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEntry entry = captor.getValue();
        assertThat(entry.getId()).isNotNull();
        assertThat(entry.isPublished()).isFalse();
        assertThat(entry.getCreatedAt()).isNotNull();
        assertThat(entry.getPayload()).isNotEmpty();
        // WO-REL-12 R-01: producer writes the explicit kind — no payload guessing downstream
        assertThat(entry.getKind()).isEqualTo(com.zorrodev.bpm.engine.entity.OutboxKind.SERVICE_TASK);
    }
    @Test
    void enqueueAfterCommit_nullJob_createsIncidentAndSkipsOutbox() {

        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskNoJob";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        // serviceTaskExtension exists but job is null
        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob(null);
        BpmnElementExtensionModel extensions = new BpmnElementExtensionModel();
        extensions.setServiceTaskExtension(ext);

        BpmnElementModel element = new BpmnElementModel();
        element.setId(bpmnElementId);
        element.setExtensions(extensions);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(element);

        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);

        service.enqueueAfterCommit(serviceTaskId);

        // must create an incident, not NPE
        verify(dbService).createIncident(eq(serviceTaskId), contains(bpmnElementId));
        // must NOT create outbox entry
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void enqueueAfterCommit_withHeaders_setsThemOnJobDetail() throws Exception {
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskHeaders";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob("send-email");
        ext.setTaskHeaders(java.util.Map.of("tenant", "acme", "priority", "high"));
        BpmnElementExtensionModel extensions = new BpmnElementExtensionModel();
        extensions.setServiceTaskExtension(ext);

        BpmnElementModel element = new BpmnElementModel();
        element.setId(bpmnElementId);
        element.setExtensions(extensions);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(element);

        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getVariables(processInstanceId, serviceTaskId)).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        service.enqueueAfterCommit(serviceTaskId);

        // WO-C8-7: headers must reach the JobDetailModel handed to serialization (not just parse).
        ArgumentCaptor<JobDetailModel> detailCaptor = ArgumentCaptor.forClass(JobDetailModel.class);
        verify(objectMapper).writeValueAsString(detailCaptor.capture());
        assertThat(detailCaptor.getValue().getTaskHeaders())
            .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("tenant", "acme", "priority", "high"));
    }

    @Test
    void enqueueAfterCommit_withoutHeaders_setsNullOnJobDetail() throws Exception {
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskNoHeaders";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        // no taskHeaders on the extension (the common case)
        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob("send-email");
        BpmnElementExtensionModel extensions = new BpmnElementExtensionModel();
        extensions.setServiceTaskExtension(ext);

        BpmnElementModel element = new BpmnElementModel();
        element.setId(bpmnElementId);
        element.setExtensions(extensions);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(element);

        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getVariables(processInstanceId, serviceTaskId)).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        service.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<JobDetailModel> detailCaptor = ArgumentCaptor.forClass(JobDetailModel.class);
        verify(objectMapper).writeValueAsString(detailCaptor.capture());
        assertThat(detailCaptor.getValue().getTaskHeaders()).isNull();
    }

    @Test
    void enqueueAfterCommit_withHeaders_outboxPayloadCarriesThem() throws Exception {
        // WO-C8-7, strongest form: real Jackson serialization — the outbox JSON the worker
        // will consume actually contains taskHeaders (proves the exchange-DTO change end to end).
        ServiceTaskEnqueueServiceImpl realMapperService = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, new tools.jackson.databind.ObjectMapper());

        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskHeadersJson";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob("send-email");
        ext.setTaskHeaders(java.util.Map.of("tenant", "acme"));
        BpmnElementExtensionModel extensions = new BpmnElementExtensionModel();
        extensions.setServiceTaskExtension(ext);

        BpmnElementModel element = new BpmnElementModel();
        element.setId(bpmnElementId);
        element.setExtensions(extensions);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(element);

        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getVariables(processInstanceId, serviceTaskId)).thenReturn(List.of());

        realMapperService.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"taskHeaders\"");
        assertThat(captor.getValue().getPayload()).contains("\"tenant\"");
        assertThat(captor.getValue().getPayload()).contains("acme");
    }
}
