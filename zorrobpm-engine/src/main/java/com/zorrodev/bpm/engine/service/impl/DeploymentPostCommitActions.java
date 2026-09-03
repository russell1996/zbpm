package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * WO-DEBT-5b — post-commit effects cluster extracted byte-for-byte from
 * {@code ProcessDefinitionServiceImpl} (model cache fill, job-queue announcement).
 * Add-only foundation: the original still calls its own copies; delegation happens
 * in Phase 5c. Visibility private → public where stated, bodies and javadoc
 * otherwise verbatim.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeploymentPostCommitActions {

    private final BpmnService bpmnService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * WO-REL-15: fills the in-memory model cache only after the surrounding deployment
     * transaction has COMMITTED — never inside the tx (a rolled-back deployment must not
     * leave a cached model that does not exist in the database). registerSynchronization
     * defers the cache put to afterCommit, which is not invoked on rollback. Without an
     * active transaction synchronization (e.g. unit tests running the TransactionTemplate
     * callback directly) the cache is filled immediately.
     */
    public void cacheModelAfterCommit(UUID processDefinitionId, BpmnProcessDefinitionModel model) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bpmnService.addProcessDefinition(processDefinitionId, model);
                }
            });
        } else {
            bpmnService.addProcessDefinition(processDefinitionId, model);
        }
    }

    /**
     * WO-REL-16: announces this definition's job types so the messaging layer can declare their
     * queues now, instead of lazily on the first message actually sent to them (a job type that
     * had never run yet simply had no queue on the broker).
     *
     * <p>Deferred to afterCommit for the same reason as {@link #cacheModelAfterCommit} — a
     * rolled-back deployment must not announce queues for a definition that does not exist — and
     * so that a broker problem cannot fail the deployment transaction. Publishing is best-effort:
     * the listener is expected to swallow its own broker errors, but the try/catch here guarantees
     * that even a listener that throws cannot break a deployment that has already committed. The
     * lazy declare on first send stays as the fallback.
     */
    public void requestJobQueuesAfterCommit(BpmnProcessDefinitionModel model) {
        java.util.Set<String> jobTypes = model.getJobTypes();
        if (jobTypes.isEmpty()) return;

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishJobQueuesRequested(jobTypes);
                }
            });
        } else {
            publishJobQueuesRequested(jobTypes);
        }
    }

    private void publishJobQueuesRequested(java.util.Set<String> jobTypes) {
        try {
            eventPublisher.publishEvent(new com.zorrodev.bpm.exchange.JobQueuesRequested(jobTypes));
        } catch (Exception e) {
            log.warn("WO-REL-16: could not announce job queues {} — they will be declared lazily "
                + "on the first message instead", jobTypes, e);
        }
    }
}
