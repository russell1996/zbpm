package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1j: домен Incidents — реализация.
 * Перенесено 1:1 из DBServiceImpl (5 методов).
 */
@Service
@RequiredArgsConstructor
public class IncidentDbOperationsImpl implements IncidentDbOperations {

    private final IncidentRepository incidentRepository;
    private final BpmMetrics bpmMetrics;
    private final ActivityRepository activityRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final DomainEventEmitter domainEventEmitter;

    @Override
    public List<Incident> findOpenIncidentsByActivityIds(List<UUID> activityIds) {
        return incidentRepository.findByActivityIdInAndCompletedAtIsNull(activityIds).stream()
            .map(entity -> {
                Incident i = new Incident();
                i.setId(entity.getId());
                i.setActivityId(entity.getActivityId());
                i.setMessage(entity.getMessage());
                i.setCreatedAt(entity.getCreatedAt());
                i.setCompletedAt(entity.getCompletedAt());
                return i;
            })
            .toList();
    }

    @Override
    public void completeIncidentsByActivityIds(List<UUID> activityIds) {
        List<IncidentEntity> open = incidentRepository.findByActivityIdInAndCompletedAtIsNull(activityIds);
        Instant now = Instant.now();
        for (IncidentEntity entity : open) {
            entity.setCompletedAt(now);
        }
        incidentRepository.saveAll(open);
        // WO-OBS-1: stuck gauge follows open incidents exactly (closed count, not list size).
        for (int i = 0; i < open.size(); i++) {
            bpmMetrics.decrementStuckTokens();
        }
    }

    @Override
    public UUID createIncident(UUID activityId, String message) {
        ActivityEntity activityEntity = activityRepository.findById(activityId).orElseThrow();
        UUID id = UUID.randomUUID();

        IncidentEntity entity = new IncidentEntity();
        entity.setId(id);
        entity.setActivityId(activityId);
        entity.setCreatedAt(Instant.now());
        entity.setMessage(message);
        incidentRepository.save(entity);
        // WO-OBS-1: single choke for every incident path — the engine's failure signal.
        bpmMetrics.processFailed();
        bpmMetrics.incrementStuckTokens();

        ProcessInstanceEntity pi = processInstanceRepository.findById(activityEntity.getProcessInstanceId()).orElseThrow();
        // WO-EVT-9: service tasks carry their stable job id in the event data.
        String job = serviceTaskRepository.findById(activityId).map(ServiceTaskEntity::getJob).orElse(null);
        domainEventEmitter.emitIncidentRaised(activityEntity.getProcessInstanceId(), pi.getProcessDefinitionId(), activityEntity.getBpmnElementId(), id, message, job);

        return id;
    }

    @Override
    public Incident getIncident(UUID incidentId) {
        IncidentEntity entity = incidentRepository.findById(incidentId).orElseThrow();
        Incident incident = new Incident();
        incident.setId(entity.getId());
        incident.setActivityId(entity.getActivityId());
        incident.setMessage(entity.getMessage());
        incident.setCreatedAt(entity.getCreatedAt());
        incident.setCompletedAt(entity.getCompletedAt());
        return incident;
    }

    @Override
    public void completeIncident(UUID incidentId) {
        IncidentEntity entity = incidentRepository.findById(incidentId).orElseThrow();
        // WO-OBS-1: exact pairing — a re-completed incident must not double-decrement.
        boolean wasOpen = entity.getCompletedAt() == null;
        entity.setCompletedAt(Instant.now());
        incidentRepository.save(entity);
        if (wasOpen) {
            bpmMetrics.decrementStuckTokens();
        }

        ActivityEntity activity = activityRepository.findById(entity.getActivityId()).orElseThrow();
        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        // WO-EVT-9: service tasks carry their stable job id in the event data.
        String job = serviceTaskRepository.findById(activity.getId()).map(ServiceTaskEntity::getJob).orElse(null);
        domainEventEmitter.emitIncidentResolved(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), incidentId, job);
    }
}
