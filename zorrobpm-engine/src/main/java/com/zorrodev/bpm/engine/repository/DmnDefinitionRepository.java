package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DmnDefinitionRepository extends JpaRepository<DmnDefinitionEntity, UUID> {

    /** The latest deployed version of a decision. */
    Optional<DmnDefinitionEntity> findFirstByDecisionIdOrderByVersionDesc(String decisionId);

    /**
     * The process definition id of the latest deployed version of a decision.
     * Scalar projection — avoids hydrating the {@code @Lob dmn} column (which fails on
     * PostgreSQL outside a transaction, "Large Objects may not be used in auto-commit mode").
     * Used by DmnResource authz (WO-SEC-40).
     */
    @Query("SELECT d.processDefinitionId FROM DmnDefinitionEntity d WHERE d.decisionId = :decisionId ORDER BY d.version DESC LIMIT 1")
    Optional<UUID> findLatestProcessDefinitionId(@Param("decisionId") String decisionId);
}
