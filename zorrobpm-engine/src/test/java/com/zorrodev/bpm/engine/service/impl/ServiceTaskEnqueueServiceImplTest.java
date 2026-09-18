package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.repository.ElementListenerPhaseRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTaskEnqueueServiceImplTest {

    @Mock private DBService dbService;
    @Mock private BpmnService bpmnService;
    @Mock private OutboxRepository outboxRepository;
    @Mock private tools.jackson.databind.ObjectMapper objectMapper;
    @Mock private ElementSupport elementSupport;
    @Mock private com.zorrodev.bpm.engine.tracing.TracingSupport tracing;

    @InjectMocks
    private ServiceTaskEnqueueServiceImpl service;

    // WO-C8-9: реальный ElementSupport поверх мокнутого DBService — резолв гоняет прод-код
    // (литерал/blank не трогают DB вообще, стабы не нужны), а не дефолты Mockito
    // (mock.resolvePriority вернул бы 0 для Integer — ассерт зависел бы от мока, не от кода).
    private ElementSupport realElementSupport() {
        return new ElementSupport(
            dbService, mock(ScriptService.class), mock(org.camunda.feel.api.FeelEngineApi.class),
            new tools.jackson.databind.ObjectMapper(), java.time.ZoneId.of("Asia/Almaty"));
    }

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

    /**
     * WO-OBS-8: the enqueue path persists the CURRENT traceparent into the row so the
     * outbox poller can continue the trace across the {@code @Scheduled} gap
     * (persistence half of WO-REL-26). Assert on the CONCRETE value returned by the
     * tracing collaborator — not "non-null" (P-67: null-vs-value distinguishes
     * "copied" from "minted locally").
     */
    @Test
    void obs8_enqueueAfterCommit_persistsCurrentTraceParentIntoRow() {
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
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String traceParent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        when(tracing.captureTraceParent()).thenReturn(traceParent);

        service.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getTraceParent()).isEqualTo(traceParent);
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
        // WO-C8-9: SUT constructor gained ElementSupport — a permissive mock keeps this
        // headers-only test focused (priority resolves to null, headers path untouched).
        ServiceTaskEnqueueServiceImpl realMapperService = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, new tools.jackson.databind.ObjectMapper(),
            mock(ElementSupport.class), mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());

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

    @Test
    void enqueueAfterCommit_withPriority_outboxPayloadCarriesIt() throws Exception {
        // WO-C8-9, критерий 2, strongest form (зеркало WO-C8-7 headers-теста): реальный Jackson
        // + РЕАЛЬНЫЙ ElementSupport (литерал "75" резолвится без стабов) — резолвнутый priority
        // реально лежит в outbox-JSON, который увидит воркер.
        ServiceTaskEnqueueServiceImpl realMapperService = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, new tools.jackson.databind.ObjectMapper(),
            realElementSupport(), mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());

        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskPriorityJson";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob("send-email");
        ext.setPriority("75");
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
        assertThat(captor.getValue().getPayload()).contains("\"priority\":75");
    }

    @Test
    void enqueueAfterCommit_processDefaultPriority_outboxPayloadCarriesIt() throws Exception {
        // WO-C8-13, критерий 3 (delivery-уровень): своего priority у задачи нет, process-level
        // default "50" доезжает до outbox-JSON через тот же wiring (реальный Jackson +
        // РЕАЛЬНЫЙ ElementSupport, литерал без стабов).
        ServiceTaskEnqueueServiceImpl realMapperService = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, new tools.jackson.databind.ObjectMapper(),
            realElementSupport(), mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());

        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskProcessDefaultJson";

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
        bpmn.setDefaultJobPriority("50");
        element.setProcessDefinition(bpmn);
        bpmn.addElement(element);

        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getVariables(processInstanceId, serviceTaskId)).thenReturn(List.of());

        realMapperService.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"priority\":50");
    }

    @Test
    void enqueueAfterCommit_withoutPriority_setsNullOnJobDetail() throws Exception {
        // WO-C8-9, критерий 3 (зеркало withoutHeaders; имя элемента исправлено WO-C8-13):
        // без jobPriorityDefinition — null, не ошибка.
        // SUT собран напрямую с реальным ElementSupport (пустой raw коротится до DB) —
        // null приходит из прод-кода, а не из дефолта мока.
        ServiceTaskEnqueueServiceImpl sut = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, objectMapper, realElementSupport(),
            mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskNoPriority";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        // no priority on the extension (the common case)
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

        sut.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<JobDetailModel> detailCaptor = ArgumentCaptor.forClass(JobDetailModel.class);
        verify(objectMapper).writeValueAsString(detailCaptor.capture());
        assertThat(detailCaptor.getValue().getPriority()).isNull();
    }

    @Test
    void enqueueAfterCommit_withListenerInFlight_dispatchesListenerJob() throws Exception {
        // WO-C8-11, критерий 2 (unit-уровень): pendingListenerIndex=0 → в outbox уходит
        // listener-job, НЕ реальный job. SUT напрямую с реальным ElementSupport.
        ServiceTaskEnqueueServiceImpl sut = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, objectMapper, realElementSupport(),
            mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskListener";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob("real-job");
        ext.setStartListeners(List.of(new ListenerModel("listener-job", null, null)));
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
        when(dbService.getServiceTaskPendingListenerIndex(serviceTaskId)).thenReturn(0);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        sut.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<JobDetailModel> detailCaptor = ArgumentCaptor.forClass(JobDetailModel.class);
        verify(objectMapper).writeValueAsString(detailCaptor.capture());
        assertThat(detailCaptor.getValue().getJob()).isEqualTo("listener-job");
    }

    @Test
    void enqueueAfterCommit_listenersDone_dispatchesRealJob() throws Exception {
        // WO-C8-11: последний listener завершён (index сброшен в null) → диспетчеризуется
        // РЕАЛЬНЫЙ job тем же кодом (без отдельной ветки).
        ServiceTaskEnqueueServiceImpl sut = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, objectMapper, realElementSupport(),
            mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskAfterListeners";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob("real-job");
        ext.setStartListeners(List.of(new ListenerModel("listener-job", null, null)));
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
        when(dbService.getServiceTaskPendingListenerIndex(serviceTaskId)).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        sut.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<JobDetailModel> detailCaptor = ArgumentCaptor.forClass(JobDetailModel.class);
        verify(objectMapper).writeValueAsString(detailCaptor.capture());
        assertThat(detailCaptor.getValue().getJob()).isEqualTo("real-job");
    }

    @Test
    void enqueueAfterCommit_withoutListeners_neverReadsListenerIndex() throws Exception {
        // WO-C8-11, критерий 5: у элементов без startListeners новый DB-read не вызывается
        // вообще (горячий путь без лишних запросов; моки старых тестов не требуют стабов).
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskPlain";

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

        verify(dbService, never()).getServiceTaskPendingListenerIndex(any());
    }

    @Test
    void enqueueAfterCommit_withEndListenerInFlight_dispatchesEndListenerJob() throws Exception {
        // WO-C8-11b: pendingEndListenerIndex=0 → в outbox уходит end-listener-job, НЕ реальный.
        ServiceTaskEnqueueServiceImpl sut = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, objectMapper, realElementSupport(),
            mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskEndListener";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob("real-job");
        ext.setEndListeners(List.of(new ListenerModel("listener-job-end", null, null)));
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
        when(dbService.getServiceTaskPendingEndListenerIndex(serviceTaskId)).thenReturn(0);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        sut.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<JobDetailModel> detailCaptor = ArgumentCaptor.forClass(JobDetailModel.class);
        verify(objectMapper).writeValueAsString(detailCaptor.capture());
        assertThat(detailCaptor.getValue().getJob()).isEqualTo("listener-job-end");
    }

    @Test
    void enqueueAfterCommit_withoutEndListeners_neverReadsEndListenerIndex() throws Exception {
        // WO-C8-11b, зеркало start-U3: у элементов без endListeners новый DB-read не вызывается.
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskPlainEnd";

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

        verify(dbService, never()).getServiceTaskPendingEndListenerIndex(any());
    }
}
