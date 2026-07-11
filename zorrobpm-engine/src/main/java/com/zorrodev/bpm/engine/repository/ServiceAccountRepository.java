package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ServiceAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceAccountRepository extends JpaRepository<ServiceAccountEntity, UUID> {

    List<ServiceAccountEntity> findByProcessId(UUID processId);

    List<ServiceAccountEntity> findByKeyHash(String keyHash);
}
