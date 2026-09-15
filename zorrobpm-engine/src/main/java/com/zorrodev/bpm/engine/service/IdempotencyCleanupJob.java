package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.entity.IdempotencyRecord;
import com.zorrodev.bpm.engine.entity.IdempotencyRecordId;
import com.zorrodev.bpm.engine.repository.IdempotencyRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * WO-REL-21: TTL cleanup of idempotency records past the idempotency window.
 *
 * <p>Mirrors {@code RegistrationCleanupJob}: per-row {@code TransactionTemplate}
 * (not one transaction for the whole pass — one bad row must not fail the rest,
 * P-42), listing is paged (this table is high-volume — never full-scan it).
 */
@Slf4j
@Component
public class IdempotencyCleanupJob {

    private final IdempotencyRecordRepository repository;
    private final TransactionTemplate transactionTemplate;

    @Value("${zorrobpm.idempotency.ttl-hours:24}")
    private int ttlHours = 24;

    @Value("${zorrobpm.idempotency.cleanup-page-size:500}")
    private int pageSize = 500;

    public IdempotencyCleanupJob(IdempotencyRecordRepository repository,
            TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${zorrobpm.idempotency.cleanup-interval-ms:3600000}")
    public void run() {
        int deleted = cleanExpired();
        if (deleted > 0) {
            log.info("IdempotencyCleanup: deleted {} expired idempotency records", deleted);
        }
    }

    /**
     * Deletes records older than the TTL window. Returns the deleted count. Public
     * for tests (the {@code @Scheduled} entry point is {@link #run()}).
     */
    public int cleanExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(ttlHours));
        int deleted = 0;
        while (true) {
            List<IdempotencyRecord> batch =
                repository.findByCreatedAtBefore(cutoff, PageRequest.of(0, pageSize));
            if (batch.isEmpty()) {
                return deleted;
            }
            int pageDeleted = 0;
            for (IdempotencyRecord row : batch) {
                IdempotencyRecordId id = new IdempotencyRecordId(
                    row.getIdemKey(), row.getEndpoint(), row.getActorId());
                try {
                    Boolean gone = transactionTemplate.execute(status -> {
                        if (!repository.existsById(id)) {
                            return false;
                        }
                        repository.deleteById(id);
                        return true;
                    });
                    if (Boolean.TRUE.equals(gone)) {
                        deleted++;
                        pageDeleted++;
                    }
                } catch (Exception e) {
                    log.warn("IdempotencyCleanup: skipping row {} {}: {}",
                        row.getIdemKey(), row.getEndpoint(), e.getMessage());
                }
            }
            if (pageDeleted == 0) {
                // No progress on a full page (rows permanently undeletable) — break
                // instead of hot-looping the same page until the next scheduled pass.
                // Warn, not error: a concurrent cleaner on another replica racing us
                // to the same rows trips this benignly.
                log.warn("IdempotencyCleanup: no rows deleted from a full page, stopping pass");
                return deleted;
            }
        }
    }
}
