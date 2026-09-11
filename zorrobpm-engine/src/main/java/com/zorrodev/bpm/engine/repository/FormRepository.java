package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.FormEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FormRepository extends JpaRepository<FormEntity, UUID> {

    Optional<FormEntity> findTopByFormKeyOrderByVersionDesc(String formKey);

    /**
     * WO-AUDIT-3 (P3): batch variant of the per-key latest lookup — one query for all
     * keys of a schema-map page instead of N {@code findTop...} round-trips. Callers
     * pick the max version per key in memory (same semantics as {@code findTop}).
     */
    List<FormEntity> findByFormKeyIn(java.util.Collection<String> formKeys);

    /** WO-C8-22: latest deployed version of a Modeler-linked form (binding {@code latest}). */
    Optional<FormEntity> findTopByFormIdOrderByVersionDesc(String formId);

    /**
     * WO-C8-23: the version of a linked form deployed together with a process
     * ({@code bindingType="deployment"}). Exact match on both columns — a null
     * {@code deploymentId} argument never matches (callers 404 first, see FormResolver),
     * so singly-deployed rows are invisible here by construction, never by fallback.
     */
    Optional<FormEntity> findFirstByFormIdAndDeploymentIdOrderByVersionDesc(String formId, UUID deploymentId);

    /**
     * WO-C8-31: the latest version of a linked form carrying a version tag
     * ({@code bindingType="versionTag"} — one tag on several versions pins the latest,
     * WO-C8-27). Exact match on both columns — a null/blank {@code versionTag}
     * argument never matches (callers 404 first, see FormResolver), so untagged
     * rows are invisible here by construction, never by fallback.
     */
    Optional<FormEntity> findFirstByFormIdAndVersionTagOrderByVersionDesc(String formId, String versionTag);

    Optional<FormEntity> findByFormKeyAndVersion(String formKey, int version);

    @Query("SELECT COALESCE(MAX(f.version), 0) FROM FormEntity f WHERE f.formKey = :formKey")
    int findMaxVersionByFormKey(@Param("formKey") String formKey);

    @Query("SELECT f FROM FormEntity f WHERE f.version = (SELECT MAX(f2.version) FROM FormEntity f2 WHERE f2.formKey = f.formKey) ORDER BY f.formKey")
    List<FormEntity> findLatestVersions();
}
