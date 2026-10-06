package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WO-REL-64: повторная доставка отравленных результатов из poison-очереди.
 *
 * <p>Откуда берутся сообщения: {@code JobCompletionListener} после
 * {@code completion-max-attempts} неудачных публикаций паркует результат сюда
 * (verbatim-тело + заголовки {@code x-poison-attempts}/{@code x-poison-reason})
 * и подтверждает вход — основная очередь свободна. Этот слушатель — на
 * ОТДЕЛЬНОМ потоке потребителя: его попытки никого не блокируют.
 *
 * <p>Цикл повтора: немедленная попытка отправить в очередь completion'ов →
 * успех: ACK poison-копии, результат у движка РОВНО ОДИН раз (тело то же,
 * completionId тот же — движок дедуплицирует по нему, см. WO-C8-36 HOLD-1;
 * handler здесь НЕ вызывается никогда, бизнес-эффект не повторяется) →
 * неудача: та же копия в delay-очередь с per-message TTL
 * ({@code delayFor(попытки+1)}: растёт 1с→…→потолок 30с) и счётчиком +1;
 * брокер по истечении TTL возвращает её сюда через DLX. Потолка числа повторов
 * НЕТ осознанно — результат не дропается никогда (тот же принцип, что backoff
 * в WO-C8-36), цикл стоит оператору один редкий заход в delay-очередь в 30с,
 * а не горячий requeue.
 *
 * <p>Ручной повтор: то же самое делает оператор — вынуть сообщение из
 * {@code zorrobpm.completion.poison} в Management UI и опубликовать тело как
 * есть в {@code zorrobpm.complete-service-task} (или вернуть в poison —
 * слушатель подхватит сам). Тело вербатим, correlationId сообщения равен
 * completionId — путать нечего.
 *
 * <p>Терминальное состояние для битой оболочки (тело — не completion):
 * такое сообщение не доставить никогда и починить нечем — ERROR + счётчик +
 * ACK (иначе poison-очередь без DLX крутила бы его вечно на этом потоке,
 * блокируя чужие повторы). Та же философия, что malformed job payload в
 * {@code JobCompletionListener} (WO-REL-36): повтор лишь усилил бы потерю.
 */
@Slf4j
public class CompletionPoisonRetryListener implements MessageListener {

    /** Очередь припаркованных результатов (durable, без DLX — ACK всегда явный). */
    public static final String POISON_QUEUE = "zorrobpm.completion.poison";

    /**
     * Delay-очередь (durable): сообщения с per-message TTL; по истечении
     * возвращаются в {@link #POISON_QUEUE} через default exchange
     * ({@code x-dead-letter-routing-key}, см.
     * {@code HandlerAutoConfiguration.declarePoisonTopology}).
     */
    public static final String RETRY_DELAY_QUEUE = "zorrobpm.completion.retry-delay";

    /** Сколько публикаций результата уже не удалось (включая парковочную). */
    public static final String HDR_ATTEMPTS = "x-poison-attempts";

    /** Последняя причина недоставки (для оператора, обрезана). */
    public static final String HDR_REASON = "x-poison-reason";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String completeQueueName;

    private boolean ensurePublisherConfirms = true;
    private long confirmTimeoutMs = 5_000L;

    private final AtomicLong retryDeliveredCount = new AtomicLong(0);
    private final AtomicLong retryReparkedCount = new AtomicLong(0);
    private final AtomicLong retryMalformedCount = new AtomicLong(0);
    private final AtomicLong unroutableCount = new AtomicLong(0);

    public CompletionPoisonRetryListener(RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper, String completeQueueName) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.completeQueueName = completeQueueName;
    }

    public void setEnsurePublisherConfirms(boolean ensurePublisherConfirms) {
        this.ensurePublisherConfirms = ensurePublisherConfirms;
    }

    public void setConfirmTimeoutMs(long confirmTimeoutMs) {
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    /** Сколько припаркованных результатов доставлено движку повтором. */
    public long retryDeliveredCountForTest() {
        return retryDeliveredCount.get();
    }

    /** Сколько повторов вернулось в delay-очередь (маршрут всё ещё бит). */
    public long retryReparkedCountForTest() {
        return retryReparkedCount.get();
    }

    /** Сколько poison-копий оказались битой оболочкой (терминально ACK'нуты). */
    public long retryMalformedCountForTest() {
        return retryMalformedCount.get();
    }

    /** Сколько повторов поймано как unroutable. */
    public long unroutableCountForTest() {
        return unroutableCount.get();
    }

    @Override
    public void onMessage(Message message) {
        final ServiceTaskCompleteData data;
        try {
            data = objectMapper.readValue(message.getBody(), ServiceTaskCompleteData.class);
        } catch (Exception e) {
            long n = retryMalformedCount.incrementAndGet();
            log.error("Poisoned completion is not a completion at all (dropped terminally, "
                    + "total={}): {}: {}. The bytes could never be delivered — requeueing would "
                    + "spin this consumer forever (the poison queue has no DLX).",
                n, e.getClass().getName(), e.getMessage(), e);
            return;
        }
        if (data.getCompletionId() == null) {
            long n = retryMalformedCount.incrementAndGet();
            log.error("Poisoned completion without completionId (dropped terminally, total={}): "
                    + "serviceTaskId={}. Without the stable id the engine cannot dedup it — "
                    + "delivering would risk a double effect.", n, data.getServiceTaskId());
            return;
        }
        int attempts = headerAsInt(message, HDR_ATTEMPTS);
        try {
            ConfirmedCompletionSender.sendAndConfirm(rabbitTemplate, completeQueueName, data,
                null, null, confirmTimeoutMs,
                ensurePublisherConfirms
                    && ConfirmedCompletionSender.confirmsAvailable(rabbitTemplate),
                unroutableCount);
        } catch (RuntimeException sendFailure) {
            reparkForLater(data, attempts + 1, sendFailure);
            return;
        }
        long n = retryDeliveredCount.incrementAndGet();
        log.info("Poisoned completion {} delivered to {} on retry (attempts so far={}, "
                + "delivered total={}): route is fixed, main queue was never blocked",
            data.getCompletionId(), completeQueueName, attempts, n);
    }

    /**
     * Маршрут всё ещё бит: копия уходит в delay-очередь с TTL следующей ступени
     * шкалы и возвращается сюда через DLX. Poison-вход при этом ACK'ается —
     * сообщение НЕ крутится горячо на этом потоке и НЕ теряется.
     */
    private void reparkForLater(ServiceTaskCompleteData data, int nextAttempts,
            RuntimeException sendFailure) {
        long delayMs = CompletionRedeliveryBackoff.delayFor(nextAttempts);
        String reason = sendFailure.getClass().getSimpleName()
            + (sendFailure.getMessage() != null
                ? ": " + truncate(sendFailure.getMessage(), 500) : "");
        try {
            ConfirmedCompletionSender.sendAndConfirm(rabbitTemplate, RETRY_DELAY_QUEUE, data,
                Map.of(HDR_ATTEMPTS, nextAttempts, HDR_REASON, reason),
                String.valueOf(delayMs), confirmTimeoutMs,
                ensurePublisherConfirms
                    && ConfirmedCompletionSender.confirmsAvailable(rabbitTemplate),
                null);
        } catch (RuntimeException delayFailure) {
            // Delay-очередь недоступна (брокер в беде): лучше requeue poison-копии,
            // чем тихая потеря — контейнер NACK'ает вход, попробуем позже.
            log.error("Poisoned completion {} could not be reparked for retry "
                    + "(attempts={}, delay={}ms) — input NOT acked, will be redelivered: {}",
                data.getCompletionId(), nextAttempts, delayMs, delayFailure.getMessage(),
                delayFailure);
            throw delayFailure instanceof AmqpException amqp ? amqp
                : new AmqpException("Delay-queue publish failed", delayFailure);
        }
        long n = retryReparkedCount.incrementAndGet();
        log.warn("Poisoned completion {} still undeliverable (attempts={}, next retry in {}ms, "
                + "reparked total={}): {}", data.getCompletionId(), nextAttempts, delayMs, n,
            reason);
    }

    private static int headerAsInt(Message message, String key) {
        if (message.getMessageProperties() == null) {
            return 0;
        }
        Object v = message.getMessageProperties().getHeaders().get(key);
        if (v instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (v != null) {
            try {
                return Math.max(0, Integer.parseInt(v.toString()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
