package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;

import java.util.UUID;

public interface IncidentRuntimeOperations {

    IdDTO resolveIncident(UUID id, ResolveIncidentDTO dto);
}
