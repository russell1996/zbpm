package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.UserTaskMapper;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserTaskQueryOperationsImpl implements UserTaskQueryOperations {

    /**
     * WO-IN-2 criterion 2: the ONLY user-task columns a caller may sort by. A white list, not a
     * blacklist — an unknown field is a 400, never a string spliced into an ORDER BY. The keys
     * are entity attribute names (JPA path), and every one of them is a real column of
     * {@code user_tasks} (checked against the live schema, see the report).
     */
    private static final Set<String> SORTABLE_FIELDS = Set.of(
        "createdAt", "completedAt", "priority", "dueDate", "followUpDate",
        "assignee", "bpmnElementId", "formKey", "id");

    private final UserTaskRepository userTaskRepository;
    private final UserTaskMapper userTaskMapper;
    private final QueryPaginationSupport queryPaginationSupport;
    /** WO-IN-2 criterion 1: relatesTo names a user id; the assignee column holds the USERNAME. */
    private final UiUserRepository uiUserRepository;
    private final UserGroupRepository userGroupRepository;

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
        // WO-IN-2 C0: the declared candidate filters were never applied here (only completed/
        // assigned/assignee had conditions), so ?candidateGroup=X silently answered with EVERY
        // task of the caller. null/blank = "no filter" — the same rule as jobType in
        // ServiceTaskQueryOperationsImpl (WO-IN-1), so an absent parameter keeps the old page.
        if (hasText(query.getCandidateGroup())) {
            specifications.add(UserTaskRepository.byCandidateGroup(query.getCandidateGroup().trim()));
        }
        // WO-IN-2 criterion 3
        if (hasText(query.getBpmnElementId())) {
            specifications.add(UserTaskRepository.byBpmnElementId(query.getBpmnElementId().trim()));
        }
        if (hasText(query.getFormKey())) {
            specifications.add(UserTaskRepository.byFormKey(query.getFormKey().trim()));
        }
        // WO-IN-2 criterion 1: one query, roles OR-ed inside one specification.
        if (query.getRelatesTo() != null) {
            PersonRoles roles = resolveRoles(query.getRelatesTo());
            if (roles.isEmpty()) {
                // the id belongs to nobody we can name or group — fail closed, never "see all"
                return queryPaginationSupport.emptyPage(query);
            }
            specifications.add(UserTaskRepository.relatesTo(roles.username(), roles.groups()));
        }
        Specification<UserTaskEntity> all = Specification.allOf(specifications);
        PageRequest page = queryPaginationSupport.clampedPage(
            query.getPageIndex(), query.getPageSize(), resolveSort(query));
        return queryPaginationSupport.toDTOBulk(userTaskRepository.findAll(all, page), userTaskMapper::toDTOs);
    }

    /**
     * WO-IN-2 criterion 2. Default stays {@code createdAt DESC} — that is the order the endpoint
     * has always answered with — but it is now ALWAYS terminated by {@code id} (same direction),
     * so two pages of the same query cannot hand the same row twice or skip one when
     * {@code createdAt} ties. Absent {@code sortBy}: no change at all apart from that tie-breaker.
     */
    private Sort resolveSort(UserTaskQuery query) {
        Sort.Direction direction = resolveDirection(query.getSortOrder());
        String field = hasText(query.getSortBy()) ? query.getSortBy().trim() : "createdAt";
        if (!SORTABLE_FIELDS.contains(field)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SORT_FIELD",
                "Unsupported user-task sort field: " + field,
                Map.of("field", field, "supported", SORTABLE_FIELDS.stream().sorted().toList()));
        }
        return Sort.by(direction, field).and(Sort.by(direction, "id"));
    }

    private Sort.Direction resolveDirection(String sortOrder) {
        if (!hasText(sortOrder)) {
            return Sort.Direction.DESC;
        }
        return switch (sortOrder.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SORT_ORDER",
                "Unsupported user-task sort order: " + sortOrder,
                Map.of("sortOrder", sortOrder));
        };
    }

    /**
     * WO-IN-2 criterion 1: turns a user id into the two things a task row can carry about that
     * person — the username written into {@code assignee} (there is NO
     * {@code user_tasks.candidate_users} column in this schema, so the candidate-USER role has
     * no data to match; escalation E-IN2-1) and the group names from {@code user_group}.
     *
     * <p>Who may ASK about which user is NOT decided here: {@code allowedPdIds} still scopes the
     * rows, and the resource layer authorizes the VALUE of {@code relatesTo} before this runs
     * ({@code QueryResource.getUserTasks}) — the same trust model as {@code allowedPdIds} itself.
     */
    private PersonRoles resolveRoles(UUID userId) {
        String username = uiUserRepository.findById(userId)
            .map(UiUserEntity::getUsername)
            .orElse(null);
        Set<String> groups = new LinkedHashSet<>(userGroupRepository.findGroupNamesByUserId(userId));
        return new PersonRoles(username, List.copyOf(groups));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** username may be null (unknown user); groups may be empty. */
    private record PersonRoles(String username, List<String> groups) {
        boolean isEmpty() {
            return (username == null || username.isBlank()) && groups.isEmpty();
        }
    }

    @Override
    public UserTask getUserTask(UUID id) {
        // WO-C8-21r2: the mid-phase guard is gone with the marker row — a missing row
        // orElseThrows by itself (a task that was never created is simply not there).
        return userTaskMapper.toDTO(userTaskRepository.findById(id).orElseThrow());
    }
}