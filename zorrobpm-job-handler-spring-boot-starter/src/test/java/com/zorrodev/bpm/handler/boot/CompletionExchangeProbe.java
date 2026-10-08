package com.zorrodev.bpm.handler.boot;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * WO-INT-10: тестовый хелпер проводки completion через выделенный exchange.
 *
 * <p>Прод-объявление топологии — за движком
 * ({@code RabbitConfiguration.completionsExchange + *Binding}); rabbit-IT
 * стартера живут без engine-контекста, поэтому exchange + identity-биндинг
 * (ключ = имя очереди) объявляют сами — ТЕМ ЖЕ способом (durable direct,
 * ключ равен имени), что прод-бины. Расхождение имен ловит
 * {@code CompletionPublishDeniedTest.topologyNames_pinEngineSideMirror}.
 */
final class CompletionExchangeProbe {

    private CompletionExchangeProbe() {
    }

    /** Объявляет exchange (идемпотентно) + identity-биндинг очереди к нему. */
    static void bindQueue(RabbitAdmin admin, String queueName) {
        admin.declareExchange(
            new DirectExchange(CompletionTopology.COMPLETION_EXCHANGE, true, false));
        admin.declareQueue(new Queue(queueName, true, false, false));
        bindExisting(admin, queueName);
    }

    /**
     * Только exchange + биндинг — очередь уже объявлена со своими аргументами
     * (poison/delay: переобъявление голой очередью дало бы 406 на несовпадении
     * аргументов — тот же класс, что REL-51).
     */
    static void bindExisting(RabbitAdmin admin, String queueName) {
        admin.declareExchange(
            new DirectExchange(CompletionTopology.COMPLETION_EXCHANGE, true, false));
        admin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
            CompletionTopology.COMPLETION_EXCHANGE, queueName, null));
    }
}
