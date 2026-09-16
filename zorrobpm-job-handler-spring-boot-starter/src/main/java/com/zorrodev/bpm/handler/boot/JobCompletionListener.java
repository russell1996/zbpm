package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.handler.JobHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * WO-REL-36 (F10): слушатель worker-очереди с разделённой обработкой ошибок.
 *
 * <p>Было: один внешний {@code catch} в {@code HandlerAutoConfiguration} оборачивал
 * И десериализацию входящей джобы, И отправку completion-результата — сбой ОТПРАВКИ
 * логировался как «Failed to deserialize» и тихо возвращался; при AUTO-ack контейнер
 * подтверждал вход, а результат терялся навсегда (watchdog WO-REL-35 такое не чинит).
 *
 * <p>Стало, три раздельные ветки:
 * <ul>
 *   <li>malformed payload (десериализация упала) → тихо, без отправки, без проброса:
 *       poison-сообщение не должно ретраиться вечно; DLQ-политику применяет сам
 *       контейнер/брокер, не этот код;</li>
 *   <li>handler упал (бизнес-ошибка) → completion со статусом FAILED отправляется
 *       как раньше (ретраи движка и инцидент — на стороне engine);</li>
 *   <li>отправка completion упала (transport failure: broker недоступен) →
 *       исключение ПРОБРАСЫВАЕТСЯ наружу, вход НЕ подтверждается (retry/NACK
 *       контейнера), результат не теряется.</li>
 * </ul>
 *
 * <p>Идемпотентность (п.2 WO): ключ — {@code correlationId} входящего сообщения
 * (engine ставит туда outboxId отправки — уникален на каждую джобу, проверено чтением
 * {@code ServiceTaskListener}). Повторная доставка того же сообщения (redelivery после
 * сбоя отправки) НЕ повторяет бизнес-эффект: handler вызывается один раз, а готовый
 * {@code ServiceTaskCompleteData} переотправляется из bounded result-cache. Без
 * correlationId кэшировать нечего — выполняется и отправляется как раньше (честное
 * ограничение, не тихий скип). Кэш bounded (10k/10m, паттерн F38) — утечки нет.
 */
@Slf4j
public class JobCompletionListener implements MessageListener {

    /** Очередь completion'ов движка (зеркало RabbitConfiguration.COMPLETE_QUEUE). */
    public static final String COMPLETE_QUEUE = "zorrobpm.complete-service-task";

    private final JobHandler handler;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String queueName;

    private final Cache<String, ServiceTaskCompleteData> resultCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofMinutes(10))
        .build();

    public JobCompletionListener(JobHandler handler, RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper, String queueName) {
        this.handler = handler;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
    }

    @Override
    public void onMessage(Message message) {
        final JobDetailModel model;
        try {
            model = objectMapper.readValue(message.getBody(), JobDetailModel.class);
        } catch (Exception e) {
            // Malformed payload: тихо, без отправки, без проброса (см. javadoc).
            log.error("Skipping malformed message for queue {}: {}", queueName, e.getMessage());
            return;
        }

        String correlationId = message.getMessageProperties() != null
            ? message.getMessageProperties().getCorrelationId()
            : null;

        ServiceTaskCompleteData cached =
            correlationId != null ? resultCache.getIfPresent(correlationId) : null;
        if (cached != null) {
            // Redelivery: работа уже сделана, переотправляем только результат.
            // Сбой и здесь — тоже проброс (вход не подтверждаем).
            sendCompletion(cached);
            return;
        }

        ServiceTaskCompleteData completeData = new ServiceTaskCompleteData();
        completeData.setServiceTaskId(model.getServiceTaskId());
        try {
            List<ProcessVariable> result = handler.handleJob(model).stream().map(x -> {
                ProcessVariable v = new ProcessVariable();
                v.setName(x.getName());
                v.setValue(x.getValue());
                v.setType(x.getType().toString());
                return v;
            }).toList();
            completeData.setStatus("SUCCESS");
            completeData.setVariables(result);
        } catch (Exception e) {
            // Бизнес-ошибка воркера: FAILED-completion как раньше (ретраи/инцидент — engine).
            completeData.setStatus("FAILED");
            completeData.setErrorMessage(e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            log.warn("Job '{}' handler failed: {}", handler.getJob(), completeData.getErrorMessage());
        }

        if (correlationId != null) {
            resultCache.put(correlationId, completeData);
        }
        sendCompletion(completeData);
    }

    private void sendCompletion(ServiceTaskCompleteData completeData) {
        try {
            rabbitTemplate.convertAndSend(COMPLETE_QUEUE, completeData);
        } catch (AmqpException e) {
            // Transport failure: проброс наружу — контейнер NACK'ает/ретраит вход,
            // результат (уже в resultCache) не теряется. НЕ логируем как deserialize.
            log.warn("Completion send failed (transport), message will be redelivered: {}", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // Не-AMQP сбой отправки (сериализация конвертера и т.п.) — та же семантика.
            log.warn("Completion send failed (transport), message will be redelivered: {}", e.getMessage());
            throw e;
        }
    }

    /** Тест-хук: сколько результатов сейчас закэшировано (не размер кэша Caffeine). */
    Map<String, ServiceTaskCompleteData> cachedResultsForTest() {
        return resultCache.asMap();
    }
}
