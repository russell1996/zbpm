package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-42 (verifier round 1, HOLD finding 1): the tuning customizer must be
 * registered in a context shaped like production — i.e. the module under test
 * is on the context classpath and component-scanned. A unit test on the
 * customizer alone cannot catch "right code, dead in prod" (V5): this test
 * fails with NoSuchBeanDefinitionException if the configuration class is moved
 * back to a module no prod context scans.
 *
 * <p>Runs in the rabbit suite (needs the broker only because the shared
 * {@code TestMain} context wires AMQP; no messages are sent here).
 */
@Tag("rabbit")
@ActiveProfiles("test")
@SpringBootTest(classes = TestMain.class, properties = {
    "spring.rabbitmq.host=${RABBITMQ_HOST:localhost}",
    "spring.rabbitmq.port=${RABBITMQ_PORT:5672}",
    "spring.rabbitmq.username=${RABBITMQ_USER:zorrodev}",
    "spring.rabbitmq.password=${RABBITMQ_PASSWORD:zorrodev}"
})
class ListenerTuningContextIT {

    @Autowired private ApplicationContext context;

    @Test
    void tuningCustomizer_registeredInAppShapedContext() {
        assertThat(context.getBeansOfType(
            org.springframework.amqp.rabbit.config.ContainerCustomizer.class))
            .as("exactly one ContainerCustomizer (WO-REL-42) in context")
            .hasSize(1);
        assertThat(context.getBean(
            com.zorrodev.bpm.rabbitmq.configuration.RabbitListenerTuningConfiguration.class))
            .isNotNull();
    }
}
