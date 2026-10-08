package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.VariablePresetHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VariablePresetHistoryRepository extends JpaRepository<VariablePresetHistoryEntity, UUID> {

    /** История шаблона в хронологическом порядке (эндпоинт отдаёт как есть). */
    List<VariablePresetHistoryEntity> findByPresetIdOrderByAtAscIdAsc(UUID presetId);
}
