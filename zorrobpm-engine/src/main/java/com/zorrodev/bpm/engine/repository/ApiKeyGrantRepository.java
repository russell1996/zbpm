package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ApiKeyGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApiKeyGrantRepository extends JpaRepository<ApiKeyGrantEntity, ApiKeyGrantEntity.ApiKeyGrantId> {
    List<ApiKeyGrantEntity> findByApiKeyId(UUID apiKeyId);
    void deleteByApiKeyId(UUID apiKeyId);
}
