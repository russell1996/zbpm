package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DomainEventRepository extends JpaRepository<DomainEventEntity, Long> {

    @Query(value = "SELECT * FROM events WHERE sequence > :since ORDER BY sequence ASC LIMIT :limit", nativeQuery = true)
    List<DomainEventEntity> findSince(@Param("since") long since, @Param("limit") int limit);

    @Query(value = "SELECT * FROM events WHERE process_instance_id = :processInstanceId ORDER BY sequence ASC", nativeQuery = true)
    List<DomainEventEntity> findByProcessInstanceId(@Param("processInstanceId") UUID processInstanceId);

    @Query(value = "SELECT COALESCE(MAX(sequence), 0) FROM events", nativeQuery = true)
    long getMaxSequence();

    /**
     * Cursor-based query with AuthZ filtering: only events whose process_definition_id
     * is in the allowed set. Used by GET /events (WO-EVT-3).
     */
    @Query(value = "SELECT * FROM events " +
        "WHERE sequence > :since " +
        "AND process_definition_id IN :pdIds " +
        "ORDER BY sequence ASC LIMIT :limit", nativeQuery = true)
    List<DomainEventEntity> findSinceForPrincipal(
        @Param("since") long since,
        @Param("pdIds") Collection<UUID> processDefinitionIds,
        @Param("limit") int limit);

    /**
     * Cursor-based query with processInstanceId filter.
     */
    @Query(value = "SELECT * FROM events " +
        "WHERE sequence > :since " +
        "AND process_instance_id = :processInstanceId " +
        "ORDER BY sequence ASC LIMIT :limit", nativeQuery = true)
    List<DomainEventEntity> findSinceByProcessInstanceId(
        @Param("since") long since,
        @Param("processInstanceId") UUID processInstanceId,
        @Param("limit") int limit);

    /**
     * Cursor-based query with AuthZ + processInstanceId filter.
     */
    @Query(value = "SELECT * FROM events " +
        "WHERE sequence > :since " +
        "AND process_definition_id IN :pdIds " +
        "AND process_instance_id = :processInstanceId " +
        "ORDER BY sequence ASC LIMIT :limit", nativeQuery = true)
    List<DomainEventEntity> findSinceForPrincipalByProcessInstanceId(
        @Param("since") long since,
        @Param("pdIds") Collection<UUID> processDefinitionIds,
        @Param("processInstanceId") UUID processInstanceId,
        @Param("limit") int limit);
}
