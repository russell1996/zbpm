package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ProcessDefinitionRepository extends JpaRepository<ProcessDefinitionEntity, UUID>, JpaSpecificationExecutor<ProcessDefinitionEntity> {

    /** Case-insensitive partial match on the process name. */
    static Specification<ProcessDefinitionEntity> byNameContains(String name) {
        // WO-SEC-17 L3: escape LIKE wildcards + backslash to prevent injection
        String escaped = name.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), "%" + escaped + "%", '\\');
    }

    static Specification<ProcessDefinitionEntity> byKey(String key) {
        return (root, query, cb) -> cb.equal(root.get("key"), key);
    }

    static Specification<ProcessDefinitionEntity> byVersion(Integer version) {
        return (root, query, cb) -> cb.equal(root.get("version"), version);
    }

    /** Keeps only the latest version of each key, via a correlated max-version subquery. */
    static Specification<ProcessDefinitionEntity> latestVersion() {
        return (root, query, cb) -> {
            var subquery = query.subquery(Integer.class);
            var sub = subquery.from(ProcessDefinitionEntity.class);
            subquery.select(cb.max(sub.get("version")))
                .where(cb.equal(sub.get("key"), root.get("key")));
            return cb.equal(root.get("version"), subquery);
        };
    }

    Optional<ProcessDefinitionEntity> findBySha256(String sha256);

    @Query("SELECT MAX(pd.version) FROM ProcessDefinitionEntity pd WHERE pd.key = :key")
    Optional<Integer> findMaxByKey(String key);

    @Query("SELECT MAX(pd.version) FROM ProcessDefinitionEntity pd WHERE pd.key = :key AND pd.versionTag = :versionTag")
    Optional<Integer> findMaxByKeyAndVersionTag(String key, String versionTag);

    /**
     * WO-C8-3b: latest version of a process deployed together with the given deployment
     * ({@code bindingType="deployment"} pinning on call activities).
     */
    @Query("SELECT MAX(pd.version) FROM ProcessDefinitionEntity pd WHERE pd.key = :key AND pd.deploymentId = :deploymentId")
    Optional<Integer> findMaxByKeyAndDeploymentId(String key, UUID deploymentId);

    Optional<ProcessDefinitionEntity> findByKeyAndVersion(String key, Integer version);

    @Query("SELECT pd1 FROM ProcessDefinitionEntity pd1 JOIN (SELECT pd2.key AS key, MAX(pd2.version) AS version FROM ProcessDefinitionEntity pd2 GROUP BY pd2.key) AS pd3 ON pd1.key = pd3.key AND pd1.version = pd3.version")
    Page<ProcessDefinitionEntity> findAllLatest(Pageable page);
}
