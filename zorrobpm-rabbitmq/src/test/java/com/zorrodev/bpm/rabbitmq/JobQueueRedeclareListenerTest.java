package com.zorrodev.bpm.rabbitmq;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-66 (A): {@link JobQueueRedeclareListener} переобъявляет известные
 * очереди на новом соединении; ({@link RabbitTopologyMonitor} — только
 * читает, ничего не объявляет).
 */
class JobQueueRedeclareListenerTest {

    @Test
    void redeclareAll_declaresEveryKnownType_oncePerRound() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);
        when(declarer.declaredJobTypes()).thenReturn(Set.of("a", "b"));
        ConnectionFactory cf = mock(CachingConnectionFactory.class);
        JobQueueRedeclareListener listener = new JobQueueRedeclareListener(declarer, cf);

        listener.redeclareAll();

        verify(declarer).declare("a");
        verify(declarer).declare("b");
        verify(declarer).countRedeclare("connection");
    }

    @Test
    void redeclareAll_debouncedInsideWindow() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);
        when(declarer.declaredJobTypes()).thenReturn(Set.of("a"));
        ConnectionFactory cf = mock(CachingConnectionFactory.class);
        JobQueueRedeclareListener listener = new JobQueueRedeclareListener(declarer, cf);

        listener.redeclareAll();
        listener.redeclareAll();

        verify(declarer, times(1)).declare("a");
        verify(declarer, times(1)).countRedeclare("connection");
    }

    @Test
    void redeclareAll_emptyKnownSet_declaresNothing() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);
        when(declarer.declaredJobTypes()).thenReturn(Set.of());
        ConnectionFactory cf = mock(CachingConnectionFactory.class);
        JobQueueRedeclareListener listener = new JobQueueRedeclareListener(declarer, cf);

        listener.redeclareAll();

        verify(declarer, never()).declare(org.mockito.ArgumentMatchers.anyString());
        verify(declarer, never()).countRedeclare(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void registerForReconnect_nonCachingFactory_warnsWithoutThrow() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);
        ConnectionFactory plain = mock(ConnectionFactory.class);
        JobQueueRedeclareListener listener = new JobQueueRedeclareListener(declarer, plain);

        org.assertj.core.api.Assertions.assertThatCode(listener::registerForReconnect)
            .doesNotThrowAnyException();
    }

    // --- RabbitTopologyMonitor: read-only ----------------------------------

    @Test
    void monitor_missingQueue_warnsAndDeclaresNothing() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);
        when(declarer.declaredQueueNames()).thenReturn(Set.of("zorrobpm.jobs.gone"));
        AmqpAdmin admin = mock(AmqpAdmin.class);
        when(admin.getQueueInfo("zorrobpm.jobs.gone")).thenReturn(null);
        RabbitTopologyMonitor monitor = new RabbitTopologyMonitor(declarer, admin);

        monitor.checkDeclaredQueues();

        verify(admin).getQueueInfo("zorrobpm.jobs.gone");
        verify(admin, never()).declareQueue(org.mockito.ArgumentMatchers.any());
        verify(admin, never()).deleteQueue(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void monitor_disabled_doesNotTouchBroker() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);
        AmqpAdmin admin = mock(AmqpAdmin.class);
        RabbitTopologyMonitor monitor = new RabbitTopologyMonitor(declarer, admin);
        org.springframework.test.util.ReflectionTestUtils.setField(monitor, "enabled", false);

        monitor.checkDeclaredQueues();

        verify(admin, never()).getQueueInfo(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void monitor_brokerDown_quietReturnWithoutThrow() {
        JobQueueDeclarer declarer = mock(JobQueueDeclarer.class);
        when(declarer.declaredQueueNames()).thenReturn(Set.of("zorrobpm.jobs.a"));
        AmqpAdmin admin = mock(AmqpAdmin.class);
        when(admin.getQueueInfo(org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new RuntimeException("connection refused"));
        RabbitTopologyMonitor monitor = new RabbitTopologyMonitor(declarer, admin);

        org.assertj.core.api.Assertions.assertThatCode(monitor::checkDeclaredQueues)
            .doesNotThrowAnyException();
    }
}
