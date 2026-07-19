package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DomainEventRepository extends JpaRepository<DomainEventEntity, Long> {

    @Query(value = "SELECT * FROM events WHERE sequence > :since ORDER BY sequence ASC LIMIT :limit", nativeQuery = true)
    List<DomainEventEntity> findSince(@Param("since") long since, @Param("limit") int limit);

    @Query(value = "SELECT * FROM events WHERE process_instance_id = :processInstanceId ORDER BY sequence ASC", nativeQuery = true)
    List<DomainEventEntity> findByProcessInstanceId(@Param("processInstanceId") UUID processInstanceId);

    @Query(value = "SELECT COALESCE(MAX(sequence), 0) FROM events", nativeQuery = true)
    long getMaxSequence();
}
