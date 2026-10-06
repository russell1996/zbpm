package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WO-REL-64: повторная доставка из poison-очереди отдельным слушателем.
 *
 * <p>Мутации, которые обязаны ронять эти тесты: слать повтор НОВЫМ телом
 * (assert на completionId); подтвердить poison-вход при неудавшемся повторе
 * (assert на throw); дропнуть битую оболочку молча без счётчика
 * (assert на retryMalformedCount); не ставить TTL на delay-копию (assert на
 * expiration); потерять счётчик попыток (assert на заголовок).
 */
@ExtendWith(MockitoExtension.class)
class CompletionPoisonRetryTest {

    @Mock private RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CompletionPoisonRetryListener retryListener;

    private static final String COMPLETE_QUEUE = "q-complete";

    @BeforeEach
    void setUp() {
        retryListener = new CompletionPoisonRetryListener(rabbitTemplate, objectMapper,
            COMPLETE_QUEUE);
        org.springframework.amqp.rabbit.connection.ConnectionFactory cf =
            org.mockito.Mockito.mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        org.mockito.Mockito.lenient().when(cf.isPublisherConfirms()).thenReturn(false);
        org.mockito.Mockito.lenient().when(rabbitTemplate.getConnectionFactory()).thenReturn(cf);
    }

    private static ServiceTaskCompleteData parkedResult() {
        ServiceTaskCompleteData data = new ServiceTaskCompleteData();
        data.setServiceTaskId(UUID.randomUUID());
        data.setStatus("SUCCESS");
        ProcessVariable v = new ProcessVariable();
        v.setName("x");
        v.setValue("1");
        v.setType("STRING");
        data.setVariables(List.of(v));
        data.setCompletionId("completion-" + UUID.randomUUID());
        return data;
    }

    private Message poisonMessage(ServiceTaskCompleteData data, int attempts) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(data);
        MessageProperties props = new MessageProperties();
        props.setCorrelationId(data.getCompletionId());
        if (attempts > 0) {
            props.setHeader(CompletionPoisonRetryListener.HDR_ATTEMPTS, attempts);
        }
        return new Message(body, props);
    }

    /** Маршрут починен: отправка в очередь completion'ов проходит. */
    private void routeIsFixed() {
        doAnswer(inv -> null).when(rabbitTemplate)
            .convertAndSend(anyString(), (Object) any(),
                any(org.springframework.amqp.core.MessagePostProcessor.class),
                any(CorrelationData.class));
    }

    /** Маршрут всё ещё бит: любая отправка бросает. */
    private void routeStillBroken() {
        doAnswer(inv -> {
            throw new AmqpException("NO_ROUTE");
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
    }

    @Test
    void fixedRoute_retryDeliversSameBodyAndAcks() throws Exception {
        routeIsFixed();
        ServiceTaskCompleteData parked = parkedResult();

        // Нормальный возврат — poison-вход ACK'ается.
        retryListener.onMessage(poisonMessage(parked, 10));

        assertThat(retryListener.retryDeliveredCountForTest()).isEqualTo(1L);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(COMPLETE_QUEUE), bodyCaptor.capture(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
        assertThat(bodyCaptor.getValue()).isInstanceOf(ServiceTaskCompleteData.class);
        ServiceTaskCompleteData delivered = (ServiceTaskCompleteData) bodyCaptor.getValue();
        assertThat(delivered.getCompletionId())
            .as("повтор доставляет ТОТ ЖЕ результат (тот же completionId) — "
                + "движок дедуплицирует, эффект ровно один раз")
            .isEqualTo(parked.getCompletionId());
        assertThat(delivered.getStatus()).isEqualTo("SUCCESS");
        assertThat(delivered.getVariables()).hasSize(1);
    }

    @Test
    void brokenRoute_retryReparksToDelayWithTtlAndAcksPoisonInput() throws Exception {
        // Основная очередь бита, delay-очередь жива.
        doAnswer(inv -> {
            String routingKey = inv.getArgument(0);
            if (COMPLETE_QUEUE.equals(routingKey)) {
                throw new AmqpException("NO_ROUTE");
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
        ServiceTaskCompleteData parked = parkedResult();

        // Нормальный возврат — poison-вход ACK'ается (копия уже в delay).
        retryListener.onMessage(poisonMessage(parked, 10));

        assertThat(retryListener.retryReparkedCountForTest()).isEqualTo(1L);
        assertThat(retryListener.retryDeliveredCountForTest()).isZero();

        ArgumentCaptor<org.springframework.amqp.core.MessagePostProcessor> mppCaptor =
            ArgumentCaptor.forClass(org.springframework.amqp.core.MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
            eq(CompletionPoisonRetryListener.RETRY_DELAY_QUEUE), any(Object.class),
            mppCaptor.capture(), any(CorrelationData.class));

        // Заголовки delay-копии: счётчик +1, TTL следующей ступени шкалы.
        Message probe = new Message("x".getBytes(StandardCharsets.UTF_8),
            new MessageProperties());
        Message processed = mppCaptor.getValue().postProcessMessage(probe);
        assertThat(processed.getMessageProperties().getHeaders())
            .containsEntry(CompletionPoisonRetryListener.HDR_ATTEMPTS, 11);
        assertThat(processed.getMessageProperties().getHeaders())
            .containsKey(CompletionPoisonRetryListener.HDR_REASON);
        assertThat(processed.getMessageProperties().getExpiration())
            .as("per-message TTL = задержка следующей попытки по той же шкале 1с→30с")
            .isEqualTo(String.valueOf(CompletionRedeliveryBackoff.delayFor(11)));
    }

    @Test
    void delayQueueDown_retryThrowsSoPoisonInputIsNotAcked() {
        routeStillBroken();

        assertThatThrownBy(() -> retryListener.onMessage(poisonMessage(parkedResult(), 10)))
            .as("некуда перепаковаться — вход НЕ подтверждается, копия вернётся позже")
            .isInstanceOf(AmqpException.class);
        assertThat(retryListener.retryReparkedCountForTest()).isZero();
    }

    @Test
    void malformedBody_droppedTerminallyWithCounter_notRetried() {
        MessageProperties props = new MessageProperties();
        props.setCorrelationId("corr-garbage");
        Message garbage =
            new Message("not-a-completion{{{".getBytes(StandardCharsets.UTF_8), props);

        // Нормальный возврат (ACK): перекручивать мусор вечно запрещено —
        // та же философия, что malformed job payload в JobCompletionListener.
        retryListener.onMessage(garbage);

        assertThat(retryListener.retryMalformedCountForTest()).isEqualTo(1L);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
    }

    @Test
    void missingCompletionId_droppedTerminally_engineCouldNotDedup() throws Exception {
        ServiceTaskCompleteData noId = parkedResult();
        noId.setCompletionId(null);

        retryListener.onMessage(poisonMessage(noId, 10));

        assertThat(retryListener.retryMalformedCountForTest())
            .as("без стабильного id движок не дедуплицирует — доставка рискует "
                + "двойным эффектом, поэтому терминальный дроп со счётчиком")
            .isEqualTo(1L);
        verify(rabbitTemplate, never()).convertAndSend(eq(COMPLETE_QUEUE), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
    }
}
