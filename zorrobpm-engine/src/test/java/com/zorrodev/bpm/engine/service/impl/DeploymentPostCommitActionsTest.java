package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-5b — unit tests for {@link DeploymentPostCommitActions} (both branches of
 * the synchronization check + best-effort publishing).
 *
 * Note on placement: no-sync tests need synchronization INACTIVE, sync tests need it
 * ACTIVE, so init/clear lives inside each sync test (try/finally), not in
 * {@code @BeforeEach} — a class-level init would break the no-sync tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeploymentPostCommitActionsTest {

    @Mock
    private BpmnService bpmnService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DeploymentPostCommitActions actions;

    @BeforeEach
    void setUp() {
        actions = new DeploymentPostCommitActions(bpmnService, eventPublisher);
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static BpmnProcessDefinitionModel modelWithJobs() {
        BpmnProcessDefinitionModel model = mock(BpmnProcessDefinitionModel.class);
        when(model.getJobTypes()).thenReturn(Set.of("j1"));
        return model;
    }

    // ==================== cacheModelAfterCommit ====================

    @Test
    void cacheModelAfterCommit_noSynchronization_putsImmediately() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        UUID id = UUID.randomUUID();
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();

        actions.cacheModelAfterCommit(id, model);

        verify(bpmnService).addProcessDefinition(id, model);
    }

    @Test
    void cacheModelAfterCommit_activeSynchronization_defersUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            UUID id = UUID.randomUUID();
            BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();

            actions.cacheModelAfterCommit(id, model);

            // Deferred: not called yet...
            verify(bpmnService, never()).addProcessDefinition(any(), any());
            // ...but registered exactly once — firing it performs the put
            List<TransactionSynchronization> syncs =
                TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.get(0).afterCommit();
            verify(bpmnService).addProcessDefinition(id, model);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================== requestJobQueuesAfterCommit ====================

    @Test
    void requestJobQueuesAfterCommit_emptyJobTypes_publishesNothing() {
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();

        actions.requestJobQueuesAfterCommit(model);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void requestJobQueuesAfterCommit_noSynchronization_publishesImmediately() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        actions.requestJobQueuesAfterCommit(modelWithJobs());

        verify(eventPublisher).publishEvent(any(com.zorrodev.bpm.exchange.JobQueuesRequested.class));
    }

    @Test
    void requestJobQueuesAfterCommit_activeSynchronization_defersUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            actions.requestJobQueuesAfterCommit(modelWithJobs());

            verify(eventPublisher, never()).publishEvent(any());
            List<TransactionSynchronization> syncs =
                TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.get(0).afterCommit();
            verify(eventPublisher).publishEvent(any(com.zorrodev.bpm.exchange.JobQueuesRequested.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void requestJobQueuesAfterCommit_publisherThrows_swallowed() {
        doThrow(new RuntimeException("broker down")).when(eventPublisher).publishEvent(any());

        assertThatNoException().isThrownBy(() -> actions.requestJobQueuesAfterCommit(modelWithJobs()));
    }

    @Test
    void requestJobQueuesAfterCommit_announcedJobTypesMatchModel() {
        actions.requestJobQueuesAfterCommit(modelWithJobs());

        var captor = org.mockito.ArgumentCaptor.forClass(com.zorrodev.bpm.exchange.JobQueuesRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getJobTypes()).containsExactly("j1");
    }
}
