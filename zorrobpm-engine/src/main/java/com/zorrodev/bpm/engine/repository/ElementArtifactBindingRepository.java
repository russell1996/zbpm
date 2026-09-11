package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ElementArtifactBindingRepository extends JpaRepository<ElementArtifactBindingEntity, UUID> {

    Optional<ElementArtifactBindingEntity> findByProcessDefinitionIdAndElementId(UUID processDefinitionId, String elementId);

    List<ElementArtifactBindingEntity> findByProcessDefinitionId(UUID processDefinitionId);

    void deleteByProcessDefinitionIdAndElementId(UUID processDefinitionId, String elementId);

    /** WO-VM-9a: find bindings by old PD version to carry-forward to new version. */
    @Query("SELECT b FROM ElementArtifactBindingEntity b WHERE b.processDefinitionVersion = :oldVersion AND EXISTS (SELECT 1 FROM ProcessDefinitionEntity pd WHERE pd.key = :key AND pd.id = b.processDefinitionId)")
    List<ElementArtifactBindingEntity> findByKeyAndOldVersion(@Param("key") String key, @Param("oldVersion") int oldVersion);

    /** WO-SEC-47: find all bindings for a given artifact (form) key. */
    List<ElementArtifactBindingEntity> findByArtifactKey(String artifactKey);

    /** WO-SEC-47: find all bindings for a set of process definition IDs. */
    List<ElementArtifactBindingEntity> findByProcessDefinitionIdIn(Collection<UUID> processDefinitionIds);

    /**
     * WO-AUDIT-3 (P3): batch variant for the schema-map shared-flag — counts usage of
     * this page's keys across all PDs in one query instead of {@code findAll()}.
     */
    List<ElementArtifactBindingEntity> findByArtifactKeyIn(Collection<String> artifactKeys);
}
