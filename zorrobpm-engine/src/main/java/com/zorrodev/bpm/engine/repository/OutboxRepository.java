package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    List<OutboxEntry> findByPublishedFalseOrderByCreatedAtAsc();

    @Modifying
    @Query("UPDATE OutboxEntry o SET o.published = true WHERE o.id = :id AND o.published = false")
    int markPublished(@Param("id") UUID id);
}
