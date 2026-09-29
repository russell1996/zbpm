package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ProcessEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessRepository extends JpaRepository<ProcessEntity, UUID> {

    Optional<ProcessEntity> findByDefinitionKey(String definitionKey);

    List<ProcessEntity> findByArchivedTrue();

    List<ProcessEntity> findByIdIn(Collection<UUID> ids);
}
