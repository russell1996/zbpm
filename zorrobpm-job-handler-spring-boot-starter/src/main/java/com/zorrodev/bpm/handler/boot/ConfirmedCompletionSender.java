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
     * @param extraHeaders дополнительные AMQP-заголовки поверх базовых
     *     (correlationId/trace из тела) — например счётчик poison-попыток;
     *     null — только базовые
     * @param expirationMs per-message TTL в мс (для delay-очереди) — null без TTL
     * @param unroutableCounter счётчик пойманных возвратов — null не считать
     * @throws AmqpException NACK/timeout/interrupt/unroutable — вход НЕ
     *     подтверждать, результат восстанавливается переотправкой
     * @throws IllegalStateException у тела нет completionId — вызывающий обязан
     *     назначить его ДО отправки (стабильность на redelivery)
     */
    static void sendAndConfirm(RabbitTemplate rabbitTemplate,
            String queueName,
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
        rabbitTemplate.convertAndSend(queueName, completeData, headers, correlationData);
        if (!waitForConfirm) {
            return;
        }
        try {
            CorrelationData.Confirm confirm =
                correlationData.getFuture().get(confirmTimeoutMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
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
}
