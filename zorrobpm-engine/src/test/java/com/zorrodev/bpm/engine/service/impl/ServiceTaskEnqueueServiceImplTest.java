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
    }
}
