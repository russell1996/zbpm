package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.FormEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FormRepository extends JpaRepository<FormEntity, UUID> {

    Optional<FormEntity> findTopByFormKeyOrderByVersionDesc(String formKey);

    @Query("SELECT COALESCE(MAX(f.version), 0) FROM FormEntity f WHERE f.formKey = :formKey")
    int findMaxVersionByFormKey(@Param("formKey") String formKey);
}
