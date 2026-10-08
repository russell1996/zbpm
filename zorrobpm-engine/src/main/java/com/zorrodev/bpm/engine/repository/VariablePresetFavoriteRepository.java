package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.VariablePresetFavoriteEntity;
import com.zorrodev.bpm.engine.entity.VariablePresetFavoriteId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VariablePresetFavoriteRepository
        extends JpaRepository<VariablePresetFavoriteEntity, VariablePresetFavoriteId> {

    boolean existsByUserIdAndPresetId(UUID userId, UUID presetId);

    void deleteByUserIdAndPresetId(UUID userId, UUID presetId);
}
