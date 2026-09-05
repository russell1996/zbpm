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

    /** WO-C8-22: latest deployed version of a Modeler-linked form (binding {@code latest}). */
    Optional<FormEntity> findTopByFormIdOrderByVersionDesc(String formId);

    Optional<FormEntity> findByFormKeyAndVersion(String formKey, int version);

    @Query("SELECT COALESCE(MAX(f.version), 0) FROM FormEntity f WHERE f.formKey = :formKey")
    int findMaxVersionByFormKey(@Param("formKey") String formKey);

    @Query("SELECT f FROM FormEntity f WHERE f.version = (SELECT MAX(f2.version) FROM FormEntity f2 WHERE f2.formKey = f.formKey) ORDER BY f.formKey")
    List<FormEntity> findLatestVersions();
}
