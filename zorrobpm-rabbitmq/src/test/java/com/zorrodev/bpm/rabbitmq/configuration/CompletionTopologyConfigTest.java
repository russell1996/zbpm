package com.zorrodev.bpm.rabbitmq.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-INT-10: движок объявляет completion-exchange и identity-биндинги
 * воркерного контура (complete/poison/delay).
 *
 * <p>Без этих бинов воркер на per-system кредах публиковал бы в exchange, у
 * которого нет маршрута к очередям (unroutable → вечный redelivery), а
 * объявить топологию сам он не вправе (нет configure-права на exchange —
 * 403). Аргументы poison/delay — byte-identical объявлению стартера
 * {@code declarePoisonTopology} (иначе admin-воркер переходного периода
 * получил бы 406 на переобъявлении).
 *
 * <p>Мутации, которые обязаны ронять: убрать биндинг (ключ немаршрутизируем);
 * другой routing key (не identity); лишние аргументы у poison/delay (406).
 */
class CompletionTopologyConfigTest {

    private final RabbitConfiguration config = new RabbitConfiguration();

    @Test
    void completionsExchange_isDurableDirect() {
        DirectExchange exchange = config.completionsExchange();

        assertThat(exchange.getName()).isEqualTo("zorrobpm.completions");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.getType()).isEqualTo("direct");
    }

    @Test
    void bindings_areIdentity_queueNameAsKey() {
        Binding complete = config.completionsCompleteBinding();
        assertThat(complete.getExchange())
            .isEqualTo(RabbitConfiguration.COMPLETIONS_EXCHANGE);
        assertThat(complete.getRoutingKey())
            .as("ключ identity-биндинга complete равен имени очереди")
            .isEqualTo(RabbitConfiguration.COMPLETE_QUEUE);
        assertThat(complete.getDestination())
            .isEqualTo(RabbitConfiguration.COMPLETE_QUEUE);

        Binding poison = config.completionsPoisonBinding();
        assertThat(poison.getExchange())
            .isEqualTo(RabbitConfiguration.COMPLETIONS_EXCHANGE);
        assertThat(poison.getRoutingKey())
            .isEqualTo(RabbitConfiguration.COMPLETION_POISON_QUEUE);
        assertThat(poison.getDestination())
            .isEqualTo(RabbitConfiguration.COMPLETION_POISON_QUEUE);

        Binding delay = config.completionsRetryDelayBinding();
        assertThat(delay.getExchange())
            .isEqualTo(RabbitConfiguration.COMPLETIONS_EXCHANGE);
        assertThat(delay.getRoutingKey())
            .isEqualTo(RabbitConfiguration.COMPLETION_RETRY_DELAY_QUEUE);
        assertThat(delay.getDestination())
            .isEqualTo(RabbitConfiguration.COMPLETION_RETRY_DELAY_QUEUE);
    }

    @Test
    void poisonAndDelayQueues_matchStarterDeclareArguments() {
        Queue poison = config.completionPoisonQueue();

        assertThat(poison.getName())
            .isEqualTo(RabbitConfiguration.COMPLETION_POISON_QUEUE);
        assertThat(poison.isDurable()).isTrue();
        assertThat(poison.getArguments())
            .as("poison — durable без аргументов (как declarePoisonTopology стартера)")
            .isEmpty();

        Queue delay = config.completionRetryDelayQueue();

        assertThat(delay.getName())
            .isEqualTo(RabbitConfiguration.COMPLETION_RETRY_DELAY_QUEUE);
        assertThat(delay.isDurable()).isTrue();
        Map<String, Object> args = delay.getArguments();
        assertThat(args)
            .as("delay — возврат копий в poison через default exchange делает брокер")
            .containsEntry("x-dead-letter-exchange", "")
            .containsEntry("x-dead-letter-routing-key",
                RabbitConfiguration.COMPLETION_POISON_QUEUE);
    }
}
