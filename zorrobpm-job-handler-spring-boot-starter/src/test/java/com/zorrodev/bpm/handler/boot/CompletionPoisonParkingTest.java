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
import org.mockito.stubbing.Answer;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-64: потолок попыток публикации результата + парковка в poison-очередь.
 *
 * <p>До правки отравленный результат (немаршрутизируемый completion,
 * потерянный confirm) крутился в бесконечном backoff 1с→30с на единственном
 * потоке потребителя — основная очередь стояла вечно (измерено в WO-C8-36,
 * тест {@code redeliveryBackoff_singleConsumerThread_blocksHeadOfLine}).
 * После правки N-я неудача подряд паркует результат verbatim в poison-очередь,
 * а вход подтверждается — очередь продолжает течь, повтор идёт отдельным
 * потоком с растущей задержкой.
 *
 * <p>Мутации, которые обязаны ронять эти тесты: убрать вызов
 * {@code handleSendFailure} (вернуть прямой проброс); убрать парковочную ветку;
 * слать в poison НОВОЕ тело вместо переигрываемого (assert на completionId);
 * подтвердить вход ДО парковки (assert на порядок: сначала poison-send, вход
 * ACK — здесь доказывается «парковка опубликована ровно один раз до возврата»).
 */
@ExtendWith(MockitoExtension.class)
class CompletionPoisonParkingTest {

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
        // Записывающий sleeper: иначе 1с+2с реального сна на каждую попытку.
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

    /** Основная очередь бита (sync-throw), парковочная — жива. */
    private void mainQueueDeadPoisonAlive() {
        doAnswer(inv -> {
            String routingKey = inv.getArgument(0);
            if (COMPLETE_QUEUE.equals(routingKey)) {
                throw new AmqpException("NO_ROUTE");
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
    }

    @Test
    void attemptsBelowCeiling_throwSoInputIsNotAcked() {
        mainQueueDeadPoisonAlive();
        Message msg = message(jobJson(), "corr-parking-1");

        // Попытки 1 и 2 из 3: проброс наружу (вход НЕ подтверждается).
        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);
        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);

        assertThat(listener.poisonedCountForTest())
            .as("до потолка — никакой парковки, только горячие переотправки")
            .isZero();
        verify(rabbitTemplate, never()).convertAndSend(eq(POISON_QUEUE), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
    }

    @Test
    void attemptAtCeiling_parksVerbatimAndAcksInput() {
        mainQueueDeadPoisonAlive();
        Message msg = message(jobJson(), "corr-parking-2");

        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);
        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);
        // Третья неудача подряд = потолок: парковка, НОРМАЛЬНЫЙ возврат (ACK входа).
        listener.onMessage(msg);

        assertThat(listener.poisonedCountForTest()).isEqualTo(1L);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(1)).convertAndSend(eq(POISON_QUEUE), bodyCaptor.capture(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
        assertThat(bodyCaptor.getValue()).isInstanceOf(ServiceTaskCompleteData.class);
        ServiceTaskCompleteData parked = (ServiceTaskCompleteData) bodyCaptor.getValue();
        // P-67: assert на КОНКРЕТНОЕ значение — тот же completionId, что ушёл в
        // трёх попытках основной отправки (движок дедуплицирует именно по нему).
        ArgumentCaptor<Object> mainBodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(3)).convertAndSend(eq(COMPLETE_QUEUE), mainBodyCaptor.capture(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
        String attemptedId =
            ((ServiceTaskCompleteData) mainBodyCaptor.getAllValues().get(0)).getCompletionId();
        assertThat(attemptedId).isNotNull();
        assertThat(parked.getCompletionId())
            .as("паркуется ТОТ ЖЕ результат (тот же completionId), а не новый — "
                + "иначе движок не связал бы повтор с дедупом")
            .isEqualTo(attemptedId);
        assertThat(parked.getStatus()).isEqualTo("SUCCESS");

        // Бизнес-эффект — один раз (все три попытки переигрывали кэш).
        verify(handler, times(1)).handleJob(any());
    }

    @Test
    void afterParking_backoffScaleIsReset() {
        mainQueueDeadPoisonAlive();
        Message msg = message(jobJson(), "corr-parking-3");

        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);
        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);
        listener.onMessage(msg);

        ArgumentCaptor<Object> mainBodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, times(3)).convertAndSend(eq(COMPLETE_QUEUE), mainBodyCaptor.capture(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
        String completionId =
            ((ServiceTaskCompleteData) mainBodyCaptor.getAllValues().get(0)).getCompletionId();
        assertThat(listener.redeliveryBackoffForTest().attemptsFor(completionId))
            .as("после парковки шкала сброшена — иначе следующий сбой того же "
                + "completionId начинался бы сразу с потолка")
            .isZero();
    }

    @Test
    void parkingDisabled_throwsForever_neverParks() {
        mainQueueDeadPoisonAlive();
        listener.setPoisonParkingEnabled(false);
        Message msg = message(jobJson(), "corr-no-park");

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> listener.onMessage(msg))
                .as("без парковки — старая семантика: проброс на каждой попытке")
                .isInstanceOf(AmqpException.class);
        }

        verify(rabbitTemplate, never()).convertAndSend(eq(POISON_QUEUE), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
        assertThat(listener.poisonedCountForTest()).isZero();
    }

    @Test
    void attemptsAreCountedPerSend_chronicPoisonDoesNotParkFreshJob() {
        mainQueueDeadPoisonAlive();
        Message chronic = message(jobJson(), "corr-chronic");
        Message fresh = message(jobJson(), "corr-fresh");

        // Хроник дважды упал (2 из 3)…
        assertThatThrownBy(() -> listener.onMessage(chronic)).isInstanceOf(AmqpException.class);
        assertThatThrownBy(() -> listener.onMessage(chronic)).isInstanceOf(AmqpException.class);
        // …а свежая отправка начинает свою шкалу: первая неудача — проброс, не парковка.
        assertThatThrownBy(() -> listener.onMessage(fresh)).isInstanceOf(AmqpException.class);

        assertThat(listener.poisonedCountForTest())
            .as("чужой счётчик не наследуется: свежая отправка паркуется только "
                + "после СВОИХ трёх неудач")
            .isZero();
    }

    @Test
    void parkPublishFails_throwsParkFailure_inputNotAcked() {
        // Обе очереди биты: основная И парковочная.
        doAnswer((Answer<Object>) inv -> {
            throw new AmqpException("broker down");
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
        Message msg = message(jobJson(), "corr-park-fail");

        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);
        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);
        // Потолок достигнут, но сама парковка упала — проброс ОШИБКИ ПАРКОВКИ
        // (вход НЕ подтверждается): результат нельзя ни доставить, ни припарковать,
        // терять его тихим ACK запрещено.
        assertThatThrownBy(() -> listener.onMessage(msg))
            .isInstanceOf(AmqpException.class)
            .hasMessageContaining("broker down");

        assertThat(listener.poisonedCountForTest())
            .as("неудавшаяся парковка не считается припаркованной")
            .isZero();
    }

    @Test
    void maxAttempts_isClampedOnBothSides() {
        listener.setMaxCompletionAttempts(0);
        assertThat(listener.maxCompletionAttemptsForTest())
            .as("0 попыток парковали бы всё не пытаясь — пол 1")
            .isEqualTo(JobCompletionListener.MIN_MAX_COMPLETION_ATTEMPTS);

        listener.setMaxCompletionAttempts(10_000);
        assertThat(listener.maxCompletionAttemptsForTest())
            .as("сотни попыток по 30с — годы горячей очереди вместо парковки — потолок 100")
            .isEqualTo(JobCompletionListener.MAX_MAX_COMPLETION_ATTEMPTS);

        listener.setMaxCompletionAttempts(7);
        assertThat(listener.maxCompletionAttemptsForTest()).isEqualTo(7);
    }
}
