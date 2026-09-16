package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VariableRepository extends JpaRepository<ProcessVariableEntity, UUID>, JpaSpecificationExecutor<ProcessVariableEntity> {

    static Specification<ProcessVariableEntity> byProcessInstanceId(UUID processInstanceId) {
        return ((root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("processInstanceId"), processInstanceId));
    }

    static Specification<ProcessVariableEntity> byName(String name) {
        return ((root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("name"), name));
    }

    static Specification<ProcessVariableEntity> byType(ProcessVariableType type) {
        return ((root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("type"), type));
    }

    static Specification<ProcessVariableEntity> byValue(String value) {
        return ((root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("value"), value));
    }

    /** WO-ENG-14: root scope only (process-instance variables, not activity-local ones). */
    static Specification<ProcessVariableEntity> byRootScope() {
        return ((root, query, criteriaBuilder) -> criteriaBuilder.isNull(root.get("scopeId")));
    }

    /** WO-ENG-14: variables of one activity scope (e.g. its ioMapping inputs). */
    static Specification<ProcessVariableEntity> byScopeId(UUID scopeId) {
        return ((root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("scopeId"), scopeId));
    }

    boolean existsByNameAndProcessInstanceId(String name, UUID processInstanceId);

    Optional<ProcessVariableEntity> findByNameAndProcessInstanceId(String name, UUID processInstanceId);

    List<ProcessVariableEntity> findByProcessInstanceId(UUID processInstanceId);

    @Modifying
    @Query("UPDATE ProcessVariableEntity pve SET pve.type = :type, pve.textValue = :textValue WHERE pve.processInstanceId = :processInstanceId AND pve.name = :name")
    void updateVariableTextValueAndType(UUID processInstanceId, String name, ProcessVariableType type, String textValue);

    // --- scoped access (scopeId == null is the process-instance root scope) ---

    List<ProcessVariableEntity> findByProcessInstanceIdAndScopeIdIsNull(UUID processInstanceId);

    /**
     * WO-REL-41 (B-8, п.2): pinpoint read of ONE root variable — the batch-UUID
     * lookup must not pull the full variable list.
     */
    Optional<ProcessVariableEntity> findByProcessInstanceIdAndNameAndScopeIdIsNull(
        UUID processInstanceId, String name);

    List<ProcessVariableEntity> findByProcessInstanceIdAndScopeId(UUID processInstanceId, UUID scopeId);

    /**
     * WO-REL-31 CR-4: one query instead of two for the root+scoped read merge.
     * Root rows (scopeId IS NULL) are ordered BEFORE the given scope rows — the
     * caller's "scoped wins for duplicate names" merge depends on that order.
     */
    @Query("""
        select v from ProcessVariableEntity v
        where v.processInstanceId = :processInstanceId
          and (v.scopeId is null or v.scopeId = :scopeId)
        order by case when v.scopeId is null then 0 else 1 end, v.name
        """)
    List<ProcessVariableEntity> findRootAndScoped(@Param("processInstanceId") UUID processInstanceId,
            @Param("scopeId") UUID scopeId);

    void deleteByProcessInstanceIdAndScopeId(UUID processInstanceId, UUID scopeId);
}
