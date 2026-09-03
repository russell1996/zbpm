package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-5b — unit tests for {@link DeploymentArtifactRegistrar} (one per method:
 * registers when present, skips when absent).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeploymentArtifactRegistrarTest {

    @Mock
    private DBService dbService;

    @Mock
    private ElementSupport elementSupport;

    @Mock
    private ElementArtifactBindingRepository bindingRepository;

    @Mock
    private FormRepository formRepository;

    private DeploymentArtifactRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new DeploymentArtifactRegistrar(dbService, elementSupport, bindingRepository, formRepository);
    }

    private static BpmnElementModel messageStart(String id, String messageName) {
        BpmnElementModel e = new BpmnElementModel();
        e.setId(id);
        e.setType(BpmnElementType.MESSAGE_START_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        MessageEventExtensionModel msg = new MessageEventExtensionModel();
        msg.setMessageName(messageName);
        ext.setMessageEventExtension(msg);
        e.setExtensions(ext);
        return e;
    }

    private static BpmnElementModel signalStart(String id, String signalName) {
        BpmnElementModel e = new BpmnElementModel();
        e.setId(id);
        e.setType(BpmnElementType.SIGNAL_START_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        EventDefinitionExtensionModel sig = new EventDefinitionExtensionModel();
        sig.setName(signalName);
        ext.setEventDefinition(sig);
        e.setExtensions(ext);
        return e;
    }

    private static BpmnElementModel timerStart(String id, TimerEventType type, String expression) {
        BpmnElementModel e = new BpmnElementModel();
        e.setId(id);
        e.setType(BpmnElementType.TIMER_START_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        TimerEventExtensionModel timer = new TimerEventExtensionModel();
        timer.setType(type);
        timer.setExpression(expression);
        ext.setTimerEventExtension(timer);
        e.setExtensions(ext);
        return e;
    }

    private static BpmnProcessDefinitionModel model(BpmnElementModel... elements) {
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        for (BpmnElementModel e : elements) {
            model.addElement(e);
        }
        return model;
    }

    // ==================== message starts ====================

    @Test
    void registerMessageStartSubscriptions_registersEachNamedStart() {
        UUID pdId = UUID.randomUUID();
        BpmnProcessDefinitionModel model = model(messageStart("s1", "m1"), messageStart("s2", "m2"));

        registrar.registerMessageStartSubscriptions("k", pdId, model);

        verify(dbService).deleteMessageStartSubscriptionsByKey("k");
        verify(dbService).createMessageStartSubscription("k", pdId, "s1", "m1");
        verify(dbService).createMessageStartSubscription("k", pdId, "s2", "m2");
    }

    @Test
    void registerMessageStartSubscriptions_emptyModel_touchesNothing() {
        UUID pdId = UUID.randomUUID();

        registrar.registerMessageStartSubscriptions("k", pdId, model());

        verify(dbService, never()).deleteMessageStartSubscriptionsByKey(anyString());
        verify(dbService, never()).createMessageStartSubscription(anyString(), any(), anyString(), anyString());
    }

    @Test
    void registerMessageStartSubscriptions_namelessStart_skipped() {
        UUID pdId = UUID.randomUUID();
        BpmnProcessDefinitionModel model = model(messageStart("s1", null));

        registrar.registerMessageStartSubscriptions("k", pdId, model);

        verify(dbService).deleteMessageStartSubscriptionsByKey("k");
        verify(dbService, never()).createMessageStartSubscription(anyString(), any(), anyString(), anyString());
    }

    // ==================== signal starts ====================

    @Test
    void registerSignalStartSubscriptions_registersEachNamedStart() {
        UUID pdId = UUID.randomUUID();
        BpmnProcessDefinitionModel model = model(signalStart("s1", "sig1"));

        registrar.registerSignalStartSubscriptions("k", pdId, model);

        verify(dbService).deleteSignalStartSubscriptionsByKey("k");
        verify(dbService).createSignalStartSubscription("k", pdId, "s1", "sig1");
    }

    @Test
    void registerSignalStartSubscriptions_emptyModel_touchesNothing() {
        UUID pdId = UUID.randomUUID();

        registrar.registerSignalStartSubscriptions("k", pdId, model());

        verify(dbService, never()).deleteSignalStartSubscriptionsByKey(anyString());
        verify(dbService, never()).createSignalStartSubscription(anyString(), any(), anyString(), anyString());
    }

    // ==================== timer starts ====================

    @Test
    void registerTimerStartJobs_cycleType_persistsRemainingCount() {
        UUID pdId = UUID.randomUUID();
        BpmnProcessDefinitionModel model = model(timerStart("t1", TimerEventType.CYCLE, "R3/PT1H"));
        Instant dueAt = Instant.now().plusSeconds(60);
        when(elementSupport.computeDueAt(any(), eq("t1"))).thenReturn(dueAt);

        registrar.registerTimerStartJobs("k", pdId, model);

        verify(dbService).deleteTimerStartJobsByKey("k");
        // WO-REL-14: R3/... → remainingCount = 3 - 1 = 2 (replicates TimerExpressions.repeatCount)
        verify(dbService).createTimerStartJob(eq("k"), eq(pdId), eq("t1"), eq(dueAt), eq(2));
    }

    @Test
    void registerTimerStartJobs_durationType_remainingCountNull() {
        UUID pdId = UUID.randomUUID();
        BpmnProcessDefinitionModel model = model(timerStart("t1", TimerEventType.DURATION, "PT5M"));
        Instant dueAt = Instant.now().plusSeconds(300);
        when(elementSupport.computeDueAt(any(), eq("t1"))).thenReturn(dueAt);

        registrar.registerTimerStartJobs("k", pdId, model);

        verify(dbService).createTimerStartJob(eq("k"), eq(pdId), eq("t1"), eq(dueAt), eq(null));
    }

    @Test
    void registerTimerStartJobs_emptyModel_touchesNothing() {
        UUID pdId = UUID.randomUUID();

        registrar.registerTimerStartJobs("k", pdId, model());

        verify(dbService, never()).deleteTimerStartJobsByKey(anyString());
        verify(dbService, never()).createTimerStartJob(anyString(), any(), anyString(), any(), any());
    }

    // ==================== carry-forward ====================

    @Test
    void carryForwardBindings_copiesWithRepinToCurrentArtifactVersion() {
        ProcessDefinitionEntity newPd = new ProcessDefinitionEntity();
        newPd.setId(UUID.randomUUID());
        newPd.setVersion(2);
        ElementArtifactBindingEntity old = new ElementArtifactBindingEntity();
        old.setProcessDefinitionId(UUID.randomUUID());
        old.setElementId("startEvent");
        old.setArtifactKey("art");
        old.setArtifactVersion(1);
        when(bindingRepository.findByKeyAndOldVersion("k", 1)).thenReturn(List.of(old));
        FormEntity current = new FormEntity();
        current.setFormKey("art");
        current.setVersion(5);
        when(formRepository.findTopByFormKeyOrderByVersionDesc("art")).thenReturn(Optional.of(current));

        registrar.carryForwardBindings("k", 1, newPd);

        ArgumentCaptor<ElementArtifactBindingEntity> captor =
            ArgumentCaptor.forClass(ElementArtifactBindingEntity.class);
        verify(bindingRepository).save(captor.capture());
        ElementArtifactBindingEntity saved = captor.getValue();
        assertThat(saved.getProcessDefinitionId()).isEqualTo(newPd.getId());
        assertThat(saved.getProcessDefinitionVersion()).isEqualTo(2);
        assertThat(saved.getElementId()).isEqualTo("startEvent");
        assertThat(saved.getArtifactKey()).isEqualTo("art");
        // Re-pinned to the CURRENT artifact version, not the old one
        assertThat(saved.getArtifactVersion()).isEqualTo(5);
    }

    @Test
    void carryForwardBindings_artifactGone_nothingCopied() {
        ProcessDefinitionEntity newPd = new ProcessDefinitionEntity();
        newPd.setId(UUID.randomUUID());
        newPd.setVersion(2);
        ElementArtifactBindingEntity old = new ElementArtifactBindingEntity();
        old.setProcessDefinitionId(UUID.randomUUID());
        old.setElementId("startEvent");
        old.setArtifactKey("gone");
        old.setArtifactVersion(1);
        when(bindingRepository.findByKeyAndOldVersion("k", 1)).thenReturn(List.of(old));
        when(formRepository.findTopByFormKeyOrderByVersionDesc("gone")).thenReturn(Optional.empty());

        registrar.carryForwardBindings("k", 1, newPd);

        verify(bindingRepository, never()).save(any(ElementArtifactBindingEntity.class));
    }

    @Test
    void carryForwardBindings_noOldBindings_nothingCopied() {
        ProcessDefinitionEntity newPd = new ProcessDefinitionEntity();
        newPd.setId(UUID.randomUUID());
        newPd.setVersion(2);
        when(bindingRepository.findByKeyAndOldVersion("k", 1)).thenReturn(List.of());

        registrar.carryForwardBindings("k", 1, newPd);

        verify(bindingRepository, never()).save(any(ElementArtifactBindingEntity.class));
        verify(formRepository, never()).findTopByFormKeyOrderByVersionDesc(anyString());
    }
}
