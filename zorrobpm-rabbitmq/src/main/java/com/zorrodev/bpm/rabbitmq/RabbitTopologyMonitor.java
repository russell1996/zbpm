package com.zorrodev.bpm.rabbitmq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WO-REL-66 (C): read-only control — warns when an expected queue is missing
 * on the broker. Diagnostic only, NO side effects: it never declares, deletes,
 * or publishes (healing belongs to {@link JobQueueRedeclareListener} and
 * {@link JobQueueDeclarer#redeclareForSend}).
 *
 * <p>A missing queue here means the next send to it is unroutable (or a
 * listener is already in a {@code not_found} loop) — the warning tells the
 * operator BEFORE the incident becomes a restart, and tells them which queue
 * to look at. Queue names are infrastructure identifiers, not user data
 * (criterion 5: nothing sensitive is logged).
 *
 * <p>{@code getQueueInfo} returns null on a broker 404 (missing queue) and
 * throws when the broker is unreachable — the latter is a debug + quiet
 * return (the broker being down is already loud everywhere else; every queue
 * would fail the same way, so continuing the loop adds nothing).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitTopologyMonitor {

    private final JobQueueDeclarer declarer;
    private final AmqpAdmin amqpAdmin;

    @Value("${zorrobpm.rabbit.topology-monitor-enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${zorrobpm.rabbit.topology-monitor-interval-ms:60000}")
    public void checkDeclaredQueues() {
        if (!enabled) {
            return;
        }
        for (String queueName : declarer.declaredQueueNames()) {
            boolean present;
            try {
                present = amqpAdmin.getQueueInfo(queueName) != null;
            } catch (Exception e) {
                log.debug("WO-REL-66: topology monitor cannot reach the broker "
                    + "while checking {}: {}", queueName, e.getMessage());
                return;
            }
            if (!present) {
                log.warn("WO-REL-66: expected queue {} is MISSING on the broker — "
                    + "sends to it are unroutable until reconnect-redeclare heals it "
                    + "(see JobQueueRedeclareListener)", queueName);
            }
        }
    }
}
