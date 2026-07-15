package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Transactional batch processor for outbox entries.
 * Separated from OutboxPollerService to avoid self-invocation proxy issue (P-18):
 * @Transactional only works when called through a Spring proxy (i.e. from a different bean).
 *
 * At-least-once delivery: publish FIRST, then markPublished.
 * SELECT … FOR UPDATE SKIP LOCKED + @Transactional = row locks held until commit,
 * preventing two pollers from picking the same entries (AUD-1 F1 fix).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxBatchProcessor {

    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public void processBatch() {
        List<OutboxEntry> pending = outboxRepository.findByPublishedFalseOrderByCreatedAtAsc();
        for (OutboxEntry entry : pending) {
            try {
                JobDetailModel detail = objectMapper.readValue(entry.getPayload(), JobDetailModel.class);
                publisher.publishEvent(new ServiceTaskEnqueued(detail));
                outboxRepository.markPublished(entry.getId());
                log.info("Published outbox entry {} for service task {}", entry.getId(), detail.getServiceTaskId());
            } catch (Exception e) {
                log.error("Failed to publish outbox entry {} (will retry)", entry.getId(), e);
            }
        }
    }
}
