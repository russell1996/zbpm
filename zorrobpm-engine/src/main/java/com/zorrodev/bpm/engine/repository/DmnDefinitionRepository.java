package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DmnDefinitionRepository extends JpaRepository<DmnDefinitionEntity, UUID> {

    /** The latest deployed version of a decision. */
    Optional<DmnDefinitionEntity> findFirstByDecisionIdOrderByVersionDesc(String decisionId);
}
