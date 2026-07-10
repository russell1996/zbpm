package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Polls the outbox table for unpublished entries and publishes them to MQ.
 * Uses atomic claim (UPDATE published=true WHERE id=? AND published=false)
 * to prevent duplicate delivery on multi-node deployments.
 */
@Slf4j
@Profile("!test")
@Component
@RequiredArgsConstructor
public class OutboxPollerService {

    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${zorrobpm.engine.outbox-poll-interval-ms:2000}")
    public void pollOnce() {
        List<OutboxEntry> pending = outboxRepository.findByPublishedFalseOrderByCreatedAtAsc();
        for (OutboxEntry entry : pending) {
            try {
                // Atomic claim: only one node can publish this entry
                if (outboxRepository.claimOutboxEntry(entry.getId()) == 0) {
                    continue; // Already claimed by another node
                }

                JobDetailModel detail = objectMapper.readValue(entry.getPayload(), JobDetailModel.class);
                publisher.publishEvent(new ServiceTaskEnqueued(detail));
                log.info("Published outbox entry {} for service task {}", entry.getId(), detail.getServiceTaskId());
            } catch (Exception e) {
                log.error("Failed to publish outbox entry {}", entry.getId(), e);
            }
        }
    }
}
