package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.model.BpmnElementStatistics;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    /** resolved == true -> incident is closed (completedAt set); false -> still open. */
    static Specification<IncidentEntity> byResolved(boolean resolved) {
        return (root, query, cb) -> resolved ? cb.isNotNull(root.get("completedAt")) : cb.isNull(root.get("completedAt"));
    }

    static Specification<IncidentEntity> byProcessDefinitionId(UUID processDefinitionId) {
        return (root, query, cb) -> {
            var actSub = query.subquery(UUID.class);
            var act = actSub.from(ActivityEntity.class);
            var piSub = query.subquery(UUID.class);
            var pi = piSub.from(ProcessInstanceEntity.class);
            piSub.select(pi.get("id")).where(cb.equal(pi.get("processDefinitionId"), processDefinitionId));
            actSub.select(act.get("id")).where(act.get("processInstanceId").in(piSub));
            return root.get("activityId").in(actSub);
        };
    }

    static Specification<IncidentEntity> byProcessDefinitionKey(String key) {
        return (root, query, cb) -> {
            var pdSub = query.subquery(UUID.class);
            var pd = pdSub.from(ProcessDefinitionEntity.class);
            pdSub.select(pd.get("id")).where(cb.equal(pd.get("key"), key));
            var piSub = query.subquery(UUID.class);
            var pi = piSub.from(ProcessInstanceEntity.class);
            piSub.select(pi.get("id")).where(pi.get("processDefinitionId").in(pdSub));
            var actSub = query.subquery(UUID.class);
            var act = actSub.from(ActivityEntity.class);
            actSub.select(act.get("id")).where(act.get("processInstanceId").in(piSub));
            return root.get("activityId").in(actSub);
        };
    }

    static Specification<IncidentEntity> byProcessDefinitionVersion(Integer version) {
        return (root, query, cb) -> {
            var pdSub = query.subquery(UUID.class);
            var pd = pdSub.from(ProcessDefinitionEntity.class);
            pdSub.select(pd.get("id")).where(cb.equal(pd.get("version"), version));
            var piSub = query.subquery(UUID.class);
            var pi = piSub.from(ProcessInstanceEntity.class);
            piSub.select(pi.get("id")).where(pi.get("processDefinitionId").in(pdSub));
            var actSub = query.subquery(UUID.class);
            var act = actSub.from(ActivityEntity.class);
            actSub.select(act.get("id")).where(act.get("processInstanceId").in(piSub));
            return root.get("activityId").in(actSub);
        };
    }

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processDefinitionId = :processDefinitionId GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findStatsByProcessDefinitionId(UUID processDefinitionId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processInstanceId = :processInstanceId GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findStatsByProcessInstanceId(UUID processInstanceId);

    List<IncidentEntity> findByActivityIdInAndCompletedAtIsNull(Collection<UUID> activityIds);

    /**
     * WO-REL-31 CR-4: definition key in one join query (incident→activity→instance→definition)
     * instead of four two-step resolutions. Empty when any link is missing — same branches
     * the old chain used to skip.
     */
    @Query("""
        SELECT pd.key FROM IncidentEntity i
        JOIN ActivityEntity a ON a.id = i.activityId
        JOIN ProcessInstanceEntity pi ON pi.id = a.processInstanceId
        JOIN ProcessDefinitionEntity pd ON pd.id = pi.processDefinitionId
        WHERE i.id = :id
        """)
    Optional<String> findDefinitionKeyById(UUID id);
}
