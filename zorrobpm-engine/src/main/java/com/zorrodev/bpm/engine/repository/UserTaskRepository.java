package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.model.BpmnElementStatistics;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
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
