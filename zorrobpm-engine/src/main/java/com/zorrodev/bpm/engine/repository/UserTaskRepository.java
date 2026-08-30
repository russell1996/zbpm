package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.model.BpmnElementStatistics;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateType;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
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

    static Specification<UserTaskEntity> byBpmnElementId(String bpmnElementId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("bpmnElementId"), bpmnElementId);
    }

    static Specification<UserTaskEntity> byFormKey(String formKey) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("formKey"), formKey);
    }

    static Specification<UserTaskEntity> byAssignee(String assignee) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("assignee"), assignee);
    }

    static Specification<UserTaskEntity> byAssigned(boolean assigned) {
        return (root, query, criteriaBuilder) -> assigned
            ? criteriaBuilder.isNotNull(root.get("assignee"))
            : criteriaBuilder.isNull(root.get("assignee"));
    }

    static Specification<UserTaskEntity> byCompleted(boolean completed) {
        return (root, query, criteriaBuilder) -> completed
            ? criteriaBuilder.isNotNull(root.get("completedAt"))
            : criteriaBuilder.and(
                criteriaBuilder.isNull(root.get("completedAt")),
                criteriaBuilder.isNull(root.get("canceledAt")));
    }

    static Specification<UserTaskEntity> byCandidateGroup(String group) {
        return byCandidate(UserTaskCandidateType.GROUP, List.of(group));
    }

    static Specification<UserTaskEntity> byCandidateGroups(Collection<String> groups) {
        return byCandidate(UserTaskCandidateType.GROUP, groups);
    }

    static Specification<UserTaskEntity> byCandidateUser(String user) {
        return byCandidate(UserTaskCandidateType.USER, List.of(user));
    }

    /**
     * Matches nothing. Used where a condition has no subject to compare against, so that the
     * surrounding OR keeps its shape without matching every row.
     */
    static Specification<UserTaskEntity> none() {
        return (root, query, criteriaBuilder) -> criteriaBuilder.disjunction();
    }

    /**
     * EXISTS rather than a join on purpose: a join to the candidates table multiplies task rows,
     * which breaks both the total count and pagination.
     */
    private static Specification<UserTaskEntity> byCandidate(UserTaskCandidateType type, Collection<String> values) {
        if (values.isEmpty()) {
            return none();
        }
        return (root, query, criteriaBuilder) -> {
            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<UserTaskCandidateEntity> candidate = subquery.from(UserTaskCandidateEntity.class);
            subquery.select(candidate.get("taskId")).where(
                criteriaBuilder.equal(candidate.get("taskId"), root.get("id")),
                criteriaBuilder.equal(candidate.get("candidateType"), type),
                candidate.get("candidateValue").in(values));
            return criteriaBuilder.exists(subquery);
        };
    }

    @Modifying
    @Query("UPDATE UserTaskEntity e SET e.completedAt = :completedAt WHERE e.id = :taskId")
    void setCompletedAt(UUID taskId, Instant completedAt);

    @Modifying
    @Query("UPDATE UserTaskEntity e SET e.canceledAt = :canceledAt WHERE e.id = :taskId")
    void setCanceledAt(UUID taskId, Instant canceledAt);

    List<UserTaskEntity> findByProcessInstanceId(UUID processInstanceId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processDefinitionId = :processDefinitionId AND e.completedAt IS NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findActiveStatsByProcessDefinitionId(UUID processDefinitionId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processDefinitionId = :processDefinitionId AND e.completedAt IS NOT NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findCompletedStatsByProcessDefinitionId(UUID processDefinitionId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processInstanceId = :processInstanceId AND e.completedAt IS NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findStatsByProcessInstanceId(UUID processInstanceId);

    List<UserTaskEntity> findByProcessDefinitionId(UUID processDefinitionId);

    List<UserTaskEntity> findByProcessDefinitionIdAndProcessInstanceId(UUID processDefinitionId, UUID processInstanceId);
}
