package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.IdempotencyRecord;
import com.zorrodev.bpm.engine.entity.IdempotencyRecordId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecordId> {

    /** WO-REL-32 F05: ровно 0-1 запись под (key, endpoint, actor_id). */
    java.util.Optional<IdempotencyRecord> findByIdemKeyAndEndpointAndActorId(String idemKey, String endpoint, String actorId);

    /** DEPRECATED: for tests — find all under key+endpoint across actors. */
    List<IdempotencyRecord> findByIdemKeyAndEndpoint(String idemKey, String endpoint);

    /** WO-REL-21: TTL listing, paged — the table is high-volume, never full-scan it. */
    List<IdempotencyRecord> findByCreatedAtBefore(Instant cutoff, Pageable page);
}
