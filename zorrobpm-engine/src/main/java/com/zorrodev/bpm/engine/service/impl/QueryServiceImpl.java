package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ActivityInstance;
import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.service.query.ActivityQueryOperations;
import com.zorrodev.bpm.engine.mapper.IncidentMapper;
import com.zorrodev.bpm.engine.mapper.MessageSubscriptionMapper;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.mapper.TimerJobMapper;
import com.zorrodev.bpm.engine.mapper.ServiceTaskMapper;
import com.zorrodev.bpm.engine.mapper.UserTaskMapper;
import com.zorrodev.bpm.engine.mapper.VariableMapper;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.QueryService;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class QueryServiceImpl implements QueryService {

    /** WO-A-05: maximum allowed page size — prevents DoS via huge queries */
    private static final int MAX_PAGE_SIZE = 200;

    private final DBService dbService;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedJdbc;
    private final ActivityQueryOperations activityQueryOperations;

    private final ServiceTaskMapper serviceTaskMapper;
    private final UserTaskMapper userTaskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final IncidentMapper incidentMapper;
    private final VariableMapper variableMapper;
    private final TimerJobMapper timerJobMapper;
    private final MessageSubscriptionMapper messageSubscriptionMapper;

    private final UserTaskRepository userTaskRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final IncidentRepository incidentRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final VariableRepository variableRepository;
    private final ActivityRepository activityRepository;
    private final TimerJobRepository timerJobRepository;
    private final MessageSubscriptionRepository messageSubscriptionRepository;

    @Override
    public PagedDataDTO<TimerJob> findTimerJobs(TimerJobQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<TimerJobEntity>> specifications = new LinkedList<>();
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return emptyPage(query);
        }
        if (allowedPdIds != null) {
            specifications.add(processInstanceInAllowedDefinitions(allowedPdIds));
        }
        if (query.getId() != null) {
            specifications.add((root, q, cb) -> cb.equal(root.get("id"), query.getId()));
        }
        if (query.getProcessInstanceId() != null) {
            specifications.add(TimerJobRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getFired() != null) {
            specifications.add(TimerJobRepository.byFired(query.getFired()));
        }
        PageRequest page = clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("dueAt").ascending());
        return toDTO(timerJobRepository.findAll(Specification.allOf(specifications), page), timerJobMapper::toDTO);
    }

    @Override
    public PagedDataDTO<MessageSubscription> findMessageSubscriptions(MessageSubscriptionQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<MessageSubscriptionEntity>> specifications = new LinkedList<>();
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return emptyPage(query);
        }
        if (allowedPdIds != null) {
            specifications.add(processInstanceInAllowedDefinitions(allowedPdIds));
        }
        if (query.getId() != null) {
            specifications.add((root, q, cb) -> cb.equal(root.get("id"), query.getId()));
        }
        if (query.getProcessInstanceId() != null) {
            specifications.add(MessageSubscriptionRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getConsumed() != null) {
            specifications.add(MessageSubscriptionRepository.byConsumed(query.getConsumed()));
        }
        PageRequest page = clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("createdAt").descending());
        return toDTO(messageSubscriptionRepository.findAll(Specification.allOf(specifications), page), messageSubscriptionMapper::toDTO);
    }

    @Override
    public List<ActivityInstance> getActivities(UUID processInstanceId) {
        return activityQueryOperations.getActivities(processInstanceId);
    }

    @Override
    public PagedDataDTO<ServiceTask> findServiceTasks(ServiceTaskQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<ServiceTaskEntity>> specifications = new LinkedList<>();
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return emptyPage(query);
        }
        if (allowedPdIds != null) {
            // ServiceTaskEntity has processDefinitionId directly
            specifications.add((root, q, cb) -> root.get("processDefinitionId").in(allowedPdIds));
        }
        if (query.getProcessInstanceId() != null) {
            specifications.add(ServiceTaskRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getId() != null) {
            specifications.add(ServiceTaskRepository.byId(query.getId()));
        }
        if (query.getCompleted() != null) {
            specifications.add(ServiceTaskRepository.byCompleted(query.getCompleted()));
        }
        Specification<ServiceTaskEntity> all = Specification.allOf(specifications);
        PageRequest page = clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("createdAt").descending());
        return toDTOBulk(serviceTaskRepository.findAll(all, page), serviceTaskMapper::toDTOs);
    }

    @Override
    public ServiceTask getServiceTask(UUID id) {
        return serviceTaskMapper.toDTO(serviceTaskRepository.findById(id).orElseThrow());
    }

    @Override
    public PagedDataDTO<UserTask> findUserTasks(UserTaskQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<UserTaskEntity>> specifications = new LinkedList<>();
        // WO-ARCH-1a: default DENY — no memberships → empty results
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return emptyPage(query);
        }
        if (allowedPdIds != null) {
            specifications.add((root, q, cb) -> root.get("processDefinitionId").in(allowedPdIds));
        }
        if (query.getProcessInstanceId() != null) {
            specifications.add(UserTaskRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getId() != null) {
            specifications.add(UserTaskRepository.byId(query.getId()));
        }
        if (query.getCompleted() != null) {
            specifications.add(UserTaskRepository.byCompleted(query.getCompleted()));
        }
        if (query.getAssigned() != null) {
            specifications.add(UserTaskRepository.byAssigned(query.getAssigned()));
        }
        if (query.getAssignee() != null) {
            specifications.add(UserTaskRepository.byAssignee(query.getAssignee()));
        }
        Specification<UserTaskEntity> all = Specification.allOf(specifications);
        PageRequest page = clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("createdAt").descending());
        return toDTOBulk(userTaskRepository.findAll(all, page), userTaskMapper::toDTOs);
    }

    @Override
    public UserTask getUserTask(UUID id) {
        return userTaskMapper.toDTO(userTaskRepository.findById(id).orElseThrow());
    }

    @Override
    public ProcessInstance getProcessInstance(UUID id) {
        return dbService.getProcessInstance(id);
    }

    @Override
    public PagedDataDTO<ProcessInstance> findProcessInstances(ProcessInstanceQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<ProcessInstanceEntity>> specifications = new LinkedList<>();
        // WO-ARCH-1a: default DENY — no memberships → empty results
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return emptyPage(query);
        }
        if (allowedPdIds != null) {
            specifications.add((root, q, cb) -> root.get("processDefinitionId").in(allowedPdIds));
        }
        if (query.getId() != null) {
            specifications.add(ProcessInstanceRepository.byId(query.getId()));
        }
        if (query.getParentProcessInstanceId() != null) {
            specifications.add(ProcessInstanceRepository.byParentProcessInstanceId(query.getParentProcessInstanceId()));
        }
        if (query.getProcessDefinitionId() != null) {
            specifications.add(ProcessInstanceRepository.byProcessDefinitionId(query.getProcessDefinitionId()));
        }
        if (query.getProcessDefinitionKey() != null) {
            specifications.add(ProcessInstanceRepository.byProcessDefinitionKey(query.getProcessDefinitionKey()));
        }
        if (query.getProcessDefinitionVersion() != null) {
            specifications.add(ProcessInstanceRepository.byProcessDefinitionVersion(query.getProcessDefinitionVersion()));
        }
        Specification<ProcessInstanceEntity> all = Specification.allOf(specifications);
        PageRequest page = clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("startedAt").descending());
        return toDTOBulk(processInstanceRepository.findAll(all, page), processInstanceMapper::toDTOs);
    }

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
            return emptyPage(query);
        }
        if (allowedPdIds != null) {
            // IncidentEntity: activityId → activities.processInstanceId → process_instances.process_definition_id
            specifications.add((root, q, cb) -> {
                var piSub = q.subquery(UUID.class);
                var piRoot = piSub.from(com.zorrodev.bpm.engine.entity.ProcessInstanceEntity.class);
                piSub.select(piRoot.get("id"))
                    .where(piRoot.get("processDefinitionId").in(allowedPdIds));
                var actSub = q.subquery(UUID.class);
                var actRoot = actSub.from(com.zorrodev.bpm.engine.entity.ActivityEntity.class);
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
        PageRequest page = clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("createdAt").descending());
        return toDTOBulk(incidentRepository.findAll(all, page),
            entities -> incidentMapper.enrich(entities.stream().map(incidentMapper::toDTO).toList()));
    }

    @Override
    public PagedDataDTO<ProcessVariable> findVariables(VariableQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<ProcessVariableEntity>> specifications = new LinkedList<>();
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return emptyPage(query);
        }
        if (allowedPdIds != null) {
            specifications.add(processInstanceInAllowedDefinitions(allowedPdIds));
        }
        if (query.getProcessInstanceId() != null) {
            specifications.add(VariableRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getName() != null) {
            specifications.add(VariableRepository.byName(query.getName()));
        }
        if (query.getType() != null) {
            specifications.add(VariableRepository.byType(query.getType()));
        }
        if (query.getValue() != null) {
            specifications.add(VariableRepository.byValue(query.getValue()));
        }
        Specification<ProcessVariableEntity> all = Specification.allOf(specifications);
        return toDTO(variableRepository.findAll(all, clampedPage(query.getPageIndex(), query.getPageSize(), Sort.unsorted())), variableMapper::toDTO);
    }

    private <T, S> PagedDataDTO<T> toDTO(Page<S> page, Function<S, T> converter) {
        PagedDataDTO<T> result = new PagedDataDTO<>();
        result.setTotalElements(page.getTotalElements());
        result.setPageIndex(page.getNumber());
        result.setPageSize(page.getSize());
        List<T> data = new ArrayList<>();
        for (S entity : page.getContent()) {
            data.add(converter.apply(entity));
        }
        result.setData(data);
        return result;
    }

    /**
     * WO-PERF-2 (D-01): bulk variant — the mapper batch-loads its related rows (definitions/
     * activities) in ONE extra query for the whole page instead of one per entity.
     */
    private <T, S> PagedDataDTO<T> toDTOBulk(Page<S> page, Function<List<S>, List<T>> bulkConverter) {
        PagedDataDTO<T> result = new PagedDataDTO<>();
        result.setTotalElements(page.getTotalElements());
        result.setPageIndex(page.getNumber());
        result.setPageSize(page.getSize());
        result.setData(bulkConverter.apply(page.getContent()));
        return result;
    }

    /**
     * WO-PERF-2 (D-05): entities that only carry {@code processInstanceId} (TimerJob,
     * MessageSubscription, ProcessVariable) are scoped to the allowed process DEFINITIONS via a
     * single correlated subquery on {@code process_instances}, instead of the caller first
     * materializing every matching instance id into a JVM list (native SQL round-trip) and then
     * building an {@code IN (...)} list from it — two round-trips and, on a large tenant, a large
     * heap-resident id list, for what the database can do as one query with a semi-join. A
     * definition with zero instances yet naturally yields zero matching rows here too, so the
     * separate "piIds.isEmpty() -> emptyPage" short-circuit the old code needed is not required.
     */
    private static <T> Specification<T> processInstanceInAllowedDefinitions(Collection<UUID> allowedPdIds) {
        return (root, query, cb) -> {
            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<ProcessInstanceEntity> piRoot = subquery.from(ProcessInstanceEntity.class);
            subquery.select(piRoot.get("id")).where(piRoot.get("processDefinitionId").in(allowedPdIds));
            return root.<UUID>get("processInstanceId").in(subquery);
        };
    }

    /** WO-A-05: server-side clamp — safety net even if validation annotations are bypassed */
    private static PageRequest clampedPage(Integer pageIndex, Integer pageSize, Sort sort) {
        int page = Math.max(0, pageIndex != null ? pageIndex : 0);
        int size = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize != null ? pageSize : 10));
        return PageRequest.of(page, size, sort);
    }

    /** WO-ARCH-1a: default DENY — empty allowedPdIds → empty page (no memberships = see nothing). */
    private static <T> PagedDataDTO<T> emptyPage(Object query) {
        PagedDataDTO<T> result = new PagedDataDTO<>();
        result.setPageIndex(0);
        result.setPageSize(10);
        result.setTotalElements(0L);
        result.setData(List.of());
        return result;
    }

}
