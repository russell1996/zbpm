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

    /**
     * WO-C8-49: закрывает открытые инциденты ВСЕХ активностей инстанса (включая
     * инциденты на уже COMPLETED-активностях — WO-REL-28 оставляет такие) тем же
     * путём, что {@link #completeIncidentsByActivityIds}, — часть единой
     * финализации инстанса (см. {@code DBService.finalizeFinishedInstance}).
     */
    void completeOpenIncidentsByProcessInstanceId(UUID processInstanceId);

    List<Incident> findOpenIncidentsByActivityIds(List<UUID> activityIds);
}
