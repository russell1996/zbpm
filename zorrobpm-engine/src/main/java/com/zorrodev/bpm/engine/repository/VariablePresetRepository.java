package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.VariablePresetEntity;
import com.zorrodev.bpm.engine.entity.VariablePresetTargetKind;
import com.zorrodev.bpm.engine.entity.VariablePresetVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VariablePresetRepository extends JpaRepository<VariablePresetEntity, UUID> {

    /** Все шаблоны владельца (любая видимость) — своя половина списка. */
    List<VariablePresetEntity> findByOwnerUserId(UUID ownerUserId);

    /** Чужие PROCESS-шаблоны процессов, где вызывающий — участник. */
    @Query("""
        select p from VariablePresetEntity p
        where p.visibility = :visibility
          and p.processDefinitionKey in :keys
          and p.ownerUserId <> :excludeOwner
        """)
    List<VariablePresetEntity> findSharedInKeys(
        @Param("visibility") VariablePresetVisibility visibility,
        @Param("keys") Collection<String> keys,
        @Param("excludeOwner") UUID excludeOwner);

    /** Чужие PROCESS-шаблоны места применения. */
    @Query("""
        select p from VariablePresetEntity p
        where p.visibility = :visibility
          and p.processDefinitionKey = :key
          and p.targetKind = :kind
          and p.targetRef = :ref
          and p.ownerUserId <> :excludeOwner
        """)
    List<VariablePresetEntity> findSharedAtBinding(
        @Param("visibility") VariablePresetVisibility visibility,
        @Param("key") String key,
        @Param("kind") VariablePresetTargetKind kind,
        @Param("ref") String ref,
        @Param("excludeOwner") UUID excludeOwner);

    /** Лимит WO-VT-1: ≤200 шаблонов на (ключ, владелец). */
    long countByOwnerUserIdAndProcessDefinitionKey(UUID ownerUserId, String processDefinitionKey);

    /** Проверка дубля имени перед вставкой (гонку закрывает UNIQUE-индекс БД). */
    boolean existsByOwnerUserIdAndProcessDefinitionKeyAndTargetKindAndTargetRefAndName(
        UUID ownerUserId, String processDefinitionKey, VariablePresetTargetKind targetKind,
        String targetRef, String name);
}
