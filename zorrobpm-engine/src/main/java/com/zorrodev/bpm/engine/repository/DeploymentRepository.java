package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.DeploymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeploymentRepository extends JpaRepository<DeploymentEntity, UUID> {
}
