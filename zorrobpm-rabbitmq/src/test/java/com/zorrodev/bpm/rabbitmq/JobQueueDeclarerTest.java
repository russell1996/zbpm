package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.JobQueuesRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WO-REL-16: the declarer is what turns the engine's announcement into an actual queue on the
 * broker, and what keeps the send path from paying a broker round-trip per message.
 */
class JobQueueDeclarerTest {

    private AmqpAdmin amqpAdmin;
    private JobQueueDeclarer declarer;

    @BeforeEach
    void setUp() {
        amqpAdmin = mock(AmqpAdmin.class);
        declarer = new JobQueueDeclarer(amqpAdmin);
    }

    @Test
    void announcementDeclaresDurableQueuePerJobType() {
        declarer.on(new JobQueuesRequested(new LinkedHashSet<>(Set.of("billing", "notify"))));

        // WO-REL-45: each job type now declares TWO queues (its DLQ + the work
        // queue itself) — 2 declareQueue calls per type.
        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin, times(4)).declareQueue(captor.capture());
        assertThat(captor.getAllValues()).extracting(Queue::getName)
            .containsExactlyInAnyOrder(
                "zorrobpm.jobs.billing", "zorrobpm.jobs.billing.dlq",
                "zorrobpm.jobs.notify", "zorrobpm.jobs.notify.dlq");
        assertThat(captor.getAllValues()).allMatch(Queue::isDurable);
    }

    /**
     * WO-REL-45 criterion 2 (wiring half): the work queue carries the DLX args
     * (shared DLX + own DLQ as routing key) — the broker half is proven by
     * {@code JobQueueDlqRabbitIT} against a real broker. POF: revert
     * {@code declare} to a bare durable queue and this goes RED.
     */
    @Test
    void workQueueCarriesDlxArgsPointingAtOwnDlq() {
        declarer.declare("billing");

        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin, times(2)).declareQueue(captor.capture());
        Queue work = captor.getAllValues().stream()
            .filter(q -> q.getName().equals("zorrobpm.jobs.billing"))
            .findFirst().orElseThrow();
        assertThat(work.getArguments())
            .containsEntry("x-dead-letter-exchange", JobQueueDeclarer.JOBS_DLX)
            .containsEntry("x-dead-letter-routing-key", "zorrobpm.jobs.billing.dlq");
        assertThat(JobQueueDeclarer.dlqNameFor("billing"))
            .isEqualTo("zorrobpm.jobs.billing.dlq");
    }

    /**
     * Criterion #4: the send path calls declare() on every message. It must not turn into a broker
     * round-trip per message — that is exactly what the old getQueueInfo() check cost.
     */
    @Test
    void repeatedDeclarationHitsBrokerOnlyOnce() {
        declarer.declare("billing");
        declarer.declare("billing");
        declarer.declare("billing");

        // WO-REL-45: one declare() = DLQ + work queue (2 declareQueue calls);
        // the cache still suppresses the 2nd/3rd declare() entirely.
        verify(amqpAdmin, times(2)).declareQueue(any(Queue.class));
        verify(amqpAdmin, times(1)).declareExchange(
            any(org.springframework.amqp.core.Exchange.class));
    }

    /**
     * Criterion #5/#6: a broker that is down must not propagate, and must not poison the cache —
     * the next attempt (announcement or lazy declare on send) has to retry.
     */
    @Test
    void declarationFailureIsSwallowedAndRetriedNextTime() {
        doThrow(new RuntimeException("broker down")).when(amqpAdmin).declareQueue(any(Queue.class));

        assertThatCode(() -> declarer.declare("billing")).doesNotThrowAnyException();
        verify(amqpAdmin, times(1)).declareQueue(any(Queue.class));

        // broker recovers — the previously failed name must be attempted again, not cached as done.
        // WO-REL-45: a full declare is DLQ + work queue (2 declareQueue calls).
        reset(amqpAdmin);
        declarer.declare("billing");
        verify(amqpAdmin, times(2)).declareQueue(any(Queue.class));
    }

    @Test
    void ignoresNullAndBlankJobTypes() {
        declarer.declare(null);
        declarer.declare("");
        declarer.declare("   ");
        declarer.on(new JobQueuesRequested(null));

        verify(amqpAdmin, never()).declareQueue(any(Queue.class));
    }

    @Test
    void queueNameForUsesJobPrefix() {
        assertThat(JobQueueDeclarer.queueNameFor("billing")).isEqualTo("zorrobpm.jobs.billing");
    }
}
