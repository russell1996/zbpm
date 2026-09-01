package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.Incident;

import java.util.Collection;
import java.util.UUID;

public interface IncidentQueryOperations {

    Incident getIncident(UUID id);

    /**
     * WO-SEC-43: resolve the processDefinitionId owning an incident (via its
     * activity → process instance), or {@code null} if the incident does not exist.
     */
    UUID resolveIncidentProcessDefinitionId(UUID incidentId);

    PagedDataDTO<Incident> findIncidents(IncidentQuery query, Collection<UUID> allowedPdIds);
}
