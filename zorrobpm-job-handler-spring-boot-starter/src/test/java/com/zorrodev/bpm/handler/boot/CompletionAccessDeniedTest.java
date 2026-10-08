package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * WO-INT-10 (Q3): поведение вызывателя при отказе прав — счётчик + ERROR и
 * попытка парковки вместо молчаливого транспортного цикла.
 *
 * <p>До WO 403 тонул в транспорте: вход не подтверждался, результат крутился в
 * backoff 1с→30с вечно без инцидента — misconfig выглядел как больной брокер.
 * Теперь: отказ считается отдельно ({@code accessDeniedCount}), называется в
 * логе misconfig'ом, шкала попыток не тратится впустую (повтор с теми же
 * правами даст тот же 403). Если ключ парковки пишущийся (частичные права) —
 * результат паркуется сразу и вход подтверждается; при stock-правах
 * парковаться некуда — вход остаётся неподтверждённым, результат — в
 * resultCache, повтор — штатным backoff до починки прав (потери нет,
 * самолечится).
 *
 * <p>P-67: мутация «убрать denial-ветку из handleSendFailure» обязана ронять
 * все три теста (без ветки — ни счётчика, ни попытки парковки, ни типа).
 */
@ExtendWith(MockitoExtension.class)
class CompletionAccessDeniedTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private JobHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JobCompletionListener listener;

    private static final String COMPLETE_QUEUE = "q-complete";
    private static final String POISON_QUEUE = "q-poison";

    @BeforeEach
    void setUp() {
        listener = new JobCompletionListener(handler, rabbitTemplate, objectMapper,
            "q-in", COMPLETE_QUEUE);
        listener.setPoisonQueueName(POISON_QUEUE);
        listener.setMaxCompletionAttempts(3);
        // Записывающий sleeper: иначе секунды реального сна на прогон.
        listener.setRedeliveryBackoff(new CompletionRedeliveryBackoff(millis -> { }));
        org.springframework.amqp.rabbit.connection.ConnectionFactory cf =
            org.mockito.Mockito.mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        org.mockito.Mockito.lenient().when(cf.isPublisherConfirms()).thenReturn(false);
        org.mockito.Mockito.lenient().when(rabbitTemplate.getConnectionFactory()).thenReturn(cf);
        org.mockito.Mockito.lenient().when(handler.handleJob(any())).thenReturn(List.of(outVar()));
    }

    private static ProcessVariable outVar() {
        ProcessVariable v = new ProcessVariable();
        v.setName("x");
        v.setValue("1");
        v.setType("STRING");
        return v;
    }

    private static Message message(String body, String correlationId) {
        MessageProperties props = new MessageProperties();
        props.setCorrelationId(correlationId);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private static String jobJson() {
        return "{\"serviceTaskId\":\"" + UUID.randomUUID() + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"serviceTaskKey\":\"k\",\"job\":\"job1\",\"variables\":{}}";
    }

    /** Любая публикация в complete-ключ — 403, парковочная — жива. */
    private void completeDeniedPoisonAlive() {
        doAnswer(inv -> {
            // WO-INT-10: аргумент 0 — exchange, аргумент 1 — routing key.
            String routingKey = inv.getArgument(1);
            if (COMPLETE_QUEUE.equals(routingKey)) {
                throw denied();
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
    }

    /** Stock-механика отказа: нет write на exchange — парковаться тоже некуда. */
    private void everythingDenied() {
        doAnswer(inv -> {
            throw denied();
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
    }

    private static CompletionPublishDeniedException denied() {
        return new CompletionPublishDeniedException(
            CompletionTopology.COMPLETION_EXCHANGE, COMPLETE_QUEUE,
            "completion-int10-x",
            new AmqpException("ACCESS_REFUSED - write access to exchange "
                + "'zorrobpm.completions' in vhost '/' refused for user 'worker-b'"));
    }

    @Test
    void accessDenied_parkedImmediately_inputAcked_noHotLoop() {
        completeDeniedPoisonAlive();

        // Нормальный возврат = вход подтверждён (в отличие от транспорта):
        // очередь течёт, misconfig не держит голову очереди.
        listener.onMessage(message(jobJson(), "corr-denied-1"));

        assertThat(listener.accessDeniedCountForTest())
            .as("отказ прав посчитан отдельно от транспорта")
            .isEqualTo(1L);
        assertThat(listener.poisonedCountForTest())
            .as("парковка СРАЗУ — шкала попыток при 403 бессмысленна (повтор даст тот же 403)")
            .isEqualTo(1L);
        assertThat(listener.redeliveryCountForTest())
            .as("горячего redelivery-цикла нет — вход не крутится")
            .isZero();

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(CompletionTopology.COMPLETION_EXCHANGE),
            eq(POISON_QUEUE), bodyCaptor.capture(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
        assertThat(bodyCaptor.getValue()).isInstanceOf(ServiceTaskCompleteData.class);
        ServiceTaskCompleteData parked = (ServiceTaskCompleteData) bodyCaptor.getValue();
        assertThat(parked.getCompletionId())
            .as("паркуется ТОТ ЖЕ результат (тот же completionId — движок дедуплицирует)")
            .isNotNull();
        assertThat(parked.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void accessDenied_parkingDisabled_throwsHonestly_resultNotLost() {
        completeDeniedPoisonAlive();
        listener.setPoisonParkingEnabled(false);

        // Осознанный opt-out: честный проброс как транспорт (вход НЕ подтверждён,
        // результат в resultCache — не потерян), но счётчик denial растёт.
        org.assertj.core.api.ThrowableAssert.ThrowingCallable call =
            () -> listener.onMessage(message(jobJson(), "corr-denied-2"));
        assertThatThrownBy(call).isInstanceOf(CompletionPublishDeniedException.class);

        assertThat(listener.accessDeniedCountForTest()).isEqualTo(1L);
        assertThat(listener.poisonedCountForTest()).isZero();
    }

    @Test
    void accessDenied_parkAlsoDenied_backoffAndThrow_inputNotAcked() {
        // Честная stock-механика: отказ = нет write на exchange целиком, значит
        // парковаться через тот же exchange тоже некуда. Вход НЕ подтверждается
        // (результат в resultCache — не потерян), отказ посчитан отдельно от
        // транспорта, повтор — штатным backoff до починки прав.
        // Мутация «denial-ветка глушит проброс» (тихий возврат): этот тест RED —
        // вход был бы подтверждён при недоставленном результате (потеря).
        everythingDenied();

        org.assertj.core.api.ThrowableAssert.ThrowingCallable call =
            () -> listener.onMessage(message(jobJson(), "corr-denied-3"));
        assertThatThrownBy(call).isInstanceOf(CompletionPublishDeniedException.class);

        assertThat(listener.accessDeniedCountForTest())
            .as("отказ прав посчитан отдельно от транспорта")
            .isEqualTo(1L);
        assertThat(listener.poisonedCountForTest())
            .as("парковаться некуда — парковки нет, но и потери нет")
            .isZero();
        assertThat(listener.redeliveryCountForTest())
            .as("отказ ушёл в штатный backoff-хвост, а не потерян молча")
            .isEqualTo(1L);
    }
}
