package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.entity.ElementListenerPhaseEntity;
import com.zorrodev.bpm.engine.entity.ListenerPhase;
import com.zorrodev.bpm.engine.repository.ElementListenerPhaseRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WO-C8-25 (part B of finding A-5): start-listener phases of gateway/event elements.
 *
 * <p>Service tasks (WO-C8-11) and user tasks (WO-C8-21) own their phase state on their
 * rows; gateways and events have no row before their handler runs, so the phase lives in
 * its own table ({@code element_listener_phase}), keyed by
 * (process, token, element). The phase row id doubles as the listener jobs'
 * {@code serviceTaskId}, so worker completions route back with no activity involved.
 *
 * <p>Owns open/reset/advance/finish/fail of the phase. The dispatcher
 * ({@code ActivityServiceImpl.execute}) only asks {@link #tryParkPhase} and returns;
 * {@code CompletionService} routes phase completions/failures here and resumes via the
 * {@code TokenExecutor} it already receives (no new dependencies, no cycles).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElementListenerPhaseService {

    private final ElementListenerPhaseRepository phaseRepository;
    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ElementSupport elementSupport;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;

    /** Resume data for a finished phase — the dispatcher re-enters from these ids. */
    public record Resume(boolean finished, UUID processInstanceId, UUID tokenId, String bpmnElementId) {
    }

    /**
     * Parks the token before the element's handler runs: opens (or restarts, on incident
     * re-entry) the phase and dispatches the first listener job. Returns true when parked
     * (the caller must return without calling the handler).
     *
     * <p>Service/user tasks never park here (they own C8-11/C8-21 state); elements without
     * a handler never park (there would be no resume target).
     */
    public boolean tryParkPhase(UUID processInstanceId, UUID tokenId, BpmnElementModel element,
            boolean hasHandler) {
        if (element.getType() == BpmnElementType.SERVICE_TASK
            || element.getType() == BpmnElementType.USER_TASK) {
            return false;
        }
        List<ListenerModel> listeners = elementSupport.elementStartListeners(element);
        if (listeners.isEmpty() || !hasHandler) {
            return false;
        }
        Optional<ElementListenerPhaseEntity> existing = phaseRepository
            .findByProcessInstanceIdAndTokenIdAndBpmnElementId(processInstanceId, tokenId, element.getId());
        ElementListenerPhaseEntity phase;
        if (existing.isEmpty()) {
            phase = new ElementListenerPhaseEntity();
            phase.setId(UUID.randomUUID());
            phase.setProcessInstanceId(processInstanceId);
            phase.setTokenId(tokenId);
            phase.setBpmnElementId(element.getId());
            phase.setPhase(ListenerPhase.START);
            phase.setCreatedAt(Instant.now());
        } else if (ListenerPhase.DONE.equals(existing.get().getPhase())) {
            // Incident in the HANDLER after the listeners already ran: resolve re-executes
            // the element — drop the done marker and proceed to the handler WITHOUT
            // re-dispatching listeners (they already ran for this token+element). The row
            // is gone, so a later redelivery falls back to the pre-existing loud path.
            phaseRepository.delete(existing.get());
            return false;
        } else {
            // Incident DURING the phase: resolve re-executes the element from scratch while
            // the stale phase is still there — restart listeners from 0 with a fresh budget
            // (the engine resolve model — same as every other element kind; Camunda's "only
            // the failed listener" nuance does not exist in this engine).
            phase = existing.get();
        }
        phase.setPhase(ListenerPhase.START);
        phase.setListenerIndex(0);
        phase.setRetriesRemaining(elementSupport.listenerBudget(listeners.get(0)));
        phaseRepository.save(phase);
        log.info("{}/{}: Parking {} for {} start listener(s), phase {}: {}/{}", processInstanceId, tokenId,
            element.getType(), listeners.size(), phase.getId(), processInstanceId, element.getId());
        serviceTaskEnqueueService.enqueuePhaseListener(phase.getId());
        return true;
    }

    /**
     * Completes one listener job of a phase. Empty = not a phase job (caller takes the
     * existing path). Otherwise advances (re-dispatches, {@code finished=false}) or, after
     * the last listener, marks the phase done and returns resume data ({@code finished=true})
     * — the caller re-enters the dispatcher, which runs the handler as if the token just
     * arrived. A completion for an already-done phase (redelivered job) is ignored
     * gracefully instead of failing — the broker is at-least-once.
     */
    public Optional<Resume> completePhaseListener(UUID carrierId, List<ProcessVariable> variables) {
        Optional<ElementListenerPhaseEntity> found = phaseRepository.findById(carrierId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ElementListenerPhaseEntity phase = found.get();
        UUID processInstanceId = phase.getProcessInstanceId();
        if (ListenerPhase.DONE.equals(phase.getPhase())) {
            log.info("{}/{}: Element listener job of finished phase {} already consumed, ignoring: {}/{}",
                processInstanceId, phase.getTokenId(), carrierId, processInstanceId, phase.getBpmnElementId());
            return Optional.of(new Resume(false, processInstanceId, phase.getTokenId(), phase.getBpmnElementId()));
        }
        List<ListenerModel> listeners = listenersOf(phase);
        Integer index = phase.getListenerIndex();
        if (listeners.isEmpty() || index == null || index < 0 || index >= listeners.size()) {
            // Model shrank mid-phase (redeploy): fail-open into normal execution (C8-11
            // spirit) rather than stranding — the element was asked to run, so run it.
            // Marked done (not deleted) so a redelivered completion ignores gracefully.
            phase.setPhase(ListenerPhase.DONE);
            phase.setListenerIndex(null);
            phase.setRetriesRemaining(null);
            phaseRepository.save(phase);
            log.info("{}/{}: Out-of-bounds element-listener index, resuming fail-open: {}/{}",
                processInstanceId, phase.getTokenId(), carrierId, phase.getBpmnElementId());
            return Optional.of(new Resume(true, processInstanceId, phase.getTokenId(), phase.getBpmnElementId()));
        }
        dbService.setVariables(processInstanceId, variables);
        if (index + 1 < listeners.size()) {
            phase.setListenerIndex(index + 1);
            phase.setRetriesRemaining(elementSupport.listenerBudget(listeners.get(index + 1)));
            phaseRepository.save(phase);
            serviceTaskEnqueueService.enqueuePhaseListener(carrierId);
            log.info("{}/{}: Completing element listener {} of {}: {}/{}", processInstanceId, phase.getTokenId(),
                index, phase.getBpmnElementId(), carrierId, phase.getBpmnElementId());
            return Optional.of(new Resume(false, processInstanceId, phase.getTokenId(), phase.getBpmnElementId()));
        }
        phase.setPhase(ListenerPhase.DONE);
        phase.setListenerIndex(null);
        phase.setRetriesRemaining(null);
        phaseRepository.save(phase);
        log.info("{}/{}: Last element listener done, resuming: {}/{}", processInstanceId, phase.getTokenId(),
            carrierId, phase.getBpmnElementId());
        return Optional.of(new Resume(true, processInstanceId, phase.getTokenId(), phase.getBpmnElementId()));
    }

    /**
     * Fails one listener job of a phase. False = not a phase job (caller takes the existing
     * path). Otherwise draws from the durable per-listener budget: redispatch while budget
     * remains, else parks a parked ERROR activity + incident and closes the phase (the
     * incident owns the token from here; resolve re-executes and re-opens the phase fresh).
     */
    public boolean failPhaseListener(UUID carrierId, String errorMessage, Integer retries) {
        Optional<ElementListenerPhaseEntity> found = phaseRepository.findById(carrierId);
        if (found.isEmpty()) {
            return false;
        }
        ElementListenerPhaseEntity phase = found.get();
        UUID processInstanceId = phase.getProcessInstanceId();
        if (ListenerPhase.DONE.equals(phase.getPhase())) {
            // Stale failure for already-consumed work (at-least-once broker): the listeners
            // finished, failing now would raise a false incident — ignore gracefully.
            log.info("{}/{}: Element listener failure for finished phase {} ignored: {}/{}",
                processInstanceId, phase.getTokenId(), carrierId, processInstanceId, phase.getBpmnElementId());
            return true;
        }
        String message = (errorMessage == null || errorMessage.isBlank()) ? "Element listener failed" : errorMessage;
        int remaining;
        if (retries != null) {
            // Camunda failJob semantics: explicit value sets the budget (0 → incident now).
            phase.setRetriesRemaining(retries);
            remaining = retries;
        } else {
            Integer budget = phase.getRetriesRemaining();
            remaining = (budget == null ? 0 : budget) - 1;
            phase.setRetriesRemaining(remaining);
        }
        if (remaining > 0) {
            phaseRepository.save(phase);
            log.info("{}/{}: Element listener {} failed ({} retries left), re-dispatching: {}",
                processInstanceId, phase.getTokenId(), phase.getBpmnElementId(), remaining, message);
            serviceTaskEnqueueService.enqueuePhaseListener(carrierId);
            return true;
        }
        // Budget exhausted: incidents need an activity row (same constraint as
        // ActivityServiceImpl.execute's unsupported-element path) — park one, then record.
        BpmnElementModel element = elementOf(phase);
        UUID activityId = dbService.createActivity(processInstanceId, phase.getTokenId(), element);
        dbService.errorActivity(activityId);
        dbService.createIncident(activityId, message);
        phaseRepository.delete(phase);
        log.info("{}/{}: Element listener {} failed, retries exhausted — raising incident: {}",
            processInstanceId, phase.getTokenId(), phase.getBpmnElementId(), message);
        return true;
    }

    private List<ListenerModel> listenersOf(ElementListenerPhaseEntity phase) {
        return elementSupport.elementStartListeners(elementOf(phase));
    }

    private BpmnElementModel elementOf(ElementListenerPhaseEntity phase) {
        ProcessInstance pi = dbService.getProcessInstance(phase.getProcessInstanceId());
        return bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId())
            .getElement(phase.getBpmnElementId());
    }
}
