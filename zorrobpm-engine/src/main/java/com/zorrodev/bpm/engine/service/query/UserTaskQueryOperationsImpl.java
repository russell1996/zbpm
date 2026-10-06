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
import com.zorrodev.bpm.engine.security.CandidateGroups;
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

    /**
     * WO-IN-2 MEDIUM-3: the candidate filter builds one un-indexed {@code LIKE} per group, so its
     * cost is LINEAR in the number of groups (red-team measurement on 1M rows: 1 group 77 ms,
     * 100 groups 3982 ms, plus a COUNT of the same price). 20 groups is far past anything a real
     * candidate list holds and bounds one request to 20 scans' worth of work; a caller asking about
     * a person with more is told so instead of being served a multi-second query.
     */
    static final int MAX_PERSON_GROUPS = 20;

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
            specifications.add(
                UserTaskRepository.byCandidateGroup(storableGroupName(query.getCandidateGroup())));
        }
        // WO-IN-3: candidateUser перестал быть отказом. До этого значения не существовало ни в
        // одной строке (эскалация E-IN2-1), и CTO решил тогда не притворяться, что фильтр
        // работает, — отказом 400 (вариант B). Вариант (A), нормализованная таблица
        // user_task_candidates, теперь написан: роль USER хранится рядом с GROUP, и оба
        // фильтра читают её одним EXISTS по индексу (kind, candidate, user_task_id).
        if (hasText(query.getCandidateUser())) {
            specifications.add(
                UserTaskRepository.byCandidateUser(storableCandidateUser(query.getCandidateUser())));
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
        }        Specification<UserTaskEntity> all = Specification.allOf(specifications);
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
     *
     * <p>Both guards below run BEFORE the specification is built, so neither a rejected request nor
     * an oversized one ever reaches SQL: MEDIUM-3 bounds how many {@code LIKE}s one request may
     * cost, LOW-5 refuses a group name the column cannot represent.
     */
    private PersonRoles resolveRoles(UUID userId) {
        String username = uiUserRepository.findById(userId)
            .map(UiUserEntity::getUsername)
            .orElse(null);
        Set<String> groups = new LinkedHashSet<>(userGroupRepository.findGroupNamesByUserId(userId));
        if (groups.size() > MAX_PERSON_GROUPS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_PERSON_GROUPS",
                "relatesTo: the person belongs to more than " + MAX_PERSON_GROUPS + " groups",
                Map.of("groups", groups.size(), "max", MAX_PERSON_GROUPS,
                    "relatesTo", userId.toString()));
        }
        return new PersonRoles(username, groups.stream().map(UserTaskQueryOperationsImpl::storableGroupName).toList());
    }

    /**
     * WO-IN-2 LOW-5: a group name is one name — it may not carry the list delimiter.
     *
     * <p>Red-team proof on a live PostgreSQL: with the name {@code a,b} the LIKE pattern became
     * {@code ,a,b,}, which also matched the task holding {@code x,a,b,y} — i.e. the filter handed
     * out tasks of two groups the request never named, and of which the person is not a candidate
     * ({@code AuthorizationService} compares whole names and finds no {@code a,b}). Refusing is
     * the honest answer; guessing which of the two names was meant is not.
     *
     * <p>Unreachable through the REST layer today — nothing in the {@code src/main} trees writes
     * {@code user_group} (there is no group-management endpoint), so this only guards the moment a
     * group UI appears (group-UI, EPIC-EXTERNAL-INTEGRATION).
     *
     * <p>WO-IN-3: тело и код ошибки остались БАЙТ-ВО-БАЙТ прежними — фильтр Groups переехал на
     * таблицу кандидатов, но контракт отказа не менялся (это публичный код ответа).
     */
    private static String storableGroupName(String groupName) {
        String trimmed = groupName.trim();
        if (!CandidateGroups.isStorable(trimmed)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_GROUP_NAME",
                "A group name may not contain the '" + CandidateGroups.DELIMITER + "' list delimiter",
                Map.of("group", groupName));
        }
        return trimmed;
    }

    /**
     * WO-IN-3: то же правило для кандидата-ПОЛЬЗОВАТЕЛЯ, отдельным кодом — контракт группы не
     * трогаем. Причина та же: список кандидатов приходит из BPMN-атрибута через запятую, и имя с
     * запятой в нём не представимо. Молча вернуть пустую выборку здесь нельзя — это был бы ответ
     * «у тебя таких задач нет» на вопрос о существующей задаче.
     */
    private static String storableCandidateUser(String userName) {
        String trimmed = userName.trim();
        if (!CandidateGroups.isStorable(trimmed)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CANDIDATE_USER_NAME",
                "A candidate user name may not contain the '" + CandidateGroups.DELIMITER + "' list delimiter",
                Map.of("candidateUser", userName));
        }
        return trimmed;
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