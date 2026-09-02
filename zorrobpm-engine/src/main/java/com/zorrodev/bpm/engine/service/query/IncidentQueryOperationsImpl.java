package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.mapper.IncidentMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IncidentQueryOperationsImpl implements IncidentQueryOperations {

    private final IncidentRepository incidentRepository;
    private final IncidentMapper incidentMapper;
    private final ActivityRepository activityRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final QueryPaginationSupport queryPaginationSupport;
    private final DBService dbService;

    @Override
    public Incident getIncident(UUID id) {
        Incident incident = dbService.getIncident(id);
        return incidentMapper.enrich(List.of(incident)).get(0);
    }

    @Override
    public UUID resolveIncidentProcessDefinitionId(UUID incidentId) {
        return incidentRepository.findById(incidentId)
            .map(IncidentEntity::getActivityId)
            .flatMap(activityRepository::findById)
            .map(ActivityEntity::getProcessInstanceId)
            .flatMap(processInstanceRepository::findById)
            .map(ProcessInstanceEntity::getProcessDefinitionId)
            .orElse(null);
    }

    @Override
    public PagedDataDTO<Incident> findIncidents(IncidentQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<IncidentEntity>> specifications = new LinkedList<>();
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return queryPaginationSupport.emptyPage(query);
        }
        if (allowedPdIds != null) {
            // IncidentEntity: activityId → activities.processInstanceId → process_instances.process_definition_id
            specifications.add((root, q, cb) -> {
                var piSub = q.subquery(UUID.class);
                var piRoot = piSub.from(ProcessInstanceEntity.class);
                piSub.select(piRoot.get("id"))
                    .where(piRoot.get("processDefinitionId").in(allowedPdIds));
                var actSub = q.subquery(UUID.class);
                var actRoot = actSub.from(ActivityEntity.class);
                actSub.select(actRoot.get("id"))
                    .where(actRoot.get("processInstanceId").in(piSub));
                return root.get("activityId").in(actSub);
            });
        }
        if (query.getId() != null) {
            specifications.add(IncidentRepository.byId(query.getId()));
        }
        if (query.getProcessInstanceId() != null) {
            specifications.add(IncidentRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getBpmnElementId() != null) {
            specifications.add(IncidentRepository.byBpmnElementId(query.getBpmnElementId()));
        }
        if (query.getProcessDefinitionId() != null) {
            specifications.add(IncidentRepository.byProcessDefinitionId(query.getProcessDefinitionId()));
        }
        if (query.getProcessDefinitionKey() != null) {
            specifications.add(IncidentRepository.byProcessDefinitionKey(query.getProcessDefinitionKey()));
        }
        if (query.getProcessDefinitionVersion() != null) {
            specifications.add(IncidentRepository.byProcessDefinitionVersion(query.getProcessDefinitionVersion()));
        }
        if (query.getResolved() != null) {
            specifications.add(IncidentRepository.byResolved(query.getResolved()));
        }
        Specification<IncidentEntity> all = Specification.allOf(specifications);
        PageRequest page = queryPaginationSupport.clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("createdAt").descending());
        return queryPaginationSupport.toDTOBulk(incidentRepository.findAll(all, page),
            entities -> incidentMapper.enrich(entities.stream().map(incidentMapper::toDTO).toList()));
    }
}
