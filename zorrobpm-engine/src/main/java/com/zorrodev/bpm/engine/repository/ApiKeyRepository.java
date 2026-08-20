package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {
    Optional<ApiKeyEntity> findByOwnerUserId(UUID ownerUserId);
    Optional<ApiKeyEntity> findByKeyHash(String keyHash);
    /** WO-INT-4: system accounts may hold several active keys at once. */
    List<ApiKeyEntity> findAllByOwnerUserId(UUID ownerUserId);
}
