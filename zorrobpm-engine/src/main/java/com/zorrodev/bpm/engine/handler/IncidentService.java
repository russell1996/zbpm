package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Collaborator extracted from ActivityServiceImpl (WO-AUD-25).
 * Owns incident lifecycle: raising incidents on element failures and resolving them.
 * TokenExecutor is passed as a parameter (port) to avoid circular bean dependencies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentService {

    private final DBService dbService;

    /**
     * Parks the token at the failing element as an incident instead of propagating the exception
     * (which would roll back the whole process transaction). Marks the element's activity ERROR
     * and records an incident the operator can later resolve via {@link #resolveIncident}.
     * <p>
     * WO-REL-40 (B-6): if no activity exists for the failed element (e.g. the element
     * failed before its activity was created), the fallback creates one first — a
     * silently dropped incident leaves the token parked with no trace for the
     * operator (precedes MultiInstanceExecutor.raiseCardinalityIncident).
     */
    public void raiseIncident(UUID processInstanceId, UUID tokenId, BpmnElementModel element, Exception e) {
        String message = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        log.error("{}/{}: Incident at {} {}: {}", processInstanceId, tokenId, element.getType(), element.getId(), message, e);

        // the handler creates the element's activity before doing the risky work, so it is visible
        // to this same-transaction query; pick the most recent one for this token + element
        List<Activity> activities = dbService.getActivitiesByTokenAndBpmnElementId(tokenId, element.getId());
        UUID activityId;
        if (activities.isEmpty()) {
            // WO-REL-40 (B-6) fallback: no activity to park the incident on — create one
            // so the failure stays visible instead of a log-only trace.
            log.warn("{}/{}: No activity found for failed element {}, creating fallback activity to record the incident",
                processInstanceId, tokenId, element.getId());
            activityId = dbService.createActivity(processInstanceId, tokenId, element);
        } else {
            activityId = activities.get(activities.size() - 1).getId();
        }
        dbService.errorActivity(activityId);
        dbService.createIncident(activityId, message);
    }

    /**
     * Resolves an incident: re-executes the element that failed. Guards against double-execution
     * (idempotency + active-activity check) and auto-closes stale incidents for the same token/element.
     * <p>
     * WO-ENG-19: the completed-work guard below is visit-scoped, not "any COMPLETED ever".
     * The old check ({@code hasCompletedActivityOnTokenAndElement}) matched a COMPLETED activity
     * from a PREVIOUS visit of the same token/element (BPMN loop back to an already-executed
     * element) and closed the new incident without re-executing the currently failed attempt —
     * the process stayed parked while looking "resolved". Now only a completion of the CURRENT
     * visit counts: the incident's own activity completed by another path (WO-REL-28), or a
     * replacement activity created at/after the parked one that already completed.
     */
    public void resolveIncident(UUID incidentId, List<ProcessVariable> variables, TokenExecutor executor) {
        Incident incident = dbService.getIncident(incidentId);
        Activity activity = dbService.getActivity(incident.getActivityId());
        dbService.lockProcessInstance(activity.getProcessInstanceId());

        // WO-ENG-19: re-read AFTER the lock. A concurrent resolve serialised on the same
        // instance lock may have completed this incident (or completed/cancelled its activity)
        // between our first read and the lock acquisition — deciding on pre-lock state would
        // re-execute twice.
        incident = dbService.getIncident(incidentId);
        activity = dbService.getActivity(incident.getActivityId());

        // Idempotency: already resolved → no-op
        if (incident.getCompletedAt() != null) {
            log.info("{}/{}: Incident {} already resolved, no-op", activity.getProcessInstanceId(), activity.getToken(), incidentId);
            return;
        }

        // Guard: if an active activity already exists for this (token, element), close incident without re-execution
        if (dbService.hasActiveActivityOnTokenAndElement(activity.getToken(), activity.getBpmnElementId())) {
            log.info("{}/{}: Active activity already exists for element {}, closing incident {} without re-execution",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), incidentId);
            dbService.completeIncident(incidentId);
            return;
        }

        // WO-ENG-19 (replaces the old "any COMPLETED on token/element" check): the work of the
        // CURRENT visit is done only if (a) the incident's own activity has since been COMPLETED
        // by another path (WO-REL-28: operator manual completion flips this same row), or (b) a
        // replacement activity for the same (token, element), created at/after the parked one,
        // already completed. A COMPLETED row created BEFORE the parked activity belongs to a
        // previous visit (loop re-entry) and must NOT suppress re-execution of the failed attempt.
        if (activity.getStatus() == ActivityStatus.COMPLETED) {
            log.info("{}/{}: Incident activity {} itself is COMPLETED (finished by another path), closing incident {} without re-execution",
                activity.getProcessInstanceId(), activity.getToken(), activity.getId(), incidentId);
            dbService.completeIncident(incidentId);
            return;
        }
        List<Activity> sameElementActivities = dbService.getActivitiesByTokenAndBpmnElementId(activity.getToken(), activity.getBpmnElementId());
        final UUID parkedActivityId = activity.getId();
        final var parkedCreatedAt = activity.getCreatedAt();
        boolean replacementCompleted = sameElementActivities.stream()
            .filter(a -> !a.getId().equals(parkedActivityId))
            .filter(a -> a.getStatus() == ActivityStatus.COMPLETED)
            .anyMatch(a -> !a.getCreatedAt().isBefore(parkedCreatedAt));
        if (replacementCompleted) {
            log.info("{}/{}: A replacement activity for element {} already completed, closing incident {} without re-execution",
                activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId(), incidentId);
            dbService.completeIncident(incidentId);
            return;
        }

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(activity.getProcessInstanceId(), variables);
        }

        // Auto-close stale incidents for this (token, element) before re-execution
        List<UUID> staleActivityIds = sameElementActivities.stream().map(Activity::getId).toList();
        dbService.completeIncidentsByActivityIds(staleActivityIds);

        // Cancel the parked (ERROR) activity before re-executing: re-execution creates a fresh active
        // activity, and cancelling the old one ensures a late/duplicate worker completion of its in-flight
        // job is ignored (completeServiceTask only acts on active tasks) instead of advancing the token again.
        dbService.cancelActivity(incident.getActivityId());

        log.info("{}/{}: Resolving incident {} at {}: re-executing", activity.getProcessInstanceId(), activity.getToken(), incidentId, activity.getBpmnElementId());
        executor.execute(activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId());
    }
}
