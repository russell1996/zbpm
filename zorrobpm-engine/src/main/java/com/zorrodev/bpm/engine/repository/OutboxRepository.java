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
     * L2 FIX: FOR UPDATE SKIP LOCKED prevents two pollers from picking up the same entries.
     * When two pollers run concurrently, one gets the rows locked, the other skips them.
     */
    @Query(value = "SELECT * FROM outbox WHERE published = false ORDER BY created_at ASC FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<OutboxEntry> findByPublishedFalseOrderByCreatedAtAsc();

    @Modifying
    @Query("UPDATE OutboxEntry o SET o.published = true WHERE o.id = :id AND o.published = false")
    int markPublished(@Param("id") UUID id);
}
