package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ElementArtifactBindingRepository extends JpaRepository<ElementArtifactBindingEntity, UUID> {

    Optional<ElementArtifactBindingEntity> findByProcessDefinitionIdAndElementId(UUID processDefinitionId, String elementId);

    List<ElementArtifactBindingEntity> findByProcessDefinitionId(UUID processDefinitionId);

    void deleteByProcessDefinitionIdAndElementId(UUID processDefinitionId, String elementId);
}
