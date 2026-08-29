package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DEBT-1a / WO-SEC-59: CONCURRENCY characterization (safe-net) for the four
 * compare-and-swap (CAS) methods of DBServiceImpl. These are the locking primitives that
 * prevent double-correlation / double-fire in a multinode timer/message poller.
 *
 * FIXES CURRENT ATOMIC BEHAVIOR — does NOT change production code.
 *
 * Each test seeds exactly one consumable row, then runs TWO real threads that both call
 * the CAS method on that same row. Exactly one thread must win (return true); the other
 * must lose (return false). A plain findById+save would let BOTH win -> double event,
 * which is the bug these CAS methods were introduced to prevent (WO-SEC-59 #2, WO-REL-13).
 *
 * @Tag("pg") — requires a real PostgreSQL (row locks / SKIP LOCKED behave differently on H2).
 * Collected by the ci/test:pg job (zorrobpm-engine -am). Excluded from the default H2 build.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
class DBServiceImplCasRacePgIT {

    @Autowired private DBService dbService;
    @Autowired private MessageSubscriptionRepository messageSubscriptionRepository;
    @Autowired private SignalSubscriptionRepository signalSubscriptionRepository;
    @Autowired private TimerJobRepository timerJobRepository;
    @Autowired private TimerStartJobRepository timerStartJobRepository;

    private UUID cleanupMessage;
    private UUID cleanupSignal;
    private UUID cleanupTimer;
    private UUID cleanupTimerStart;

    @AfterEach
    void cleanup() {
        if (cleanupMessage != null) messageSubscriptionRepository.deleteById(cleanupMessage);
        if (cleanupSignal != null) signalSubscriptionRepository.deleteById(cleanupSignal);
        if (cleanupTimer != null) timerJobRepository.deleteById(cleanupTimer);
        if (cleanupTimerStart != null) timerStartJobRepository.deleteById(cleanupTimerStart);
        cleanupMessage = cleanupSignal = cleanupTimer = cleanupTimerStart = null;
    }

    @Test
    void consumeMessageSubscription_onlyOneWins() throws Exception {
        UUID id = UUID.randomUUID();
        cleanupMessage = id;
        MessageSubscriptionEntity e = new MessageSubscriptionEntity();
        e.setId(id);
        e.setProcessInstanceId(UUID.randomUUID());
        e.setActivityId(UUID.randomUUID());
        e.setMessageName("race-msg");
        e.setConsumed(false);
        e.setCreatedAt(Instant.now());
        messageSubscriptionRepository.saveAndFlush(e);
        assertThat(messageSubscriptionRepository.findById(id).orElseThrow().isConsumed()).isFalse();

        AtomicBoolean r0 = new AtomicBoolean();
        AtomicBoolean r1 = new AtomicBoolean();
        race(() -> r0.set(dbService.consumeMessageSubscription(id)),
             () -> r1.set(dbService.consumeMessageSubscription(id)));

        assertThat(r0.get() ^ r1.get()).as("exactly one thread consumed the message subscription").isTrue();
    }

    @Test
    void consumeSignalSubscription_onlyOneWins() throws Exception {
        UUID id = UUID.randomUUID();
        cleanupSignal = id;
        SignalSubscriptionEntity e = new SignalSubscriptionEntity();
        e.setId(id);
        e.setProcessInstanceId(UUID.randomUUID());
        e.setActivityId(UUID.randomUUID());
        e.setSignalName("race-signal");
        e.setConsumed(false);
        e.setCreatedAt(Instant.now());
        signalSubscriptionRepository.saveAndFlush(e);
        assertThat(signalSubscriptionRepository.findById(id).orElseThrow().isConsumed()).isFalse();

        AtomicBoolean r0 = new AtomicBoolean();
        AtomicBoolean r1 = new AtomicBoolean();
        race(() -> r0.set(dbService.consumeSignalSubscription(id)),
             () -> r1.set(dbService.consumeSignalSubscription(id)));

        assertThat(r0.get() ^ r1.get()).as("exactly one thread consumed the signal subscription").isTrue();
    }

    @Test
    void claimTimerJob_onlyOneWins() throws Exception {
        UUID id = UUID.randomUUID();
        cleanupTimer = id;
        TimerJobEntity e = new TimerJobEntity();
        e.setId(id);
        e.setActivityId(UUID.randomUUID());
        e.setDueAt(Instant.now().minusSeconds(60));
        e.setFired(false);
        e.setCreatedAt(Instant.now());
        timerJobRepository.saveAndFlush(e);

        AtomicBoolean r0 = new AtomicBoolean();
        AtomicBoolean r1 = new AtomicBoolean();
        race(() -> r0.set(dbService.claimTimerJob(id)),
             () -> r1.set(dbService.claimTimerJob(id)));

        assertThat(r0.get() ^ r1.get()).as("exactly one thread claimed the timer job").isTrue();
    }

    @Test
    void claimTimerStartJob_onlyOneWins() throws Exception {
        UUID id = UUID.randomUUID();
        cleanupTimerStart = id;
        TimerStartJobEntity e = new TimerStartJobEntity();
        e.setId(id);
        e.setProcessKey("race-key");
        e.setProcessDefinitionId(UUID.randomUUID());
        e.setElementId("start");
        e.setDueAt(Instant.now().minusSeconds(60));
        e.setFired(false);
        e.setCreatedAt(Instant.now());
        timerStartJobRepository.saveAndFlush(e);

        AtomicBoolean r0 = new AtomicBoolean();
        AtomicBoolean r1 = new AtomicBoolean();
        race(() -> r0.set(dbService.claimTimerStartJob(id)),
             () -> r1.set(dbService.claimTimerStartJob(id)));

        assertThat(r0.get() ^ r1.get()).as("exactly one thread claimed the timer start job").isTrue();
    }

    private void race(Runnable a, Runnable b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Thread t0 = new Thread(() -> { try { start.await(); a.run(); } catch (InterruptedException ignored) {} finally { done.countDown(); } });
        Thread t1 = new Thread(() -> { try { start.await(); b.run(); } catch (InterruptedException ignored) {} finally { done.countDown(); } });
        t0.start();
        t1.start();
        start.countDown();
        done.await();
    }
}
