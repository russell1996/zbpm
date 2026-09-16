package com.zorrodev.bpm.rabbitmq.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WO-REL-42 (WB-001 + P-7): explicit listener tuning instead of Spring AMQP
 * defaults (AUTO-ack, prefetch ~250, unbounded concurrency).
 *
 * <p>Lives in {@code zorrobpm-rabbitmq} (NOT in the job-handler starter):
 * this module is a direct dependency of the app and its
 * {@code com.zorrodev.bpm.rabbitmq} package is component-scanned in
 * production ({@code APP.scanBasePackages}) — the customizer bean is really
 * registered where the boot-autoconfigured
 * {@code SimpleRabbitListenerContainerFactory} lives. A copy in the starter
 * would be dead in prod (the starter is not on any prod classpath — caught by
 * the verifier, WO-REL-42 round 1). No {@code @ConditionalOnBean}: the factory
 * always exists wherever this module is deployed with AMQP; in factory-less
 * sliced contexts the bean is simply never consumed.
 *
 * <p>Both consumer paths go through that factory: {@code @RabbitListener} on
 * {@code zorrobpm.complete-service-task} directly, and the per-type
 * {@code zorrobpm.jobs.*} containers via {@code createListenerContainer()}
 * (copies factory settings; the customizer runs in
 * {@code initializeContainer}). One customizer covers both — no second
 * factory, no per-queue special cases.
 *
 * <p>AcknowledgeMode stays AUTO (the factory default, set explicitly here so
 * the choice is visible and intentional): {@code JobCompletionListener}
 * (WO-REL-36) relies on container-ack-after-return plus its own bounded
 * result-cache idempotency for redeliveries. MANUAL would re-drive
 * {@code handleJob()} on every redelivery for handlers without a
 * correlationId, breaking the exactly-once-effect guarantee REL-36 proves.
 *
 * <p>All numbers are profile properties ({@code application.properties}),
 * not hardcoded — see {@code zorrobpm-app/.../application.properties}
 * for values and rationale.
 */
@Slf4j
@Configuration
public class RabbitListenerTuningConfiguration {

    @Bean
    ContainerCustomizer<SimpleMessageListenerContainer> rel42ListenerTuning(
            @Value("${spring.rabbitmq.listener.simple.prefetch:10}") int prefetch,
            @Value("${spring.rabbitmq.listener.simple.concurrency:3}") int concurrency,
            @Value("${spring.rabbitmq.listener.simple.max-concurrency:5}") int maxConcurrency) {
        return container -> {
            container.setPrefetchCount(prefetch);
            container.setConcurrentConsumers(concurrency);
            container.setMaxConcurrentConsumers(maxConcurrency);
            container.setAcknowledgeMode(AcknowledgeMode.AUTO);
            log.info("WO-REL-42 listener tuning applied: prefetch={}, concurrency={}-{}, ack=AUTO",
                prefetch, concurrency, maxConcurrency);
        };
    }
}
