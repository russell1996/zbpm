package com.zorrodev.bpm.engine.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WO-REL-38: тикающий вызов {@link FeedPositionAssigner} (та же пара, что
 * {@code OutboxPollerService} → {@code OutboxBatchProcessor}: шедулер без
 * транзакции, вся работа — в {@code @Transactional} процессоре).
 *
 * <p>Trade-off (честно, по диспатчу): событие видимо consumer-курсору только
 * после следующего тика (задержка ≈ интервал поллинга, не мгновенно при
 * инсерте) — цена корректности курсора.
 */
@Slf4j
@Profile("!test")
@Component
@RequiredArgsConstructor
public class FeedPositionPoller {

    private final FeedPositionAssigner assigner;

    @Scheduled(fixedDelayString = "${zorrobpm.feed-position.poll-interval-ms:2000}")
    public void pollOnce() {
        try {
            assigner.assignPendingPositions();
        } catch (Exception e) {
            log.error("Feed position assignment tick failed (next tick retries)", e);
        }
    }
}
