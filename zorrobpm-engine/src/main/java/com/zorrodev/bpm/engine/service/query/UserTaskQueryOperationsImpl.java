package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.UserTaskMapper;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
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
public class UserTaskQueryOperationsImpl implements UserTaskQueryOperations {

    private final UserTaskRepository userTaskRepository;
    private final UserTaskMapper userTaskMapper;
    private final QueryPaginationSupport queryPaginationSupport;

    @Override
    public PagedDataDTO<UserTask> findUserTasks(UserTaskQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<UserTaskEntity>> specifications = new LinkedList<>();
        // WO-ARCH-1a: default DENY — no memberships → empty results
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return queryPaginationSupport.emptyPage(query);
        }
        // WO-C8-21r2: the creating-phase filter is GONE with the marker row (step 4) — no
        // user_tasks row exists until the task is really created, so every reader is
        // correct by construction and no filter has to remember the phase.
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
        PageRequest page = queryPaginationSupport.clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("createdAt").descending());
        return queryPaginationSupport.toDTOBulk(userTaskRepository.findAll(all, page), userTaskMapper::toDTOs);
    }

    @Override
    public UserTask getUserTask(UUID id) {
        // WO-C8-21r2: the mid-phase guard is gone with the marker row — a missing row
        // orElseThrows by itself (a task that was never created is simply not there).
        return userTaskMapper.toDTO(userTaskRepository.findById(id).orElseThrow());
    }
}
