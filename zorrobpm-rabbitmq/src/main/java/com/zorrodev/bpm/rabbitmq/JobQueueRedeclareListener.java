package com.zorrodev.bpm.rabbitmq;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WO-REL-66 (A): redeclares every JVM-known job queue on each new broker
 * connection.
 *
 * <p>Root cause of the 2026-10-07 incident: {@link JobQueueDeclarer} caches
 * declared names to avoid a broker round-trip per send, and the poison/TTL
 * topology is declared once from {@code init()} — after the broker is
 * recreated (queues gone) neither is ever redeclared, while the listener
 * containers keep consuming from missing queues ({@code not_found} storm
 * until a manual app restart).
 *
 * <p>Mechanics: standard Spring AMQP {@link ConnectionListener#onCreate} — a
 * recreated broker means a new physical connection, which is exactly the
 * signal. Declares are idempotent on the broker (identical arguments =
 * no-op), REL-51 legacy-pinned names stay pinned inside
 * {@link JobQueueDeclarer#declare} (terminal for this JVM — criterion 4),
 * the whole round is debounced (connection flaps must not storm the broker)
 * and counted ({@code zbpm.rabbit.topology.redeclare{trigger="connection"}}).
 *
 * <p>Reentrancy: {@code RabbitAdmin} declares open connections on this same
 * factory, so {@code onCreate} can arrive recursively — {@code inFlight}
 * serializes attempts (same guard as the worker-side one-shot redeclare).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobQueueRedeclareListener {

    /**
     * Minimum interval between full redeclare rounds (package-visible for the
     * unit test). Reconnects are network events, not a hot loop — 30s bounds
     * the worst case without delaying the first heal.
     */
    static final long REDECLARE_ALL_DEBOUNCE_MS = 30_000L;

    private final JobQueueDeclarer declarer;
    private final ConnectionFactory connectionFactory;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicLong lastRedeclareAllMs = new AtomicLong(0);
    /**
     * WO-REL-66: the very first connection in this JVM's life is catch-up,
     * not a flap — its round does NOT move the debounce clock (otherwise a
     * broker flap seconds after startup would be skipped while the topology
     * is still unhealed; caught live in the worker-side IT). Debounce guards
     * flap rounds against each other only.
     */
    private final AtomicBoolean catchUpDone = new AtomicBoolean(false);

    @PostConstruct
    void registerForReconnect() {
        if (!(connectionFactory instanceof CachingConnectionFactory cachingCf)) {
            log.warn("WO-REL-66: cannot redeclare job queues on reconnect: "
                + "connection factory is not caching — healing relies on the send-path redeclare");
            return;
        }
        cachingCf.addConnectionListener(new ConnectionListener() {
            @Override
            public void onCreate(Connection connection) {
                redeclareAll();
            }
        });
        log.info("WO-REL-66: job-queue redeclare on broker reconnect registered");
    }

    /** Package-visible for the unit test (fire without a broker). */
    void redeclareAll() {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            Set<String> jobTypes = declarer.declaredJobTypes();
            if (jobTypes.isEmpty()) {
                // Nothing known yet (e.g. the very first connection at startup
                // before any announcement) — NOT a round: neither the debounce
                // clock nor the catch-up flag moves, or a broker flap seconds
                // later would be skipped while the topology is still unhealed
                // (caught live).
                return;
            }
            boolean catchUp = !catchUpDone.getAndSet(true);
            if (!catchUp) {
                long now = System.currentTimeMillis();
                if (now - lastRedeclareAllMs.get() < REDECLARE_ALL_DEBOUNCE_MS) {
                    return;
                }
                lastRedeclareAllMs.set(now);
            }
            // WO-REL-66: force-redeclare, NOT declare — the `declared` cache
            // is intact (the loss is on the broker), so a plain declare()
            // would early-return as a no-op and heal nothing.
            for (String jobType : jobTypes) {
                declarer.forceRedeclare(jobType, "connection");
            }
            log.info("WO-REL-66: redeclared {} known job queue(s) on new broker connection",
                jobTypes.size());
        } finally {
            inFlight.set(false);
        }
    }
}
