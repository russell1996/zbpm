package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.TokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface TokenRepository extends JpaRepository<TokenEntity, UUID> {

    /**
     * WO-REL-40 (B-5): row-locked read of the token for the atomic
     * pending-branches decrement. The {@code SELECT ... FOR UPDATE} serialises
     * concurrent {@code decrementPendingBranches} calls inside the caller's
     * transaction (same shape as {@code ActivityRepository.findByIdForUpdate},
     * WO-REL-30 B-3). A plain {@code findById} here is a lost-update race:
     * two branches read the same counter and both write back the same value.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TokenEntity t WHERE t.id = :id")
    Optional<TokenEntity> findByIdForUpdate(UUID id);
}
