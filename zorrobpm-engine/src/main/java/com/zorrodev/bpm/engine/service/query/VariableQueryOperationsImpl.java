package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.mapper.VariableMapper;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VariableQueryOperationsImpl implements VariableQueryOperations {

    private final VariableRepository variableRepository;
    private final VariableMapper variableMapper;
    private final QueryPaginationSupport queryPaginationSupport;

    @Override
    public PagedDataDTO<ProcessVariable> findVariables(VariableQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<ProcessVariableEntity>> specifications = new LinkedList<>();
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return queryPaginationSupport.emptyPage(query);
        }
        if (allowedPdIds != null) {
            specifications.add(queryPaginationSupport.processInstanceInAllowedDefinitions(allowedPdIds));
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
        // WO-ENG-14: activity-scoped ioMapping variables must not leak into
        // the process variable list — default is root scope only.
        if (query.getActivityId() != null) {
            specifications.add(VariableRepository.byScopeId(query.getActivityId()));
        } else {
            specifications.add(VariableRepository.byRootScope());
        }
        Specification<ProcessVariableEntity> all = Specification.allOf(specifications);
        return queryPaginationSupport.toDTO(variableRepository.findAll(all, queryPaginationSupport.clampedPage(query.getPageIndex(), query.getPageSize(), Sort.unsorted())), variableMapper::toDTO);
    }
}
