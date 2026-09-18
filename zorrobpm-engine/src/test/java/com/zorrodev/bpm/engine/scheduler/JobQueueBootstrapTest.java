package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.exchange.JobQueuesRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-16 criterion #3: after a redeploy the job queues of already-deployed definitions must be
 * announced at startup, instead of appearing only once some process instance reaches a service task.
 * Criterion #5: this is best-effort — nothing here may prevent the application from starting.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobQueueBootstrapTest {

    @Mock private ProcessDefinitionRepository processDefinitionRepository;
    @Mock private BpmnService bpmnService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private JobQueueBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new JobQueueBootstrap(processDefinitionRepository, bpmnService, eventPublisher);
        ReflectionTestUtils.setField(bootstrap, "enabled", true);
    }

    private static ProcessDefinitionEntity definition(UUID id, String key) {
        ProcessDefinitionEntity entity = new ProcessDefinitionEntity();
        entity.setId(id);
        entity.setKey(key);
        entity.setVersion(1);
        return entity;
    }

    private static BpmnProcessDefinitionModel modelWithJob(String job) {
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        BpmnElementModel element = new BpmnElementModel();
        element.setId("svc");
        element.setType(BpmnElementType.SERVICE_TASK);
        BpmnElementExtensionModel extensions = new BpmnElementExtensionModel();
        ServiceTaskExtensionModel serviceTask = new ServiceTaskExtensionModel();
        serviceTask.setJob(job);
        extensions.setServiceTaskExtension(serviceTask);
        element.setExtensions(extensions);
        model.addElement(element);
        return model;
    }

    private void singlePageOf(ProcessDefinitionEntity... definitions) {
        Page<ProcessDefinitionEntity> page = new PageImpl<>(List.of(definitions), PageRequest.of(0, 100), definitions.length);
        when(processDefinitionRepository.findAllLatest(any())).thenReturn(page);
    }

    @Test
    void announcesJobTypesOfDeployedDefinitions() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        singlePageOf(definition(a, "orders"), definition(b, "invoices"));
        when(bpmnService.getProcessDefinitionModelById(a)).thenReturn(modelWithJob("billing"));
        when(bpmnService.getProcessDefinitionModelById(b)).thenReturn(modelWithJob("notify"));

        bootstrap.run(null);

        ArgumentCaptor<JobQueuesRequested> captor = ArgumentCaptor.forClass(JobQueuesRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getJobTypes()).containsExactlyInAnyOrder("billing", "notify");
    }

    @Test
    void deduplicatesJobTypeSharedByDefinitions() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        singlePageOf(definition(a, "orders"), definition(b, "invoices"));
        when(bpmnService.getProcessDefinitionModelById(any())).thenReturn(modelWithJob("billing"));

        bootstrap.run(null);

        ArgumentCaptor<JobQueuesRequested> captor = ArgumentCaptor.forClass(JobQueuesRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getJobTypes()).containsExactly("billing");
    }

    /** One unreadable definition must not cost us the queues of all the others. */
    @Test
    void skipsUnreadableDefinitionButStillAnnouncesTheRest() {
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        singlePageOf(definition(broken, "broken"), definition(healthy, "healthy"));
        when(bpmnService.getProcessDefinitionModelById(broken)).thenThrow(new RuntimeException("bpmn file missing"));
        when(bpmnService.getProcessDefinitionModelById(healthy)).thenReturn(modelWithJob("billing"));

        bootstrap.run(null);

        ArgumentCaptor<JobQueuesRequested> captor = ArgumentCaptor.forClass(JobQueuesRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getJobTypes()).containsExactly("billing");
    }

    @Test
    void publishesNothingWhenNoJobTypesDeployed() {
        UUID id = UUID.randomUUID();
        singlePageOf(definition(id, "orders"));
        when(bpmnService.getProcessDefinitionModelById(id)).thenReturn(new BpmnProcessDefinitionModel());

        bootstrap.run(null);

        verify(eventPublisher, never()).publishEvent(any(JobQueuesRequested.class));
    }

    /** Criterion #5: a failure here must degrade to lazy declaration, never break startup. */
    @Test
    void repositoryFailureDoesNotBreakStartup() {
        when(processDefinitionRepository.findAllLatest(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
        verify(eventPublisher, never()).publishEvent(any(JobQueuesRequested.class));
    }

    @Test
    void publisherFailureDoesNotBreakStartup() {
        UUID id = UUID.randomUUID();
        singlePageOf(definition(id, "orders"));
        when(bpmnService.getProcessDefinitionModelById(id)).thenReturn(modelWithJob("billing"));
        org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
            .when(eventPublisher).publishEvent(any(JobQueuesRequested.class));

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
    }

    @Test
    void disabledFlagSkipsEverything() {
        ReflectionTestUtils.setField(bootstrap, "enabled", false);

        bootstrap.run(null);

        verify(processDefinitionRepository, never()).findAllLatest(any());
        verify(eventPublisher, never()).publishEvent(any(JobQueuesRequested.class));
    }
}
