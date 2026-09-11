package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.IdempotencyRecord;
import com.zorrodev.bpm.engine.entity.IdempotencyRecordId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecordId> {

    /**
     * WO-REL-21 раунд 2: все записи под (key, endpoint) — обычно 0-1, по одной на
     * credential. Вызывающий сравнивает ОБА хэша через {@code MessageDigest.isEqual}:
     * совпали оба — replay; совпал только body — miss (чужой credential, исполнить
     * заново, CTO запретил 422); совпал только credential — 422.
     */
    List<IdempotencyRecord> findByIdemKeyAndEndpoint(String idemKey, String endpoint);

    /** WO-REL-21: TTL listing, paged — the table is high-volume, never full-scan it. */
    List<IdempotencyRecord> findByCreatedAtBefore(Instant cutoff, Pageable page);
}
