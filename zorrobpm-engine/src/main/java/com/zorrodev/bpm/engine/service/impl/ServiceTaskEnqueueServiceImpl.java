package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.engine.tracing.TracingSupport;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Enqueues service tasks via the transactional outbox pattern.
 * Instead of publishing events after commit (which can lose messages when MQ is down),
 * we INSERT into the outbox table within the current transaction.
 * A separate OutboxPollerService publishes entries to MQ.
 */
@Slf4j
@Profile("!test")
@Service
@RequiredArgsConstructor
public class ServiceTaskEnqueueServiceImpl implements ServiceTaskEnqueueService {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ElementSupport elementSupport;
    private final com.zorrodev.bpm.engine.repository.ElementListenerPhaseRepository phaseRepository;
    private final TracingSupport tracing;

    @Transactional
    @Override
    public void enqueueAfterCommit(UUID serviceTaskId) {
        Activity activity = dbService.getActivity(serviceTaskId);
        ProcessInstance pi = dbService.getProcessInstance(activity.getProcessInstanceId());
        UUID processDefinitionId = pi.getProcessDefinitionId();
        UUID processInstanceId = activity.getProcessInstanceId();
        String bpmnElementId = activity.getBpmnElementId();

        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnElementModel element = bpmn.getElement(bpmnElementId);
        // WO-REL-18: null-safe job extraction — defense in depth if deploy validation was bypassed
        String job = Optional.ofNullable(element.getExtensions())
            .map(ext -> ext.getServiceTaskExtension())
            .map(ext -> ext.getJob())
            .orElse(null);

        // WO-C8-7r2: headers declared on the in-flight listener itself (null unless a
        // listener job is dispatched below) — merged over the element headers at the end.
        Map<String, String> listenerHeaders = null;

        // WO-C8-21: while a creating listener is in flight, the dispatched job is the
        // listener's. Read ONLY for elements that declare creating listeners (user tasks),
        // so every pre-existing path never touches the new read. A user task has no "real"
        // job of its own — a corrupt index cannot fail open into one, so it parks with an
        // incident instead (the operator resolves; the phase row stays intact).
        List<ListenerModel> creatingListeners = elementSupport.userTaskCreatingListeners(element);
        Integer pendingCreating = creatingListeners.isEmpty() ? null : dbService.getPendingCreatingListenerIndex(serviceTaskId);
        if (pendingCreating != null && pendingCreating >= 0 && pendingCreating < creatingListeners.size()) {
            job = creatingListeners.get(pendingCreating).jobType();
            listenerHeaders = creatingListeners.get(pendingCreating).headers();
        } else if (pendingCreating != null) {
            log.warn("User task {} has out-of-bounds pendingCreatingListenerIndex {} ({} creating listeners) — raising incident",
                serviceTaskId, pendingCreating, creatingListeners.size());
            dbService.createIncident(
                serviceTaskId,
                "User task '" + bpmnElementId + "' has out-of-bounds creating listener index — fix the process model");
            return;
        }

        // WO-C8-24: while a completing listener is in flight, the dispatched job is the
        // listener's. Same discipline as the creating branch above (read-only for declaring
        // elements; no real job to fail open into — corrupt index parks with an incident).
        List<ListenerModel> completingListeners = elementSupport.userTaskCompletingListeners(element);
        Integer pendingCompleting = completingListeners.isEmpty() ? null : dbService.getPendingCompletingListenerIndex(serviceTaskId);
        if (pendingCompleting != null && pendingCompleting >= 0 && pendingCompleting < completingListeners.size()) {
            job = completingListeners.get(pendingCompleting).jobType();
            listenerHeaders = completingListeners.get(pendingCompleting).headers();
        } else if (pendingCompleting != null) {
            log.warn("User task {} has out-of-bounds pendingCompletingListenerIndex {} ({} completing listeners) — raising incident",
                serviceTaskId, pendingCompleting, completingListeners.size());
            dbService.createIncident(
                serviceTaskId,
                "User task '" + bpmnElementId + "' has out-of-bounds completing listener index — fix the process model");
            return;
        }

        // WO-C8-28: in-flight assigning listener's job wins (first-valid-wins in
        // lifecycle order; phases never overlap — see open sites). Details: WO-C8-28.md.
        List<ListenerModel> assigningListeners = elementSupport.userTaskAssigningListeners(element);
        Integer pendingAssigning = assigningListeners.isEmpty() ? null : dbService.getPendingAssigningListenerIndex(serviceTaskId);
        if (pendingAssigning != null && pendingAssigning >= 0 && pendingAssigning < assigningListeners.size()) {
            job = assigningListeners.get(pendingAssigning).jobType();
            listenerHeaders = assigningListeners.get(pendingAssigning).headers();
        } else if (pendingAssigning != null) {
            log.warn("User task {} has out-of-bounds pendingAssigningListenerIndex {} ({} assigning listeners) — raising incident",
                serviceTaskId, pendingAssigning, assigningListeners.size());
            dbService.createIncident(
                serviceTaskId,
                "User task '" + bpmnElementId + "' has out-of-bounds assigning listener index — fix the process model");
            return;
        }

        // WO-C8-28: while an updating listener is in flight, the dispatched job is the
        // listener's. Same discipline as above.
        List<ListenerModel> updatingListeners = elementSupport.userTaskUpdatingListeners(element);
        Integer pendingUpdating = updatingListeners.isEmpty() ? null : dbService.getPendingUpdatingListenerIndex(serviceTaskId);
        if (pendingUpdating != null && pendingUpdating >= 0 && pendingUpdating < updatingListeners.size()) {
            job = updatingListeners.get(pendingUpdating).jobType();
            listenerHeaders = updatingListeners.get(pendingUpdating).headers();
        } else if (pendingUpdating != null) {
            log.warn("User task {} has out-of-bounds pendingUpdatingListenerIndex {} ({} updating listeners) — raising incident",
                serviceTaskId, pendingUpdating, updatingListeners.size());
            dbService.createIncident(
                serviceTaskId,
                "User task '" + bpmnElementId + "' has out-of-bounds updating listener index — fix the process model");
            return;
        }

        // WO-C8-28: while a canceling listener is in flight, the dispatched job is the
        // listener's. Same discipline as above — the activity is CANCELLED, so there
        // is no real job to fail open into either; corrupt index parks with an incident.
        List<ListenerModel> cancelingListeners = elementSupport.userTaskCancelingListeners(element);
        Integer pendingCanceling = cancelingListeners.isEmpty() ? null : dbService.getPendingCancelingListenerIndex(serviceTaskId);
        if (pendingCanceling != null && pendingCanceling >= 0 && pendingCanceling < cancelingListeners.size()) {
            job = cancelingListeners.get(pendingCanceling).jobType();
            listenerHeaders = cancelingListeners.get(pendingCanceling).headers();
        } else if (pendingCanceling != null) {
            log.warn("User task {} has out-of-bounds pendingCancelingListenerIndex {} ({} canceling listeners) — raising incident",
                serviceTaskId, pendingCanceling, cancelingListeners.size());
            dbService.createIncident(
                serviceTaskId,
                "User task '" + bpmnElementId + "' has out-of-bounds canceling listener index — fix the process model");
            return;
        }

        // WO-C8-11: while a start listener is in flight, the dispatched job is the listener's,
        // not the real one. The index is read ONLY for elements that declare listeners, so the
        // common path (and all pre-existing tests with mock DBService) never touches the new read.
        List<ListenerModel> startListeners = elementSupport.serviceTaskStartListeners(element);
        Integer pendingStart = startListeners.isEmpty() ? null : dbService.getServiceTaskPendingListenerIndex(serviceTaskId);
        if (pendingStart != null && pendingStart >= 0 && pendingStart < startListeners.size()) {
            job = startListeners.get(pendingStart).jobType();
            listenerHeaders = startListeners.get(pendingStart).headers();
        } else if (pendingStart != null) {
            log.warn("Service task {} has out-of-bounds pendingListenerIndex {} ({} start listeners) — dispatching real job",
                serviceTaskId, pendingStart, startListeners.size());
        }

        // WO-C8-11b: end-listener dispatch, same index discipline. Explicit precedence (the code
        // does not silently rely on the construction invariant "phases never overlap"): an
        // in-flight start listener wins; otherwise the end phase owns dispatch when its index
        // is valid. Corrupt start index skips the end phase (fail-open to the real job — the next
        // completion re-enters the end logic and recovers, no strand).
        List<ListenerModel> endListeners = elementSupport.serviceTaskEndListeners(element);
        if (!endListeners.isEmpty() && pendingStart == null) {
            Integer pendingEnd = dbService.getServiceTaskPendingEndListenerIndex(serviceTaskId);
            if (pendingEnd != null && pendingEnd >= 0 && pendingEnd < endListeners.size()) {
                job = endListeners.get(pendingEnd).jobType();
                listenerHeaders = endListeners.get(pendingEnd).headers();
            } else if (pendingEnd != null) {
                log.warn("Service task {} has out-of-bounds pendingEndListenerIndex {} ({} end listeners) — dispatching real job",
                    serviceTaskId, pendingEnd, endListeners.size());
            }
        }

        // WO-C8-7: null-safe headers extraction — headers are optional, most tasks carry none.
        Map<String, String> taskHeaders = Optional.ofNullable(element.getExtensions())
            .map(ext -> ext.getServiceTaskExtension())
            .map(ext -> ext.getTaskHeaders())
            .orElse(null);

        // WO-C8-7r2: a listener job carries the listener's own headers merged over the
        // element's — listener wins on key conflict, per the docs. Plain jobs keep the
        // element headers as-is (null when absent, as before).
        taskHeaders = mergeListenerHeaders(taskHeaders, listenerHeaders);

        // WO-C8-9: null-safe priority resolution — FEEL→Integer, null when absent or broken.
        Integer priority = elementSupport.resolvePriority(processInstanceId, element);

        if (job == null || job.isBlank()) {
            log.error("Service task {} (element {}) has no job definition — creating incident", serviceTaskId, bpmnElementId);
            dbService.createIncident(
                serviceTaskId,
                "Service task '" + bpmnElementId + "' has no assigned job — fix the process model");
            return;
        }

        Map<String, ProcessVariable> variables = toJobVariables(dbService.getVariables(processInstanceId, serviceTaskId));

        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(serviceTaskId);
        detail.setProcessDefinitionId(processDefinitionId);
        detail.setProcessInstanceId(processInstanceId);
        detail.setServiceTaskKey(bpmnElementId);
        detail.setJob(job);
        detail.setVariables(variables);
        detail.setTaskHeaders(taskHeaders);
        detail.setPriority(priority);

        writeOutboxEntry(serviceTaskId, detail);
    }

    /**
     * WO-C8-25: dispatches the in-flight listener job of an element-listener phase
     * (gateways/events). Same outbox/job protocol as {@link #enqueueAfterCommit}, resolved
     * from the phase row — no activity row exists by design (criterion 4), so this method
     * never touches one. Called only with a live phase (park/advance/fail-redispatch).
     */
    @Transactional
    @Override
    public void enqueuePhaseListener(UUID phaseId) {
        com.zorrodev.bpm.engine.entity.ElementListenerPhaseEntity phase =
            phaseRepository.findById(phaseId).orElseThrow(() ->
                new IllegalStateException("No element-listener phase for " + phaseId));
        UUID processInstanceId = phase.getProcessInstanceId();
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());
        BpmnElementModel element = bpmn.getElement(phase.getBpmnElementId());

        List<ListenerModel> listeners = elementSupport.elementStartListeners(element);
        Integer index = phase.getListenerIndex();
        if (listeners.isEmpty() || index == null || index < 0 || index >= listeners.size()) {
            // Unreachable by construction (index is set only to valid values in the same
            // transaction chain) — loud fail-closed, never a silent wrong job.
            throw new IllegalStateException("Element-listener phase " + phaseId + " has corrupt index " + index);
        }
        ListenerModel listener = listeners.get(index);

        // No activity scope exists for phase jobs — root variables only (same result as a
        // merged view over an empty scope; local scopes cannot exist before execution).
        Map<String, ProcessVariable> variables = toJobVariables(dbService.getVariables(processInstanceId));
        Map<String, String> taskHeaders = mergeListenerHeaders(null, listener.headers());
        Integer priority = elementSupport.resolvePriority(processInstanceId, element);

        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(phaseId);
        detail.setProcessDefinitionId(pi.getProcessDefinitionId());
        detail.setProcessInstanceId(processInstanceId);
        detail.setServiceTaskKey(phase.getBpmnElementId());
        detail.setJob(listener.jobType());
        detail.setVariables(variables);
        detail.setTaskHeaders(taskHeaders);
        detail.setPriority(priority);

        writeOutboxEntry(phaseId, detail);
    }

    /**
     * WO-C8-7r2 extraction (extended in WO-C8-25): a listener job carries the listener's
     * own headers merged over the element's — listener wins on key conflict, per the docs.
     */
    private Map<String, String> mergeListenerHeaders(Map<String, String> taskHeaders, Map<String, String> listenerHeaders) {
        if (listenerHeaders == null || listenerHeaders.isEmpty()) {
            return taskHeaders;
        }
        Map<String, String> merged = taskHeaders == null ? new LinkedHashMap<>() : new LinkedHashMap<>(taskHeaders);
        merged.putAll(listenerHeaders);
        return merged;
    }

    /** Shared variables mapping (single + phase paths carry the same variable shape). */
    private Map<String, ProcessVariable> toJobVariables(List<com.zorrodev.bpm.contract.model.ProcessVariable> candidated) {
        return candidated.stream()
            // WO-DIFF-3 (#5): engine-internal MI bookkeeping never reaches
            // the worker-visible job payload (same exclusion as the variables
            // API — the join still reads the row from storage directly).
            .filter(pv -> pv.getName() == null || !pv.getName().startsWith("_mi_batch_"))
            .collect(Collectors.toMap(com.zorrodev.bpm.contract.model.ProcessVariable::getName, pv -> {
                ProcessVariable v = new ProcessVariable();
                v.setName(pv.getName());
                v.setValue(pv.getValue());
                v.setType(pv.getType().toString());
                return v;
            }));
    }

    /** Shared outbox write (single + batch paths carry the same entry shape). */
    private void writeOutboxEntry(UUID serviceTaskId, JobDetailModel detail) {
        try {
            OutboxEntry entry = new OutboxEntry();
            entry.setId(UUID.randomUUID());
            // WO-REL-12 R-01: producer knows the type — no payload guessing downstream
            entry.setKind(com.zorrodev.bpm.engine.entity.OutboxKind.SERVICE_TASK);
            entry.setPayload(objectMapper.writeValueAsString(detail));
            entry.setCreatedAt(Instant.now());
            entry.setPublished(false);
            // WO-OBS-8: persist the enqueue-time traceparent so OutboxBatchProcessor
            // can continue the trace across the @Scheduled gap (null when untraced).
            entry.setTraceParent(tracing.captureTraceParent());
            outboxRepository.save(entry);
            log.info("Enqueued service task {} to outbox", serviceTaskId);
        } catch (Exception e) {
            log.error("Failed to serialize service task {} for outbox", serviceTaskId, e);
            throw new RuntimeException("Failed to enqueue service task", e);
        }
    }
}
