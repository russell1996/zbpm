package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.model.BpmnElementStatistics;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID>, JpaSpecificationExecutor<IncidentEntity> {

    static Specification<IncidentEntity> byId(UUID id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    // an incident links to its instance only through its activity, so filter via an activity subquery
    static Specification<IncidentEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var activity = subquery.from(ActivityEntity.class);
            subquery.select(activity.get("id"))
                .where(cb.equal(activity.get("processInstanceId"), processInstanceId));
            return root.get("activityId").in(subquery);
        };
    }

    static Specification<IncidentEntity> byBpmnElementId(String bpmnElementId) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var activity = subquery.from(ActivityEntity.class);
            subquery.select(activity.get("id"))
                .where(cb.equal(activity.get("bpmnElementId"), bpmnElementId));
            return root.get("activityId").in(subquery);
        };
    }

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processDefinitionId = :processDefinitionId GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findStatsByProcessDefinitionId(UUID processDefinitionId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processInstanceId = :processInstanceId GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findStatsByProcessInstanceId(UUID processInstanceId);

}
