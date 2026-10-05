package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.exchange.JobDetailModel;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * WO-C8-36 (CR-13): ACK входящего задания связан с надёжной публикацией результата.
 *
 * <p>NACK/confirm-timeout/return → исключение из listener'а (вход НЕ подтверждается,
 * контейнер NACK'ает/ретраит; результат уже в resultCache). Тихий confirm без wait —
 * старый путь (фиксируется тестом с выключенным ensure: RED-контроль,
 * доказывающий, что именно wait/return-check даёт throw).
 *
 * <p>P-67: каждый негативный assert — на КОНКРЕТНОЕ поведение (throw конкретного
 * типа из onMessage), мутация «убрать wait/check» валит тест.
 */
@ExtendWith(MockitoExtension.class)
class CompletionReliablePublishTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private JobHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JobCompletionListener listener;

    @BeforeEach
    void setUp() {
        listener = new JobCompletionListener(handler, rabbitTemplate, objectMapper, "q-in");
        // Прод-wiring: фабрика с publisher confirms (стартер ставит CORRELATED).
        // Без этого listener честно пропускает wait (см. confirmsAvailable).
        org.springframework.amqp.rabbit.connection.ConnectionFactory cf =
            org.mockito.Mockito.mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        // Lenient: confirmsDisabled-тест коротит до probe (стабы не вызываются).
        org.mockito.Mockito.lenient().when(cf.isPublisherConfirms()).thenReturn(true);
        org.mockito.Mockito.lenient().when(rabbitTemplate.getConnectionFactory()).thenReturn(cf);
    }

    private static String jobJson(UUID serviceTaskId) {
        return "{\"serviceTaskId\":\"" + serviceTaskId + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"serviceTaskKey\":\"k\",\"job\":\"job1\",\"variables\":{}}";
    }

    private static Message message(String body, String correlationId) {
        MessageProperties props = new MessageProperties();
        props.setCorrelationId(correlationId);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private static ProcessVariable outVar() {
        ProcessVariable v = new ProcessVariable();
        v.setName("x");
        v.setValue("1");
        v.setType("STRING");
        return v;
    }

    private CorrelationData sentCorrelationData() {
        ArgumentCaptor<CorrelationData> captor = ArgumentCaptor.forClass(CorrelationData.class);
        org.mockito.Mockito.verify(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), captor.capture());
        return captor.getValue();
    }

    @Test
    void confirmNackOrTimeout_throws_inputNotAcked() {
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        doThrow(new AmqpException("NACK from broker")).when(rabbitTemplate)
            .waitForConfirmsOrDie(anyLong());

        // CR-13/крит.5: NACK/timeout обязаны выйти наружу — контейнер не подтвердит вход.
        assertThatThrownBy(() -> listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-nack")))
            .isInstanceOf(AmqpException.class)
            .hasMessageContaining("NACK");
    }

    @Test
    void unroutableReturn_throws_inputNotAcked() {
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        // Return приходит раньше confirm (как на реальном брокере): к моменту wait
        // id уже в returned-сете — confirm ack=true обязан НЕ считаться успехом.
        AtomicReference<String> inFlight = new AtomicReference<>();
        doAnswer(inv -> {
            inFlight.set(((CorrelationData) inv.getArgument(3)).getId());
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));
        doAnswer(inv -> {
            listener.returnedCompletionIdsForTest().add(inFlight.get());
            return null;
        }).when(rabbitTemplate).waitForConfirmsOrDie(anyLong());

        assertThatThrownBy(() -> listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-ret")))
            .isInstanceOf(AmqpException.class)
            .hasMessageContaining("unroutable");

        // Дискриминатор P-67: id, попавший в исключение-путь, — именно тот, что ушёл в send.
        assertThat(inFlight.get()).startsWith("completion-");
    }

    @Test
    void confirmedRoutable_sendsOnce_withPerSendCorrelationData() {
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));

        listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-ok"));

        // Счастливый путь: wait прошёл молча (mock no-op), исключения нет.
        org.mockito.Mockito.verify(rabbitTemplate).waitForConfirmsOrDie(5_000L);
        CorrelationData cd = sentCorrelationData();
        assertThat(cd.getId()).startsWith("completion-");
    }

    @Test
    void confirmsDisabled_syncExceptionsOnly_noWaitNoThrow() {
        // RED-контроль: именно wait/return-check дают throw выше. Со старым
        // поведением (ensure=false) те же NACK/return остаются тихими.
        listener.setEnsurePublisherConfirms(false);
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));

        listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-off"));

        org.mockito.Mockito.verify(rabbitTemplate, org.mockito.Mockito.never())
            .waitForConfirmsOrDie(anyLong());
    }

    @Test
    void dispatchPhase_echoedIntoCompletion_exactValues() {
        // WO-C8-36 (CR-01, сторона воркера): фаза/индекс входящего задания
        // возвращаются в completion без изменений (assert на КОНКРЕТНЫЕ значения).
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        UUID taskId = UUID.randomUUID();
        String body = "{\"serviceTaskId\":\"" + taskId + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"serviceTaskKey\":\"k\",\"job\":\"job1\",\"variables\":{},"
            + "\"dispatchPhase\":\"start\",\"dispatchIndex\":0}";

        listener.onMessage(message(body, "corr-echo"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(rabbitTemplate).convertAndSend(anyString(), payload.capture(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));
        assertThat(payload.getValue()).isInstanceOf(ServiceTaskCompleteData.class);
        ServiceTaskCompleteData sent = (ServiceTaskCompleteData) payload.getValue();
        assertThat(sent.getDispatchPhase()).isEqualTo("start");
        assertThat(sent.getDispatchIndex()).isEqualTo(0);
    }
}
