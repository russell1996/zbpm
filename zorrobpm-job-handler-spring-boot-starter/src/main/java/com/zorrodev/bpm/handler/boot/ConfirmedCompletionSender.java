package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.TraceHeaders;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WO-REL-64: общая отправка completion-результата с брокерским подтверждением.
 *
 * <p>Выделено из {@code JobCompletionListener.sendCompletion} без изменения
 * поведения (P-24: не копировать хелпер, а переиспользовать): тем же путём теперь
 * идут парковка отравленного результата в poison-очередь и повторная публикация
 * из {@code CompletionPoisonRetryListener} — иначе «надёжная публикация»,
 * которую закрывал WO-C8-36 (CR-13), существовала бы только на основном пути, а
 * обходные писали бы вслепую.
 *
 * <p>Контракт: тело — verbatim {@code ServiceTaskCompleteData} (completionId
 * стабилен, движок дедуплицирует по нему — см. WO-C8-36 HOLD-1); корреляция —
 * per-send {@code CorrelationData}; confirm-wait + returned-check — как в
 * исходном коде, дословно.
 */
final class ConfirmedCompletionSender {

    private ConfirmedCompletionSender() {
    }

    /**
     * Есть ли publisher confirms на фабрике шаблона.
     *
     * <p>Без проверки {@code waitForConfirmsOrDie}-аналог кидает ПОСЛЕ успешной
     * публикации → каждый redelivery плодил бы дубликат (поймано живьём в
     * WO-C8-36: 14k сообщений в очереди). Логика — из
     * {@code JobCompletionListener.confirmsAvailable}, дословно.
     */
    static boolean confirmsAvailable(RabbitTemplate rabbitTemplate) {
        try {
            ConnectionFactory cf = rabbitTemplate.getConnectionFactory();
            return cf != null && cf.isPublisherConfirms();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Отправляет результат и — при {@code waitForConfirm} — дожидается
     * брокерского confirm этой отправки и проверяет возврат (unroutable).
     *
     * <p>WO-INT-10: публикация идёт через ВЫДЕЛЕННЫЙ exchange
     * ({@code CompletionTopology.COMPLETION_EXCHANGE}), routing key равен
     * имени очереди (identity-биндинги объявляет движок). Голый
     * {@code convertAndSend(queue, …)} через default exchange здесь запрещён:
     * брокер проверяет write-право против {@code amq.default}, и воркеру
     * такое право давать нельзя (см. {@code CompletionTopology}).
     *
     * @param exchange exchange публикации (прод — completion-exchange)
     * @param routingKey ключ маршрутизации (прод — имя целевой очереди)
     * @param extraHeaders дополнительные AMQP-заголовки поверх базовых
     *     (correlationId/trace из тела) — например счётчик poison-попыток;
     *     null — только базовые
     * @param expirationMs per-message TTL в мс (для delay-очереди) — null без TTL
     * @param unroutableCounter счётчик пойманных возвратов — null не считать
     * @throws CompletionPublishDeniedException брокер отказал в публикации
     *     (403/access_refused — неверные права воркера, НЕ транспорт):
     *     вызыватель обязан отреагировать иначе, чем на транспорт
     *     (см. {@code JobCompletionListener.handleSendFailure})
     * @throws AmqpException NACK/timeout/interrupt/unroutable — вход НЕ
     *     подтверждать, результат восстанавливается переотправкой
     * @throws IllegalStateException у тела нет completionId — вызывающий обязан
     *     назначить его ДО отправки (стабильность на redelivery)
     */
    static void sendAndConfirm(RabbitTemplate rabbitTemplate,
            String exchange,
            String routingKey,
            ServiceTaskCompleteData completeData,
            Map<String, Object> extraHeaders,
            String expirationMs,
            long confirmTimeoutMs,
            boolean waitForConfirm,
            AtomicLong unroutableCounter) {
        String completionId = completeData.getCompletionId();
        if (completionId == null) {
            throw new IllegalStateException(
                "ServiceTaskCompleteData without completionId must not be published: "
                    + "the id has to be assigned once per send so redeliveries replay it");
        }
        CorrelationData correlationData = new CorrelationData(completionId);
        MessagePostProcessor headers = m -> {
            m.getMessageProperties().setCorrelationId(completionId);
            if (completeData.getTraceParent() != null) {
                m.getMessageProperties().setHeader(TraceHeaders.TRACE_PARENT_HEADER,
                    completeData.getTraceParent());
            }
            if (completeData.getProcessInstanceId() != null) {
                m.getMessageProperties().setHeader(TraceHeaders.PROCESS_INSTANCE_ID_HEADER,
                    completeData.getProcessInstanceId());
            }
            if (extraHeaders != null) {
                extraHeaders.forEach(
                    (k, v) -> m.getMessageProperties().setHeader(k, v));
            }
            if (expirationMs != null) {
                m.getMessageProperties().setExpiration(expirationMs);
            }
            return m;
        };
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, completeData, headers, correlationData);
        } catch (RuntimeException e) {
            // WO-INT-10 (Q3): синхронный 403 — неверные права воркера, не
            // транспорт. Отдельный тип: вызыватель паркует сразу, а не крутит
            // вечный backoff-redelivery без инцидента.
            if (isAccessRefused(e)) {
                throw new CompletionPublishDeniedException(exchange, routingKey, completionId, e);
            }
            throw e;
        }
        if (!waitForConfirm) {
            return;
        }
        try {
            CorrelationData.Confirm confirm =
                correlationData.getFuture().get(confirmTimeoutMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                // WO-INT-10 (Q3): async-форма того же 403 — брокер убил канал
                // отказом, confirm пришёл NACK с текстом отказа в reason
                // (живой прогон: reply-code=403 ACCESS_REFUSED на amq.default).
                // Тоже misconfig, не транспорт.
                if (confirm.getReason() != null
                    && confirm.getReason().toUpperCase(java.util.Locale.ROOT)
                        .contains("ACCESS_REFUSED")) {
                    throw new CompletionPublishDeniedException(
                        exchange, routingKey, completionId,
                        new AmqpException("broker NACKed publish: " + confirm.getReason()));
                }
                throw new AmqpException("Completion " + completionId
                    + " was NACKed by broker: " + confirm.getReason()
                    + " — will be redelivered");
            }
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AmqpException("Completion " + completionId
                + " confirm failed: " + e.getCause(), e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AmqpException("Completion " + completionId
                + " was not confirmed within " + confirmTimeoutMs + "ms"
                + " — will be redelivered", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Completion " + completionId
                + " confirm wait was interrupted — will be redelivered", e);
        }
        // Голый confirm ack НЕ равен доставке: брокер подтверждает ПРИЁМ и для
        // немаршрутизируемого сообщения (сперва basic.return, потом ack).
        // Ответ лежит на самой отправке (CorrelationData.getReturned) — гонки
        // нет, чужой отправки не коснёмся (WO-C8-36, red-team HOLD-6 re-pass).
        org.springframework.amqp.core.ReturnedMessage returnedMessage =
            correlationData.getReturned();
        if (returnedMessage != null) {
            if (unroutableCounter != null) {
                unroutableCounter.incrementAndGet();
            }
            throw new AmqpException("Completion " + completionId
                + " returned as unroutable by broker (mandatory): "
                + returnedMessage.getReplyCode() + " " + returnedMessage.getReplyText()
                + " — will be redelivered");
        }
    }

    /**
     * WO-INT-10 (Q3): это отказ прав (403/access_refused) или транспорт?
     *
     * <p>Сигналы брокера: {@code ShutdownSignalException} с reply-code 403
     * (канал закрыт отказом — типичный синхронный ответ на publish без
     * write-права) либо текст {@code ACCESS_REFUSED} в цепочке причин
     * (Spring оборачивает по-разному на разных путях). Голый 403 без ключевых
     * слов — НЕ denial (не гадаем).
     */
    static boolean isAccessRefused(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof com.rabbitmq.client.ShutdownSignalException sse) {
                Object reason = sse.getReason();
                if (reason instanceof com.rabbitmq.client.AMQP.Channel.Close channelClose
                    && channelClose.getReplyCode() == 403) {
                    return true;
                }
                if (reason instanceof com.rabbitmq.client.AMQP.Connection.Close connectionClose
                    && connectionClose.getReplyCode() == 403) {
                    return true;
                }
            }
            String message = c.getMessage();
            if (message != null
                && message.toUpperCase(java.util.Locale.ROOT).contains("ACCESS_REFUSED")) {
                return true;
            }
        }
        return false;
    }
}
