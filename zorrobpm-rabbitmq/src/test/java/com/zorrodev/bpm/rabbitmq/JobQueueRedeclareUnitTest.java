package com.zorrodev.bpm.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

/**
 * WO-REL-66 (A): {@link JobQueueDeclarer#redeclareForSend} — одноразовая
 * попытка переобъявления после потери очереди на брокере.
 */
class JobQueueRedeclareUnitTest {

    private AmqpAdmin amqpAdmin;
    private JobQueueDeclarer declarer;

    @BeforeEach
    void setUp() {
        amqpAdmin = mock(AmqpAdmin.class);
        declarer = new JobQueueDeclarer(amqpAdmin);
    }

    @Test
    void redeclareForSend_afterQueueLoss_declaresAgain() {
        declarer.declare("billing");
        reset(amqpAdmin);

        assertThat(declarer.redeclareForSend("billing")).isTrue();

        // Полный declare заново: DLQ + рабочая очередь.
        verify(amqpAdmin, times(2)).declareQueue(any(Queue.class));
    }

    @Test
    void redeclareForSend_skipsLegacyPinned() {
        doThrow(preconditionFailed()).when(amqpAdmin)
            .declareQueue(argThat(q -> q != null && q.getName().equals("zorrobpm.jobs.billing")));
        declarer.declare("billing");
        assertThat(declarer.isLegacyDeclared("billing")).isTrue();
        reset(amqpAdmin);

        assertThat(declarer.redeclareForSend("billing")).isFalse();

        verify(amqpAdmin, never()).declareQueue(any(Queue.class));
        verify(amqpAdmin, never()).declareExchange(any());
    }

    @Test
    void redeclareForSend_debouncedInsideWindow() {
        declarer.declare("billing");
        reset(amqpAdmin);

        assertThat(declarer.redeclareForSend("billing")).isTrue();
        // Второй вызов сразу — в окне дебаунса: без повторного round-trip.
        assertThat(declarer.redeclareForSend("billing")).isFalse();

        verify(amqpAdmin, times(2)).declareQueue(any(Queue.class));
    }

    @Test
    void redeclareForSend_blank_returnsFalseWithoutBrokerCalls() {
        assertThat(declarer.redeclareForSend(null)).isFalse();
        assertThat(declarer.redeclareForSend("  ")).isFalse();

        verify(amqpAdmin, never()).declareQueue(any(Queue.class));
    }

    @Test
    void declaredSnapshots_listWorkQueuesAndJobTypes() {
        declarer.declare("billing");
        declarer.declare("notify");

        assertThat(declarer.declaredQueueNames())
            .containsExactlyInAnyOrder("zorrobpm.jobs.billing", "zorrobpm.jobs.notify");
        assertThat(declarer.declaredJobTypes())
            .containsExactlyInAnyOrder("billing", "notify");
    }

    @Test
    void redeclareForSend_countsMetricWithReturnedTrigger() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        declarer.setMeterRegistry(registry);
        declarer.declare("billing");

        declarer.redeclareForSend("billing");

        assertThat(registry.find("zbpm.rabbit.topology.redeclare")
            .tag("trigger", "returned").counter())
            .isNotNull();
        assertThat(registry.get("zbpm.rabbit.topology.redeclare")
            .tag("trigger", "returned").counter().count())
            .isEqualTo(1.0);
    }

    private static AmqpIOException preconditionFailed() {
        AMQP.Channel.Close close = new AMQP.Channel.Close() {
            @Override public int getReplyCode() { return 406; }
            @Override public String getReplyText() { return "PRECONDITION_FAILED"; }
            @Override public int getClassId() { return 50; }
            @Override public int getMethodId() { return 10; }
            @Override public int protocolClassId() { return 50; }
            @Override public int protocolMethodId() { return 10; }
            @Override public String protocolMethodName() { return "queue.declare"; }
        };
        return new AmqpIOException("neutral channel close",
            new ShutdownSignalException(false, false, close, null));
    }
}
