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

        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin, times(2)).declareQueue(captor.capture());
        assertThat(captor.getAllValues()).extracting(Queue::getName)
            .containsExactlyInAnyOrder("zorrobpm.jobs.billing", "zorrobpm.jobs.notify");
        assertThat(captor.getAllValues()).allMatch(Queue::isDurable);
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

        verify(amqpAdmin, times(1)).declareQueue(any(Queue.class));
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

        // broker recovers — the previously failed name must be attempted again, not cached as done
        reset(amqpAdmin);
        declarer.declare("billing");
        verify(amqpAdmin, times(1)).declareQueue(any(Queue.class));
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
