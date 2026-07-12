package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    @Query("SELECT a FROM AuditLogEntity a WHERE " +
           "(:processKey IS NULL OR a.processKey = :processKey) AND " +
           "(:ownerUserId IS NULL OR a.ownerUserId = :ownerUserId) AND " +
           "(:fromTime IS NULL OR a.at >= :fromTime) AND " +
           "(:toTime IS NULL OR a.at <= :toTime) " +
           "ORDER BY a.at DESC")
    List<AuditLogEntity> findByFilters(String processKey, UUID ownerUserId,
                                        Instant fromTime, Instant toTime);
}
