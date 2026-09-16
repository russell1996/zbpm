package com.zorrodev.bpm.engine.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * WO-REL-33 п.2: ручной circuit breaker + backoff/jitter для SMTP-доставки.
 *
 * <p>Без новой зависимости (Resilience4j проект принципиально не тянет):
 * три состояния (CLOSED → OPEN → HALF_OPEN), порог последовательных сбоев,
 * окно разомкнутой цепи, одна пробная попытка в HALF_OPEN. Backoff с jitter —
 * экспоненциальная задержка со случайным разбросом, чтобы все реплики
 * multi-instance развёртывания не долбили упавший SMTP синхронно.
 *
 * <p>Потокобезопасность: состояние — {@code AtomicReference}, счётчики —
 * {@code AtomicInteger}; проверка-и-переход выполняется под коротким
 * {@code synchronized} только на границах состояний, не на горячем пути.
 */
@Slf4j
@Component
public class SmtpCircuitBreaker {

    /** Возможные состояния цепи. */
    public enum State {
        /** Нормальная работа: вызовы идут во внешнюю систему. */
        CLOSED,
        /** Цепь разомкнута: вызовы отклоняются сразу, без похода в SMTP. */
        OPEN,
        /** Одна пробная попытка разрешена; успех закрывает, сбой открывает снова. */
        HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration openDuration;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant openedAt;
    private final Object transitionLock = new Object();

    public SmtpCircuitBreaker(
            @Value("${zorrobpm.mail.circuit-breaker.failure-threshold:5}") int failureThreshold,
            @Value("${zorrobpm.mail.circuit-breaker.open-duration-seconds:60}") long openDurationSeconds,
            @Value("${zorrobpm.mail.circuit-breaker.base-backoff-ms:1000}") long baseBackoffMs,
            @Value("${zorrobpm.mail.circuit-breaker.max-backoff-ms:30000}") long maxBackoffMs) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = Duration.ofSeconds(Math.max(1, openDurationSeconds));
        this.baseBackoffMs = Math.max(0, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);
    }

    /**
     * Выполняет SMTP-вызов под защитой цепи.
     *
     * @return результат вызова
     * @throws SmtpCircuitOpenException если цепь разомкнута (вызов не выполнялся)
     * @throws RuntimeException исходное исключение вызова при сбое в CLOSED/HALF_OPEN
     */
    public <T> T execute(Supplier<T> call) {
        State current = state.get();
        if (current == State.OPEN) {
            if (Duration.between(openedAt, Instant.now()).compareTo(openDuration) < 0) {
                throw new SmtpCircuitOpenException("SMTP circuit is OPEN — skipping call to unavailable server");
            }
            // Окно вышло: одна проба.
            synchronized (transitionLock) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("SMTP circuit: OPEN → HALF_OPEN (пробная попытка)");
                }
            }
            current = state.get();
        }
        // В HALF_OPEN пропускаем только одну пробу: остальные видят HALF_OPEN как OPEN.
        if (current == State.HALF_OPEN && !tryAcquireProbe()) {
            throw new SmtpCircuitOpenException("SMTP circuit is HALF_OPEN — пробная попытка уже идёт");
        }
        try {
            T result = call.get();
            onSuccess();
            return result;
        } catch (SmtpCircuitOpenException e) {
            throw e;
        } catch (RuntimeException e) {
            onFailure();
            throw e;
        }
    }

    /** Backoff перед следующей попыткой: экспонента от числа сбоев + jitter ±50%. */
    public long backoffMs() {
        int failures = Math.max(1, consecutiveFailures.get());
        long exp = baseBackoffMs * (1L << Math.min(failures - 1, 10));
        long capped = Math.min(exp, maxBackoffMs);
        long jitter = ThreadLocalRandom.current().nextLong(capped / 2, capped + 1);
        return jitter;
    }

    /** Текущее состояние (для метрик/теста). */
    public State getState() {
        return state.get();
    }

    /** Число последовательных сбоев (для метрик/теста). */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    private boolean tryAcquireProbe() {
        return probeInFlight.compareAndSet(false, true);
    }

    private void onSuccess() {
        probeInFlight.set(false);
        consecutiveFailures.set(0);
        State prev = state.getAndSet(State.CLOSED);
        if (prev != State.CLOSED) {
            log.info("SMTP circuit: {} → CLOSED (SMTP снова доступен)", prev);
        }
    }

    private void onFailure() {
        probeInFlight.set(false);
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            synchronized (transitionLock) {
                if (state.get() != State.OPEN) {
                    state.set(State.OPEN);
                    openedAt = Instant.now();
                    log.warn("SMTP circuit: CLOSED/HALF_OPEN → OPEN после {} последовательных сбоев",
                        failures);
                }
            }
        }
    }
}
