package com.zorrodev.bpm.engine.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
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

    /**
     * WO-C8-36 (H-1, п.б/д): флаг {@code zorrobpm.engine.dispatch-phase-stamping}
     * выключен по умолчанию — тело задания при выключенном флаге побайтно то же,
     * что до WO (golden-JSON ниже). Ставится явно, потому что дефолт проверяется
     * отдельным тестом на прод-дефолте свойства, а не на угаданном значении.
     */
    private ServiceTaskEnqueueServiceImpl serviceWithStamping(boolean enabled) {
        ServiceTaskEnqueueServiceImpl sut = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, new tools.jackson.databind.ObjectMapper(),
            realElementSupport(), mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        sut.setDispatchPhaseStampingEnabled(enabled);
        return sut;
    }

    private void stubPlainServiceTask(UUID serviceTaskId, UUID processInstanceId,
            UUID processDefinitionId, String bpmnElementId, String job, String bpmn) {
        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob(job);
        BpmnElementExtensionModel extensions = new BpmnElementExtensionModel();
        extensions.setServiceTaskExtension(ext);

        BpmnElementModel element = new BpmnElementModel();
        element.setId(bpmnElementId);
        element.setExtensions(extensions);

        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(element);

        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(model);
        when(dbService.getVariables(processInstanceId, serviceTaskId)).thenReturn(List.of());
    }

    // WO-C8-9: реальный ElementSupport поверх мокнутого DBService — резолв гоняет прод-код
    // (литерал/blank не трогают DB вообще, стабы не нужны), а не дефолты Mockito
    // (mock.resolvePriority вернул бы 0 для Integer — ассерт зависел бы от мока, не от кода).
    private ElementSupport realElementSupport() {
        return new ElementSupport(
            dbService, mock(ScriptService.class), mock(FeelBudget.class),
            new tools.jackson.databind.ObjectMapper(), java.time.ZoneId.of("Asia/Almaty"), false);
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

    /**
     * WO-C8-36 (H-1, п.д — golden-JSON): при ВЫКЛЮЧЕННОМ флаге тело задания
     * побайтно то же, что до этого WO. Это и есть содержание решения CTO
     * «сначала воркеры, потом флаг»: старый воркер не должен получать поле,
     * которого его класс не знает, пока оператор не включил флаг осознанно.
     *
     * <p>Ассерт — на ОТСУТСТВИЕ ключей в РЕАЛЬНОМ outbox-JSON (реальный Jackson,
     * прод-сериализация), а не на null геттера: нулевой геттер при
     * {@code JsonInclude.ALWAYS} всё равно сериализовался бы в
     * {@code "dispatchPhase":null} — то есть тело изменилось бы, а ассерт на
     * null был бы зелёным.
     */
    @Test
    void enqueueAfterCommit_stampingFlagOff_jobBodyIsByteIdenticalToPreWo() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        stubPlainServiceTask(serviceTaskId, processInstanceId, processDefinitionId,
            "svcGoldenOff", "send-email", "golden-off");

        serviceWithStamping(false).enqueueAfterCommit(serviceTaskId);

        String payload = capturedPayload();
        assertThat(payload)
            .as("при выключенном флаге тело задания не содержит полей phased-штампа — "
                + "старый воркер читает его как раньше")
            .doesNotContain("dispatchPhase")
            .doesNotContain("dispatchIndex");
        // ...и всё остальное тело на месте (ассерт не проходит на пустом payload).
        assertThat(payload).contains("\"serviceTaskId\":\"" + serviceTaskId + "\"");
        assertThat(payload).contains("\"job\":\"send-email\"");
    }

    /**
     * WO-C8-36 (H-1, п.б): при ВКЛЮЧЁННОМ флаге штамп возвращается — иначе флаг
     * был бы декоративным (проверить это, сняв вызов setDispatchPhase, обязана
     * ронять ЭТОТ тест, а не зелёный golden-тест).
     */
    @Test
    void enqueueAfterCommit_stampingFlagOn_listenerJobCarriesPhaseAndIndex() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "svcGoldenOn";

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

        serviceWithStamping(true).enqueueAfterCommit(serviceTaskId);

        String payload = capturedPayload();
        assertThat(payload)
            .as("при включённом флаге phased-штамп обязателен в теле задания")
            .contains("\"dispatchPhase\":\"start\"")
            .contains("\"dispatchIndex\":0");
    }

    /**
     * WO-C8-36 (H-1, п.б — реальный дефолт): прод-дефолт флага ВЫКЛЮЧЕН.
     * Проверяется на новом экземпляре без вызова сеттера, то есть ровно то, что
     * задаёт Java-инициализатор поля, — application-test не переопределяет
     * свойство. «Дефолт выключен, иначе старый воркер падает» — это ровно то
     * свойство, на котором держится порядок обновления, выбранный CTO. Смена
     * дефолта на true обязана ронять этот тест.
     *
     * <p><b>Раунд 4 (verifier №5):</b> прежняя формулировка заявляла «на
     * настоящем бине в Spring-контексте», чего здесь нет — бин собирается вручную.
     * САМА связка property→поле (env-имя из compose → {@code @Value} → поле) проверяется
     * в настоящем контексте отдельно: {@code C836ComposeEnvBindingTest}.
     */
    @Test
    void dispatchPhaseStamping_defaultOffOnRealBean() {
        // Новый экземпляр без явного вызова сеттера = прод-дефолт поля.
        ServiceTaskEnqueueServiceImpl fresh = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, new tools.jackson.databind.ObjectMapper(),
            realElementSupport(), mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        assertThat(fresh.isDispatchPhaseStampingEnabled())
            .as("прод-дефолт phased-штампа ВЫКЛЮЧЕН: старый воркер не должен получать "
                + "незнакомые поля до осознанного включения флага")
            .isFalse();
    }

    /**
     * WO-C8-36 (F-2): состояние флага печатается на старте, и уровень лога
     * РАЗНЫЙ для включённого и выключенного состояния.
     *
     * <p>Зачем уровень различается: выключенный флаг = CR-01 выключен (legacy
     * fail-open), и это должно быть видно в обычном логе реплики, а не только в
     * счётчике ignored'ов. Мутация «всегда INFO» или «убрать вызов метода» валит
     * один из двух ассертов; мутация «поменять ветки местами» — оба.
     */
    @Test
    void dispatchPhaseStamping_startupLog_statesLevelPerFlagValue() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        // ListAppender без start() молча отбрасывает всё: AppenderBase.doAppend()
        // проверяет started и уходит в warn. Старт — часть установки (WO-C8-36 F-2).
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ServiceTaskEnqueueServiceImpl.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            ServiceTaskEnqueueServiceImpl on = freshService();
            on.setDispatchPhaseStampingEnabled(true);
            on.logStampStateOnStartup();
            ServiceTaskEnqueueServiceImpl off = freshService();
            off.setDispatchPhaseStampingEnabled(false);
            off.logStampStateOnStartup();

            List<ILoggingEvent> events = appender.list;
            assertThat(events).hasSize(2);
            assertThat(events.get(0).getLevel())
                .as("включённый флаг — обычный INFO, это штатная конфигурация нашего стека")
                .isEqualTo(Level.INFO);
            assertThat(events.get(0).getFormattedMessage())
                .contains("dispatch-phase-stamping=ON")
                .contains("CR-01 exact-match guard active");
            assertThat(events.get(1).getLevel())
                .as("выключенный флаг = CR-01 выключен и путь fail-open — это WARN, "
                    + "чтобы бросалось в глаза в логе реплики (в т.ч. soak-рига "
                    + "docker-compose.multi.yml, где раньше флага не было вовсе)")
                .isEqualTo(Level.WARN);
            assertThat(events.get(1).getFormattedMessage())
                .contains("dispatch-phase-stamping=OFF")
                .contains("LEGACY fail-open path");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    private ServiceTaskEnqueueServiceImpl freshService() {
        return new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, objectMapper, realElementSupport(),
            mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
    }

    private String capturedPayload() {
        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(captor.capture());
        return captor.getValue().getPayload();
    }

    @Test
    void enqueueAfterCommit_listenerInFlight_stampsStartPhaseAndIndex() throws Exception {
        // WO-C8-36 (CR-01, п.1): отправка listener-вызова штампуется фазой start/0 —
        // assert на КОНКРЕТНЫЕ значения штампа (P-67: job "listener-job" сам по
        // себе штамп не доказывает, его проверяет соседний тест выше).
        ServiceTaskEnqueueServiceImpl sut = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, objectMapper, realElementSupport(),
            mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskListenerStamp";

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

        // WO-C8-36 (H-1, п.б): штамп теперь за флагом — тест шалтлит его ВКЛЮЧИТЬ
        // и проверяет то же самое (конкретные значения), что и до флага.
        sut.setDispatchPhaseStampingEnabled(true);
        sut.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<JobDetailModel> detailCaptor = ArgumentCaptor.forClass(JobDetailModel.class);
        verify(objectMapper).writeValueAsString(detailCaptor.capture());
        assertThat(detailCaptor.getValue().getDispatchPhase()).isEqualTo("start");
        assertThat(detailCaptor.getValue().getDispatchIndex()).isEqualTo(0);
    }

    @Test
    void enqueueAfterCommit_listenersDone_stampsRealPhase() throws Exception {
        // WO-C8-36 (CR-01, п.1): отправка реального задания штампуется real/null.
        ServiceTaskEnqueueServiceImpl sut = new ServiceTaskEnqueueServiceImpl(
            dbService, bpmnService, outboxRepository, objectMapper, realElementSupport(),
            mock(ElementListenerPhaseRepository.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTaskRealStamp";

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

        // WO-C8-36 (H-1, п.б): тот же флаг — иначе тест проверял бы путь,
        // который в проде по умолчанию выключен.
        sut.setDispatchPhaseStampingEnabled(true);
        sut.enqueueAfterCommit(serviceTaskId);

        ArgumentCaptor<JobDetailModel> detailCaptor = ArgumentCaptor.forClass(JobDetailModel.class);
        verify(objectMapper).writeValueAsString(detailCaptor.capture());
        assertThat(detailCaptor.getValue().getJob()).isEqualTo("real-job");
        assertThat(detailCaptor.getValue().getDispatchPhase()).isEqualTo("real");
        assertThat(detailCaptor.getValue().getDispatchIndex()).isNull();
    }
}
