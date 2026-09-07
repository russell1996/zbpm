package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    /**
     * WO-REL-10: LIMIT :batchSize to avoid unbounded locking.
     * Skips FAILED entries (quarantined after N retries).
     * FOR UPDATE SKIP LOCKED prevents two pollers from picking the same entries.
     */
    @Query(value = "SELECT * FROM outbox " +
        "WHERE published = false AND status != 'FAILED' " +
        "ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED",
        nativeQuery = true)
    List<OutboxEntry> findPendingBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("UPDATE OutboxEntry o SET o.published = true WHERE o.id = :id AND o.published = false")
    int markPublished(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE OutboxEntry o SET o.attempts = :attempts, o.lastError = :error WHERE o.id = :id")
    int recordFailure(@Param("id") UUID id, @Param("attempts") int attempts, @Param("error") String error);

    @Modifying
    @Query("UPDATE OutboxEntry o SET o.status = 'FAILED' WHERE o.id = :id")
    int markFailed(@Param("id") UUID id);

    // WO-OBS-1: gauge sampling queries (read-only, additive — no behavior change).
    @Query("SELECT COUNT(o) FROM OutboxEntry o WHERE o.published = false AND o.status != 'FAILED'")
    long countPending();

    @Query("SELECT COUNT(o) FROM OutboxEntry o WHERE o.status = 'FAILED'")
    long countQuarantined();
}
