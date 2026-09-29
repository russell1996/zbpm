package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-59 #2: message/signal correlation is now Compare-And-Swap (UPDATE ... WHERE consumed = false),
 * so exactly ONE concurrent correlation consumes a subscription. Before the fix consume* was void and
 * every correlation "succeeded" — a duplicate correlation (e.g. at-least-once delivery) would fire the
 * boundary/catch event twice. These tests prove the CAS returns true exactly once and false afterwards,
 * including under two real threads racing on the same subscription.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class SubscriptionConsumeCasTests {

    @Autowired private DBService dbService;
    @Autowired private MessageSubscriptionRepository messageSubscriptionRepository;
    @Autowired private SignalSubscriptionRepository signalSubscriptionRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void consumeMessageSubscription_secondCallReturnsFalse_cas() {
        UUID id = saveMessageSub();
        boolean first = transactionTemplate.execute(status -> dbService.consumeMessageSubscription(id));
        boolean second = transactionTemplate.execute(status -> dbService.consumeMessageSubscription(id));
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(messageSubscriptionRepository.findById(id).orElseThrow().isConsumed()).isTrue();
    }

    @Test
    void consumeMessageSubscription_concurrentExactlyOneSucceeds() throws Exception {
        UUID id = saveMessageSub();
        int n = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        Boolean[] results = new Boolean[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    start.await();
                    results[idx] = transactionTemplate.execute(status -> dbService.consumeMessageSubscription(id));
                } catch (Exception e) {
                    results[idx] = false;
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        long wins = 0;
        for (Boolean r : results) if (Boolean.TRUE.equals(r)) wins++;
        assertThat(wins).isEqualTo(1);
        assertThat(messageSubscriptionRepository.findById(id).orElseThrow().isConsumed()).isTrue();
    }

    @Test
    void consumeSignalSubscription_secondCallReturnsFalse_cas() {
        UUID id = saveSignalSub();
        boolean first = transactionTemplate.execute(status -> dbService.consumeSignalSubscription(id));
        boolean second = transactionTemplate.execute(status -> dbService.consumeSignalSubscription(id));
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(signalSubscriptionRepository.findById(id).orElseThrow().isConsumed()).isTrue();
    }

    private UUID saveMessageSub() {
        MessageSubscriptionEntity e = new MessageSubscriptionEntity();
        e.setId(UUID.randomUUID());
        e.setProcessInstanceId(UUID.randomUUID());
        e.setActivityId(UUID.randomUUID());
        e.setMessageName("sec59-msg");
        e.setConsumed(false);
        e.setCreatedAt(Instant.now());
        return transactionTemplate.execute(status -> messageSubscriptionRepository.save(e).getId());
    }

    private UUID saveSignalSub() {
        SignalSubscriptionEntity e = new SignalSubscriptionEntity();
        e.setId(UUID.randomUUID());
        e.setProcessInstanceId(UUID.randomUUID());
        e.setActivityId(UUID.randomUUID());
        e.setSignalName("sec59-sig");
        e.setConsumed(false);
        e.setCreatedAt(Instant.now());
        return transactionTemplate.execute(status -> signalSubscriptionRepository.save(e).getId());
    }
}
