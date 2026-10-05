package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.mapper.ServiceTaskMapper;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
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
public class ServiceTaskQueryOperationsImpl implements ServiceTaskQueryOperations {

    private final ServiceTaskRepository serviceTaskRepository;
    private final ServiceTaskMapper serviceTaskMapper;
    private final QueryPaginationSupport queryPaginationSupport;

    @Override
    public PagedDataDTO<ServiceTask> findServiceTasks(ServiceTaskQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<ServiceTaskEntity>> specifications = new LinkedList<>();
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return queryPaginationSupport.emptyPage(query);
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
        // WO-IN-1: jobType was declared in the DTO (and sent by the client resolver) but never
        // applied here, so ?jobType=X silently returned every task. null/blank means "no filter":
        // a request without the parameter must keep returning the same page as before.
        if (query.getJobType() != null && !query.getJobType().isBlank()) {
            specifications.add(ServiceTaskRepository.byJobType(query.getJobType().trim()));
        }
        if (query.getCompleted() != null) {
            specifications.add(ServiceTaskRepository.byCompleted(query.getCompleted()));
        }
        Specification<ServiceTaskEntity> all = Specification.allOf(specifications);
        PageRequest page = queryPaginationSupport.clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("createdAt").descending());
        return queryPaginationSupport.toDTOBulk(serviceTaskRepository.findAll(all, page), serviceTaskMapper::toDTOs);
    }

    @Override
    public ServiceTask getServiceTask(UUID id) {
        return serviceTaskMapper.toDTO(serviceTaskRepository.findById(id).orElseThrow());
    }
}
