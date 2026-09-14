package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.model.BpmnElementStatistics;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceTaskRepository extends JpaRepository<ServiceTaskEntity, UUID>, JpaSpecificationExecutor<ServiceTaskEntity> {

    static Specification<ServiceTaskEntity> byProcessDefinitionId(UUID processDefinitionId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("processDefinitionId"), processDefinitionId);
    }

    static Specification<ServiceTaskEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<ServiceTaskEntity> byId(UUID id) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("id"), id);
    }

    /**
     * Filters by the authoritative activity lifecycle (task id == activity id):
     * completed == true -> activity COMPLETED; false -> active (CREATED/IN_PROGRESS).
     * CANCELLED/ERROR tasks are excluded from both (a cancelled task is neither active nor completed).
     */
    static Specification<ServiceTaskEntity> byCompleted(boolean completed) {
        return (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var act = sub.from(ActivityEntity.class);
            sub.select(act.get("id")).where(act.get("status").in(completed
                ? List.of(ActivityStatus.COMPLETED)
                : List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)));
            return root.get("id").in(sub);
        };
    }

    List<ServiceTaskEntity> findByProcessInstanceId(UUID processInstanceId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ServiceTaskEntity e SET e.completedAt = :completedAt WHERE e.id = :id")
    void setCompletedAt(UUID id, Instant completedAt);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM ServiceTaskEntity e WHERE e.processInstanceId = :processInstanceId GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findStatsByProcessInstanceId(UUID processInstanceId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM ServiceTaskEntity e WHERE e.processDefinitionId = :processDefinitionId AND e.completedAt IS NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findActiveStatsByProcessDefinitionId(UUID processDefinitionId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM ServiceTaskEntity e WHERE e.processDefinitionId = :processDefinitionId AND e.completedAt IS NOT NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findCompletedStatsByProcessDefinitionId(UUID processDefinitionId);

    /**
     * WO-REL-31 CR-4: definition key in one join query. The entity carries
     * processDefinitionId directly (set from the instance at creation), so the old
     * serviceTask→instance→definition three-step resolution collapses to one.
     */
    @Query("SELECT pd.key FROM ServiceTaskEntity st JOIN ProcessDefinitionEntity pd ON pd.id = st.processDefinitionId WHERE st.id = :id")
    Optional<String> findDefinitionKeyById(UUID id);
}
