package com.zorrodev.bpm.engine;

import com.zorrodev.bpm.engine.entity.IdempotencyRecord;
import com.zorrodev.bpm.engine.repository.IdempotencyRecordRepository;
import com.zorrodev.bpm.engine.service.IdempotencyCleanupJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-21: TTL cleanup of idempotency records (H2 part; PG equivalence runs at review).
 * Expired rows go, fresh rows stay, count is exact.
 */
@ActiveProfiles("test")
@SpringBootTest
class IdempotencyCleanupJobTest {

    @Autowired
    private IdempotencyRecordRepository repository;
    @Autowired
    private IdempotencyCleanupJob job;

    @BeforeEach
    void clean() {
        repository.deleteAllInBatch();
    }

    private void seed(String key, Instant createdAt) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.setIdemKey(key);
        r.setEndpoint("/process-instances");
        r.setActorId("actor-" + key);
        r.setCredentialHash("cred");
        r.setRequestHash("abc");
        r.setResponseStatus(200);
        r.setResponseBody("{}");
        r.setCreatedAt(createdAt);
        repository.saveAndFlush(r);
    }

    @Test
    void cleanExpired_deletesOnlyStaleRows() {
        // Default TTL is 24h: 25h-old is stale, fresh is kept.
        seed("stale-key", Instant.now().minusSeconds(25 * 3600 + 60));
        seed("fresh-key", Instant.now());

        int deleted = job.cleanExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findByIdemKeyAndEndpoint("stale-key", "/process-instances")).isEmpty();
        assertThat(repository.findByIdemKeyAndEndpoint("fresh-key", "/process-instances")).isNotEmpty();
    }

    @Test
    void cleanExpired_emptyTable_zero() {
        assertThat(job.cleanExpired()).isZero();
    }
}
