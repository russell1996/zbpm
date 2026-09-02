package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
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
public class ProcessInstanceQueryOperationsImpl implements ProcessInstanceQueryOperations {

    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessInstanceMapper processInstanceMapper;
    private final QueryPaginationSupport queryPaginationSupport;
    private final DBService dbService;

    @Override
    public ProcessInstance getProcessInstance(UUID id) {
        return dbService.getProcessInstance(id);
    }

    @Override
    public PagedDataDTO<ProcessInstance> findProcessInstances(ProcessInstanceQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<ProcessInstanceEntity>> specifications = new LinkedList<>();
        // WO-ARCH-1a: default DENY — no memberships → empty results
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return queryPaginationSupport.emptyPage(query);
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
        PageRequest page = queryPaginationSupport.clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("startedAt").descending());
        return queryPaginationSupport.toDTOBulk(processInstanceRepository.findAll(all, page), processInstanceMapper::toDTOs);
    }
}
