package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.dto.Incident;

import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен Incidents.
 */
public interface IncidentDbOperations {

    UUID createIncident(UUID activityId, String message);

    Incident getIncident(UUID incidentId);

    void completeIncident(UUID incidentId);

    void completeIncidentsByActivityIds(List<UUID> activityIds);

    List<Incident> findOpenIncidentsByActivityIds(List<UUID> activityIds);
}
