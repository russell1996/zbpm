package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.contract.dto.event.DomainEventType;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * WO-REL-66 (B): breaks the self-feeding {@code outbox.quarantined} loop.
 *
 * <p>Loop mechanics on master (incident 2026-10-07, ~24 rows/min): a domain
 * event with nothing to route to (no bound queue on {@code zorrobpm.events})
 * is returned by the broker (NO_ROUTE) → after {@code maxRetries} attempts
 * the entry is quarantined → quarantine emits {@code outbox.quarantined} →
 * that notification is ITSELF unroutable → quarantined → emits again.
 * The quarantine counter grows without bound, and every generation costs
 * broker round-trips plus DB rows.
 *
 * <p>The cut: the path «delivery failure → quarantine → event» is FORBIDDEN
 * for quarantine notifications themselves. An undeliverable
 * {@code outbox.quarantined} envelope is dropped WITH accounting (WARN +
 * {@code zbpm.domain.event.unroutable{type="outbox.quarantined"}}): it is marked
 * published (terminal — the poller never picks it up again) and NO new event
 * is emitted. Anything else (including ordinary unroutable domain events)
 * keeps the old quarantine-and-notify behavior — the notification about THEM
 * is exactly what must survive, and it routes to the same exchange (if the
 * exchange itself is reachable, operators bound to it see it).
 *
 * <p>Semantics choice (per WO): unroutable notifications are DROPPED, not
 * accumulated — an alert nobody can route to is not a backlog, it is noise;
 * keeping it in the outbox would only re-arm the loop on the next redrive.
 */
@Slf4j
final class QuarantineNotificationGuard {

    private QuarantineNotificationGuard() {
    }

    /**
     * Is this outbox entry a quarantine notification (a DOMAIN_EVENT whose
     * envelope type is {@code outbox.quarantined})? Anything unparseable is
     * NOT a notification (fail toward the old behavior — a malformed envelope
     * is someone else's poison path, not this guard's).
     */
    static boolean isQuarantineNotification(OutboxKind kind, String payload,
            tools.jackson.databind.ObjectMapper objectMapper) {
        if (kind != OutboxKind.DOMAIN_EVENT || payload == null || objectMapper == null) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = objectMapper.readValue(payload, Map.class);
            return DomainEventType.OUTBOX_QUARANTINED.getValue().equals(envelope.get("type"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Drops an undeliverable quarantine notification WITH accounting: marks
     * the row published (terminal — never redelivered, never re-emitted) and
     * counts it. Emits NOTHING — that is the loop cut.
     */
    static void dropUndeliverable(OutboxEntry entry, OutboxRepository outboxRepository,
            BpmMetrics bpmMetrics) {
        outboxRepository.markPublished(entry.getId());
        bpmMetrics.domainEventUnroutable(DomainEventType.OUTBOX_QUARANTINED.getValue());
        log.warn("WO-REL-66: quarantine notification {} undeliverable "
            + "(no route on zorrobpm.events) — dropped with accounting "
            + "(not quarantined, no re-emit)", entry.getId());
    }
}
