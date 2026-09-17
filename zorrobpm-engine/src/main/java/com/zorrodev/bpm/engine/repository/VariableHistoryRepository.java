package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.VariableHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * WO-ENG-16 (WB-003): чтение append-only истории переменных.
 *
 * <p>Порядок — {@code changedAt}, затем {@code id}: два изменения в пределах
 * одного тика часов обязаны сохранять порядок вставки, а не зависеть от
 * разрешения часов БД.
 */
public interface VariableHistoryRepository extends JpaRepository<VariableHistoryEntity, UUID> {

    List<VariableHistoryEntity> findByProcessInstanceIdOrderByChangedAtAscIdAsc(UUID processInstanceId);

    List<VariableHistoryEntity> findByProcessInstanceIdAndNameOrderByChangedAtAscIdAsc(
        UUID processInstanceId, String name);
}
