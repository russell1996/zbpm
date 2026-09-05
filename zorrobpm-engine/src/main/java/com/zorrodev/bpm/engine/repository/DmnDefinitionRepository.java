package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DmnDefinitionRepository extends JpaRepository<DmnDefinitionEntity, UUID> {

    /** The latest deployed version of a decision. */
    Optional<DmnDefinitionEntity> findFirstByDecisionIdOrderByVersionDesc(String decisionId);

    /**
     * WO-C8-17: the latest version of a decision deployed together with the given process
     * definition version ({@code bindingType="deployment"} pinning).
     */
    Optional<DmnDefinitionEntity> findFirstByDecisionIdAndProcessDefinitionIdOrderByVersionDesc(String decisionId, UUID processDefinitionId);

    /**
     * WO-C8-18: all decision rows created by one batch deployment (reporting the batch result;
     * keyed by the unique batch id, so concurrent deploys cannot leak in).
     */
    List<DmnDefinitionEntity> findByDeploymentId(UUID deploymentId);

    /**
     * The process definition id of the latest deployed version of a decision.
     * Scalar projection — avoids hydrating the {@code @Lob dmn} column (which fails on
     * PostgreSQL outside a transaction, "Large Objects may not be used in auto-commit mode").
     * Used by DmnResource authz (WO-SEC-40).
     */
    @Query("SELECT d.processDefinitionId FROM DmnDefinitionEntity d WHERE d.decisionId = :decisionId ORDER BY d.version DESC LIMIT 1")
    Optional<UUID> findLatestProcessDefinitionId(@Param("decisionId") String decisionId);
}
