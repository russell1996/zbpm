package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.model.BpmnElementStatistics;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserTaskRepository extends JpaRepository<UserTaskEntity, UUID>, JpaSpecificationExecutor<UserTaskEntity> {

    static Specification<UserTaskEntity> byProcessDefinitionId(UUID processDefinitionId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("processDefinitionId"), processDefinitionId);
    }

    static Specification<UserTaskEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<UserTaskEntity> byId(UUID id) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("id"), id);
    }

    /**
     * Filters by the authoritative activity lifecycle (task id == activity id):
     * completed == true -> activity COMPLETED; false -> active (CREATED/IN_PROGRESS).
     * CANCELLED/ERROR tasks are excluded from both.
     */
    static Specification<UserTaskEntity> byCompleted(boolean completed) {
        return (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var act = sub.from(ActivityEntity.class);
            sub.select(act.get("id")).where(act.get("status").in(completed
                ? List.of(ActivityStatus.COMPLETED)
                : List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)));
            return root.get("id").in(sub);
        };
    }

    /** assigned == true -> assignee is set; false -> unassigned (claimable). */
    static Specification<UserTaskEntity> byAssigned(boolean assigned) {
        return (root, query, cb) -> assigned ? cb.isNotNull(root.get("assignee")) : cb.isNull(root.get("assignee"));
    }

    static Specification<UserTaskEntity> byAssignee(String assignee) {
        return (root, query, cb) -> cb.equal(root.get("assignee"), assignee);
    }

    /**
     * WO-IN-2 C0: matches a candidate-group token inside the comma-separated
     * {@code user_tasks.candidate_groups} column.
     *
     * <p>There is NO candidate table and no {@code candidate_users} column in this schema (only
     * {@code candidate_groups varchar(512)}, written by {@code UserTaskHandler.createTaskRow}
     * from {@code zeebe:assignmentDefinition/@candidateGroups}) — so this cannot be an EXISTS
     * over a normalized candidates relation; see escalation E-IN2-1/E-IN2-3. A JOIN is not an
     * option either: it multiplies task rows and breaks pagination.
     *
     * <p>Token-exact on purpose: both sides are wrapped in the {@code ,} delimiter, so group
     * {@code sales} does NOT match a task whose list holds {@code sales-east}. The delimiter is
     * also what keeps the filter consistent with the write path (the engine stores
     * {@code "a,b"}, see {@code DomainEventEmitterTest}); the {@code ", "} variant tolerates a
     * human-written comma list with a space, which {@code AuthorizationService.parseCandidateGroups}
     * already trims away on the authorization side.
     *
     * <p>LIKE metacharacters in the group name are escaped with the same backslash convention as
     * {@code UiUserRepository.byUsernameContains} (WO-SEC-17) — a group literally called
     * {@code sales%} must stay a literal, never a wildcard.
     */
    static Specification<UserTaskEntity> byCandidateGroup(String group) {
        String escaped = escapeLike(group);
        String tight = "%," + escaped + ",%";
        String spaced = "%, " + escaped + ",%";
        return (root, query, cb) -> {
            var padded = cb.concat(cb.concat(",", root.get("candidateGroups")), ",");
            return cb.or(cb.like(padded, tight, '\\'), cb.like(padded, spaced, '\\'));
        };
    }

    static Specification<UserTaskEntity> byBpmnElementId(String bpmnElementId) {
        return (root, query, cb) -> cb.equal(root.get("bpmnElementId"), bpmnElementId);
    }

    static Specification<UserTaskEntity> byFormKey(String formKey) {
        return (root, query, cb) -> cb.equal(root.get("formKey"), formKey);
    }

    /**
     * WO-IN-2 criterion 1: "everything that concerns this person" as ONE query — the task's
     * assignee is this user, OR the task's candidate-group list intersects one of the user's
     * groups. One predicate, not several paged queries merged in the JVM: that is exactly what
     * produced duplicates and gaps before, and a JOIN would multiply task rows and break
     * pagination, so the roles are OR-ed inside a single specification (E-IN2-3).
     *
     * <p>{@code assignee} holds the USERNAME, not the user id
     * ({@code RuntimeOperationSupport.resolvePrincipalId}), and the candidate groups live in the
     * comma-separated {@code candidate_groups} column — see {@link #byCandidateGroup} for the
     * token-exact matching and the LIKE escaping.
     *
     * <p>Fail-closed: with neither a username nor a single group there is nothing this person
     * relates to, and the specification must match NO row rather than fall back to "see all".
     */
    static Specification<UserTaskEntity> relatesTo(String username, Collection<String> groups) {
        return (root, query, cb) -> {
            List<Predicate> roles = new ArrayList<>();
            if (username != null && !username.isBlank()) {
                roles.add(cb.equal(root.get("assignee"), username));
            }
            var padded = cb.concat(cb.concat(",", root.get("candidateGroups")), ",");
            for (String group : groups == null ? List.<String>of() : groups) {
                String escaped = escapeLike(group);
                roles.add(cb.like(padded, "%," + escaped + ",%", '\\'));
                roles.add(cb.like(padded, "%, " + escaped + ",%", '\\'));
            }
            if (roles.isEmpty()) {
                // never `disjunction()` here — an empty OR is TRUE, which would answer
                // "everything" to a person filter that matched nothing.
                return cb.isNull(root.get("id"));
            }
            return cb.or(roles.toArray(Predicate[]::new));
        };
    }

    /**
     * WO-SEC-17 escaping convention: backslash first, then the two LIKE wildcards. Case is left
     * alone on purpose — {@code AuthorizationService.parseCandidateGroups} compares group names
     * case-SENSITIVELY, so a case-insensitive filter here would answer a wider question than the
     * authorization side actually grants.
     */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Modifying
    @Query("UPDATE UserTaskEntity e SET e.completedAt = :completedAt WHERE e.id = :taskId")
    void setCompletedAt(UUID taskId, Instant completedAt);

    @Modifying
    @Query("UPDATE UserTaskEntity e SET e.assignee = :assignee WHERE e.id = :taskId")
    void setAssignee(UUID taskId, String assignee);

    /**
     * Atomic claim: assigns only if currently unassigned. Returns the number of rows updated
     * (1 = claimed, 0 = already claimed by someone else). Closes the read-check-write race:
     * two concurrent claims cannot both succeed, the second sees 0 rows.
     */
    @Modifying
    @Query("UPDATE UserTaskEntity e SET e.assignee = :assignee WHERE e.id = :taskId AND e.assignee IS NULL")
    int claimAssignee(UUID taskId, String assignee);

    List<UserTaskEntity> findByProcessInstanceId(UUID processInstanceId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processDefinitionId = :processDefinitionId AND e.completedAt IS NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findActiveStatsByProcessDefinitionId(UUID processDefinitionId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processDefinitionId = :processDefinitionId AND e.completedAt IS NOT NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findCompletedStatsByProcessDefinitionId(UUID processDefinitionId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processInstanceId = :processInstanceId AND e.completedAt IS NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findStatsByProcessInstanceId(UUID processInstanceId);

    @Query("SELECT MIN(e.createdAt) FROM UserTaskEntity e WHERE e.completedAt IS NULL")
    Instant findOldestOpenCreatedAt();

    List<UserTaskEntity> findByProcessDefinitionId(UUID processDefinitionId);

    List<UserTaskEntity> findByProcessDefinitionIdAndProcessInstanceId(UUID processDefinitionId, UUID processInstanceId);
}
