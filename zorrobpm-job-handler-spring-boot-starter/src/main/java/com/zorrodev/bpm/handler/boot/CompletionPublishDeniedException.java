package com.zorrodev.bpm.handler.boot;

import org.springframework.amqp.AmqpException;

/**
 * WO-INT-10 (Q3): брокер отказал в публикации результата — 403/access_refused
 * (нет write-права: неверные permissions воркера, а не транспортная болезнь).
 *
 * <p>До WO отказ тонул в общем {@code AmqpException}: вызыватель
 * ({@code JobCompletionListener.handleSendFailure}) уходил в бесконечный
 * backoff-redelivery без инцидента — misconfig выглядел как больной брокер.
 * Отдельный тип позволяет отреагировать иначе, чем на транспорт: счётчик +
 * ERROR с exchange/ключом + немедленная парковка в poison (если включена), а
 * не горячий цикл.
 */
class CompletionPublishDeniedException extends AmqpException {

    CompletionPublishDeniedException(String exchange, String routingKey,
            String completionId, Throwable cause) {
        super("Broker refused completion " + completionId + " (access denied "
            + "publishing to exchange '" + exchange + "' with routing key '"
            + routingKey + "' — worker permissions misconfigured, not a "
            + "transport failure): " + cause.getMessage(), cause);
    }
}
