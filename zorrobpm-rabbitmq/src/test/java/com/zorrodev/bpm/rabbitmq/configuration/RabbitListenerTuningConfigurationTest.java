package com.zorrodev.bpm.rabbitmq.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * WO-REL-42: the tuning customizer applies the profile values (not defaults)
 * to every container it touches.
 *
 * <p>The container exposes no public getters for prefetch/concurrency, so the
 * test verifies the setter calls on a mock (each value asserted individually)
 * plus the one public getter that exists ({@code getAcknowledgeMode}).
 *
 * <p>POF: changing any applied value (e.g. prefetch 10 → 250) makes the
 * corresponding verification RED — the mock records what the customizer
 * actually set, not the property source.
 */
class RabbitListenerTuningConfigurationTest {

    @Test
    void customizer_appliesProfileValues_notDefaults() {
        RabbitListenerTuningConfiguration cfg = new RabbitListenerTuningConfiguration();
        var customizer = cfg.rel42ListenerTuning(10, 3, 5);

        SimpleMessageListenerContainer c = mock(SimpleMessageListenerContainer.class);
        customizer.configure(c);

        verify(c).setPrefetchCount(10);
        verify(c).setConcurrentConsumers(3);
        verify(c).setMaxConcurrentConsumers(5);
        verify(c).setAcknowledgeMode(AcknowledgeMode.AUTO);
    }

    @Test
    void customizer_ackModeIsExplicitAuto() {
        RabbitListenerTuningConfiguration cfg = new RabbitListenerTuningConfiguration();
        var customizer = cfg.rel42ListenerTuning(7, 2, 4);

        SimpleMessageListenerContainer c = new SimpleMessageListenerContainer();
        customizer.configure(c);

        // AUTO is a deliberate choice (see class javadoc: REL-36 idempotency),
        // not an accident of "we forgot to set it". This is the one applied
        // value readable back through a public getter.
        assertThat(c.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
    }
}
