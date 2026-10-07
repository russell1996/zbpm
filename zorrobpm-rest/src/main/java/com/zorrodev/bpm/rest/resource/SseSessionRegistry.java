package com.zorrodev.bpm.rest.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * WO-AUDIT-9 (шаг 3): реестр подписчиков/соединений и жизненный цикл сессий —
 * бывшие {@code clients}, {@code clientsPerSubject}, капсы {@code maxClients} /
 * {@code maxClientsPerSubject} и heartbeat-механика {@link SseEventStreamService},
 * перенесённые построчно, без смены семантики.
 *
 * <p>Лимиты приходят suppliers (значения {@code @Value} живут на фасаде —
 * reflection-тесты {@code setField(svc, "maxClients", …)} продолжают работать
 * без правок). Планирование heartbeat — через {@link Scheduler} (фасад отдаёт
 * свой bounded retry-lane, нового пула не заводится — как раньше).
 *
 * <p>Потокобезопасность: регистрация/снятие — под монитором карты
 * {@code sessions} (как раньше {@code synchronized (clients)} — глобальный кап
 * и per-subject счётчик меняются атомарно); итерации — по snapshot-копиям.
 */
@Slf4j
public final class SseSessionRegistry {

    /**
     * Планирование периодических задач (heartbeat). Реализация — фасад
     * (его bounded retry-lane).
     */
    public interface Scheduler {
        ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long intervalMs);
    }

    private final Map<String, SseClientSession> sessions = new ConcurrentHashMap<>();

    /** Live registrations per subject key (WO-SEC-67 per-subject cap). */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> sessionsPerSubject =
        new ConcurrentHashMap<>();

    private final Supplier<Integer> maxClients;
    private final Supplier<Integer> maxClientsPerSubject;
    private final Supplier<Long> heartbeatIntervalMs;
    private final Scheduler scheduler;

    public SseSessionRegistry(Supplier<Integer> maxClients,
            Supplier<Integer> maxClientsPerSubject,
            Supplier<Long> heartbeatIntervalMs,
            Scheduler scheduler) {
        this.maxClients = maxClients;
        this.maxClientsPerSubject = maxClientsPerSubject;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.scheduler = scheduler;
    }

    /** WO-REL-57: the armed periodic heartbeat (null = no clients yet / stopped). */
    private volatile ScheduledFuture<?> heartbeatFuture;

    /**
     * Регистрация новой сессии. Капсы проверяются И инкрементятся под одним
     * локом с глобальным капом — счётчик не может обогнать проверку.
     *
     * @throws ResponseStatusException 429 при превышении любого капа
     */
    public void add(SseClientSession session, String subjectKey) {
        synchronized (sessions) {
            if (sessions.size() >= maxClients.get()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many SSE clients");
            }
            // WO-SEC-67: per-subject cap — checked AND incremented under the
            // same lock as the global cap so the count cannot race.
            java.util.concurrent.atomic.AtomicInteger subjectCount =
                sessionsPerSubject.computeIfAbsent(subjectKey, k -> new java.util.concurrent.atomic.AtomicInteger(0));
            if (subjectCount.get() >= maxClientsPerSubject.get()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many SSE clients for this subject");
            }
            sessions.put(session.clientId(), session);
            subjectCount.incrementAndGet();
        }
    }

    /**
     * WO-REL-37: единая точка снятия клиентского состояния. WO-REL-47: буфер
     * пересечения и режим живут ВНУТРИ сессии (её writer), а не в
     * отдельных мапах — снимать нечего, кроме самой записи (disconnect до
     * drain больше не может оставить висячую queue: негде). Без этого
     * disconnect до drain оставлял запись в bufferedEvents навсегда (утечка,
     * поймана тестом WO-PERF-7: вторая Map в классе).
     *
     * <p>WO-REL-47 HOLD finding 4: снятие состояния обязано закрывать и сам
     * writer ({@code closeWriter()} → {@code Mode.CLOSED}): иначе уже
     * запущенный pump/send для отозванного или отключившегося клиента позже
     * зовёт {@code emitter.send()} на уже complete()-нутом emitter →
     * {@code IllegalStateException} в misleading-ветке «send error» вместо
     * тихого no-op. WO-SEC-67 red-team #3: per-subject слот освобождается здесь
     * же (иначе слот течёт).
     */
    public SseClientSession remove(String clientId, String subjectKey) {
        SseClientSession removed = sessions.remove(clientId);
        if (removed != null) {
            removed.closeWriter();
        }
        // WO-SEC-67: release the per-subject slot (no-op when the client was
        // never registered — e.g. double completion callbacks).
        if (removed != null) {
            java.util.concurrent.atomic.AtomicInteger subjectCount =
                sessionsPerSubject.get(subjectKey);
            if (subjectCount != null && subjectCount.decrementAndGet() <= 0) {
                sessionsPerSubject.remove(subjectKey, subjectCount);
            }
        }
        return removed;
    }

    public SseClientSession get(String clientId) {
        return sessions.get(clientId);
    }

    public boolean contains(String clientId) {
        return sessions.containsKey(clientId);
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    public int size() {
        return sessions.size();
    }

    /** Snapshot для итераций (heartbeat, fan-out, revoke-sweep, shutdown). */
    public Collection<SseClientSession> snapshot() {
        return List.copyOf(sessions.values());
    }

    /** Очистка реестра при shutdown (writer'ы закрываются вызывающим). */
    public void clear() {
        sessions.clear();
    }

    /**
     * WO-REL-57: arm the periodic heartbeat (idempotent, lazy). The first
     * registration starts it; ticks go on the shared scheduler (no new pool).
     * A tick with zero sessions is a no-op; each tick re-checks liveness per
     * session under the session lock (a session that closed between ticks is
     * skipped, never sent into).
     */
    public synchronized void ensureHeartbeat() {
        ScheduledFuture<?> armed = heartbeatFuture;
        if (armed != null && !armed.isDone()) {
            return;
        }
        try {
            heartbeatFuture = scheduler.scheduleAtFixedRate(
                this::sendHeartbeatToAll, heartbeatIntervalMs.get());
        } catch (java.util.concurrent.RejectedExecutionException re) {
            log.warn("SSE heartbeat arming rejected (scheduler saturated) — "
                + "next registration re-arms", re);
            heartbeatFuture = null;
        }
    }

    /** WO-REL-57: cancel the heartbeat (stop() path; re-armed lazily). */
    public synchronized void cancelHeartbeat() {
        ScheduledFuture<?> armed = heartbeatFuture;
        heartbeatFuture = null;
        if (armed != null) {
            armed.cancel(false);
        }
    }

    /**
     * WO-REL-57: one heartbeat tick — a comment to every LIVE session.
     * Package-visible for tests (the interval itself is wall-clock).
     * Never throws into the scheduler (a rogue session must not kill the
     * periodic task — {@code scheduleWithFixedDelay} cancels itself on an
     * escaping exception).
     */
    void sendHeartbeatToAll() {
        try {
            for (SseClientSession session : sessions.values()) {
                try {
                    session.enqueueHeartbeat();
                } catch (RuntimeException e) {
                    log.warn("SSE heartbeat enqueue failed for client {}", session.clientId(), e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("SSE heartbeat tick failed", e);
        }
    }
}
