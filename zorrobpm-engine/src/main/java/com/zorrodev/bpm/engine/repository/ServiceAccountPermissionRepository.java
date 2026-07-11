package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ServiceAccountPermissionEntity;
import com.zorrodev.bpm.engine.entity.ServiceAccountPermissionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceAccountPermissionRepository extends JpaRepository<ServiceAccountPermissionEntity, ServiceAccountPermissionId> {

    List<ServiceAccountPermissionEntity> findByServiceAccountId(UUID serviceAccountId);
}
