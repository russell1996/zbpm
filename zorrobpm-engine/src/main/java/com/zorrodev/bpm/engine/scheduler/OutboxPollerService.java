package com.zorrodev.bpm.engine.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the outbox table for unpublished entries and publishes them to MQ.
 * Delegates to OutboxBatchProcessor (@Transactional) so that FOR UPDATE SKIP LOCKED
 * row locks are held until markPublished (AUD-1 F1 fix).
 */
@Slf4j
@Profile("!test")
@Component
@RequiredArgsConstructor
public class OutboxPollerService {

    private final OutboxBatchProcessor batchProcessor;

    @Scheduled(fixedDelayString = "${zorrobpm.engine.outbox-poll-interval-ms:2000}")
    public void pollOnce() {
        batchProcessor.processBatch();
    }
}
