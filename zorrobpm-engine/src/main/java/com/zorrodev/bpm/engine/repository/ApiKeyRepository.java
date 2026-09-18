package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {
    Optional<ApiKeyEntity> findByOwnerUserId(UUID ownerUserId);
    Optional<ApiKeyEntity> findByKeyHash(String keyHash);
    /** WO-INT-4: system accounts may hold several active keys at once. */
    List<ApiKeyEntity> findAllByOwnerUserId(UUID ownerUserId);

    /**
     * WO-SEC-66 (F07): debounce touch of {@code lastUsedAt} that can never
     * resurrect a revoked key. One statement, one column, conditional: a
     * concurrent {@code revokeOwnKey} between the filter's read and this write
     * simply makes the UPDATE match zero rows instead of merging a stale
     * detached snapshot (with {@code revokedAt=null}) back over the revoke.
     * No entity merge — security columns are untouched by construction.
     *
     * @return rows matched (0 = already revoked, 1 = touched).
     */
    @Modifying
    @Transactional
    @Query("UPDATE ApiKeyEntity a SET a.lastUsedAt = :now WHERE a.id = :id AND a.revokedAt IS NULL")
    int touchLastUsedAtIfLive(@Param("id") UUID id, @Param("now") Instant now);
}
