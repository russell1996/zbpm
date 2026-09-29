package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxStatus;
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

    /**
     * WO-REL-22 (B3): conditional — returns 1 only on the FIRST transition to FAILED.
     * Callers emit {@code outbox.quarantined} only when this returns 1, so a duplicate
     * mark (e.g. an in-flight delivery result landing after quarantine) cannot emit twice.
     *
     * <p>WO-REL-22 HOLD: {@code clearAutomatically} — bulk UPDATE bypasses the
     * persistence context, so a later {@code findById} in the same transaction would
     * return the stale managed instance (CTO caught it live: PENDING-in-DB read back
     * as FAILED). Same precedent as {@code ProcessInstanceRepository} bulk updates.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEntry o SET o.status = com.zorrodev.bpm.engine.entity.OutboxStatus.FAILED WHERE o.id = :id AND o.status != com.zorrodev.bpm.engine.entity.OutboxStatus.FAILED")
    int markFailed(@Param("id") UUID id);

    /**
     * WO-REL-22 (B2): re-drive — FAILED → pending with a reset attempt counter.
     * Returns 1 only if the row was actually FAILED (callers map 0 to 404/409).
     *
     * <p>WO-REL-22 HOLD: {@code clearAutomatically} for the same reason — the admin
     * resource re-reads the row right after this bulk update in one request.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEntry o SET o.status = com.zorrodev.bpm.engine.entity.OutboxStatus.PENDING, o.attempts = 0, o.lastError = NULL "
        + "WHERE o.id = :id AND o.status = com.zorrodev.bpm.engine.entity.OutboxStatus.FAILED")
    int redrive(@Param("id") UUID id);

    /** WO-REL-22 (B1): quarantine list for the admin endpoint. */
    List<OutboxEntry> findByStatusOrderByCreatedAtDesc(OutboxStatus status);

    @Query("SELECT o.id as id, o.kind as kind, o.status as status, o.published as published, o.attempts as attempts, o.lastError as lastError, o.createdAt as createdAt FROM OutboxEntry o WHERE o.status = :status ORDER BY o.createdAt DESC")
    List<OutboxEntryView> findProjectedByStatusOrderByCreatedAtDesc(@Param("status") OutboxStatus status, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT o.id as id, o.kind as kind, o.status as status, o.published as published, o.attempts as attempts, o.lastError as lastError, o.createdAt as createdAt FROM OutboxEntry o ORDER BY o.createdAt DESC")
    List<OutboxEntryView> findProjectedAllOrderByCreatedAtDesc(org.springframework.data.domain.Pageable pageable);

    // WO-OBS-1: gauge sampling queries (read-only, additive — no behavior change).
    @Query("SELECT COUNT(o) FROM OutboxEntry o WHERE o.published = false AND o.status != com.zorrodev.bpm.engine.entity.OutboxStatus.FAILED")
    long countPending();

    @Query("SELECT COUNT(o) FROM OutboxEntry o WHERE o.status = com.zorrodev.bpm.engine.entity.OutboxStatus.FAILED")
    long countQuarantined();
}
