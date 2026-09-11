package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
     * WO-C8-20: the latest version of a decision annotated with the given version tag
     * ({@code bindingType="versionTag"} pinning) — proven by test, not by reading this query.
     */
    Optional<DmnDefinitionEntity> findFirstByDecisionIdAndVersionTagOrderByVersionDesc(String decisionId, String versionTag);

    /**
     * WO-C8-18: rows of one decision created by one batch deployment (reporting the batch
     * result; keyed by the unique batch id, so concurrent deploys cannot leak in).
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

    /**
     * WO-AUDIT-3 (P1): every row WITHOUT the TEXT {@code dmn} blob — {@code listDecisions}
     * only needs identity/version/scope to pick the latest visible version per decisionId;
     * the XML is loaded solely for those latest rows (see {@code findAllById} use).
     */
    interface DmnDecisionMeta {
        UUID getId();
        String getDecisionId();
        int getVersion();
        UUID getProcessDefinitionId();
        Instant getCreatedAt();
    }

    @Query("SELECT d.id AS id, d.decisionId AS decisionId, d.version AS version, "
        + "d.processDefinitionId AS processDefinitionId, d.createdAt AS createdAt "
        + "FROM DmnDefinitionEntity d")
    List<DmnDecisionMeta> findAllMeta();
}
