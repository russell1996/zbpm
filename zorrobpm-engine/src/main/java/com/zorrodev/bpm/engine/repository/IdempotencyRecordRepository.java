package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.IdempotencyRecord;
import com.zorrodev.bpm.engine.entity.IdempotencyRecordId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecordId> {

    Optional<IdempotencyRecord> findByIdemKeyAndEndpoint(String idemKey, String endpoint);

    /** WO-REL-21: TTL listing, paged — the table is high-volume, never full-scan it. */
    List<IdempotencyRecord> findByCreatedAtBefore(Instant cutoff, Pageable page);
}
