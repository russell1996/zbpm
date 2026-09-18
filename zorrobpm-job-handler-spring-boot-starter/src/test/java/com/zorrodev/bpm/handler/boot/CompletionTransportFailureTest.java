package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-36 (F10): сбой ОТПРАВКИ completion-результата не должен глотаться.
 *
 * <p>RED-first против текущего кода: внешний catch оборачивает и десериализацию,
 * и {@code convertAndSend} — transport failure логируется как «Failed to
 * deserialize» и тихо возвращается (контейнер AUTO-ack'ает вход, результат
 * потерян навсегда). После фикса отправка обязана пробрасывать исключение
 * наружу (контейнер NACK'ает/ретраит вход), а десериализация — оставаться тихой.
 *
 * <p>Идемпотентность: повторная доставка того же сообщения (тот же correlationId,
 * redelivered) после сбоя отправки НЕ повторяет бизнес-эффект — handler вызывается
 * один раз, а completion переотправляется из кэша результата.
 */
@ExtendWith(MockitoExtension.class)
class CompletionTransportFailureTest {

    @Mock private ApplicationContext applicationContext;
    @Mock private SimpleRabbitListenerContainerFactory connectionFactory;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private AmqpAdmin amqpAdmin;
    @Mock private SimpleMessageListenerContainer container;
    @Mock private JobHandler handler;

    @Captor private ArgumentCaptor<MessageListener> listenerCaptor;

    private HandlerAutoConfiguration configuration;
    private MessageListener listener;

    @BeforeEach
    void setUp() {
        configuration = new HandlerAutoConfiguration(applicationContext, connectionFactory, rabbitTemplate, amqpAdmin);
        when(applicationContext.getBeansOfType(JobHandler.class))
            .thenReturn(Map.of("handler", handler));
        when(handler.getJob()).thenReturn("job1");
        when(connectionFactory.createListenerContainer()).thenReturn(container);
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(mock(org.springframework.amqp.core.QueueInformation.class));
        configuration.init();
        verify(container).setMessageListener(listenerCaptor.capture());
        listener = listenerCaptor.getValue();
    }

    private static String jobJson(UUID serviceTaskId) {
        return "{\"serviceTaskId\":\"" + serviceTaskId + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"serviceTaskKey\":\"k\",\"job\":\"job1\",\"variables\":{}}";
    }

    private static Message message(String body, String correlationId, boolean redelivered) {
        MessageProperties props = new MessageProperties();
        props.setCorrelationId(correlationId);
        props.setRedelivered(redelivered);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private static ProcessVariable outVar() {
        ProcessVariable v = new ProcessVariable();
        v.setName("x");
        v.setValue("1");
        v.setType("STRING");
        return v;
    }

    @Test
    void transportFailureOnCompletionSend_propagatesInsteadOfSilentSuccess() {
        UUID taskId = UUID.randomUUID();
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        doThrow(new AmqpException("broker down")).when(rabbitTemplate)
            .convertAndSend(anyString(), (Object) any(), any(org.springframework.amqp.core.MessagePostProcessor.class));

        // Фикс: исключение отправки обязано выйти наружу (NACK/retry входа).
        // Pre-fix: глотается внешним catch, тихое возвращение (AUTO-ack теряет результат).
        assertThatThrownBy(() -> listener.onMessage(message(jobJson(taskId), "corr-1", false)))
            .isInstanceOf(AmqpException.class);
    }

    @Test
    void malformedPayload_staysQuietAndSendsNothing() {
        // Контракт десериализации: malformed тихо (DLQ-политику контейнера решает
        // контейнер, не этот код), отправки нет. И до, и после фикса — без throw.
        listener.onMessage(message("not-json{{{", "corr-2", false));

        verify(handler, times(0)).handleJob(any());
        verify(rabbitTemplate, times(0)).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class));
    }

    @Test
    void redeliveredSameMessage_doesNotRepeatBusinessEffect() {
        UUID taskId = UUID.randomUUID();
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));

        String body = jobJson(taskId);
        listener.onMessage(message(body, "corr-3", false));
        // Redelivery того же сообщения после сбоя/рестарта: бизнес-эффект один раз.
        listener.onMessage(message(body, "corr-3", true));

        // Pre-fix: handler вызван дважды (эффект повторён). Post-fix: ровно один раз,
        // а completion отправлен дважды (второй — переотправка результата, не работы).
        verify(handler, times(1)).handleJob(any());
        verify(rabbitTemplate, times(2)).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class));
    }

    /**
     * WO-OBS-8: worker-hop trace linkage. Входящие AMQP-заголовки (traceparent +
     * processInstanceId) должны выйти в completion С ТЕМ ЖЕ trace id — assert на
     * КОНКРЕТНОЕ значение из входа (P-67: "что-то отправилось" — не доказательство).
     * Мутация "не копировать заголовки" обязана валить оба assert'а ниже; мутация
     * "не ставить MDC" — MDC-assert'ы (читаются LIVE внутри send — единственная точка,
     * где MDC воркера жив).
     */
    @Test
    void obs8_incomingTraceHeaders_forwardedIntoCompletionWithSameTraceId() {
        UUID taskId = UUID.randomUUID();
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String traceParent = "00-" + traceId + "-b7ad6b7169203331-01";

        MessageProperties props = new MessageProperties();
        props.setCorrelationId("corr-obs8");
        props.setHeader("traceparent", traceParent);
        props.setHeader("processInstanceId", "pi-obs8");
        Message incoming = new Message(jobJson(taskId).getBytes(StandardCharsets.UTF_8), props);

        var seenTraceId = new String[1];
        var seenPi = new String[1];
        org.mockito.stubbing.Answer<Object> captureMdc = invocation -> {
            seenTraceId[0] = org.slf4j.MDC.get("traceId");
            seenPi[0] = org.slf4j.MDC.get("processInstanceId");
            return null;
        };
        // doAnswer на 3-arg перегрузку (именно её зовёт прод-код после OBS-8).
        org.mockito.Mockito.doAnswer(captureMdc).when(rabbitTemplate)
            .convertAndSend(anyString(), (Object) any(),
                any(org.springframework.amqp.core.MessagePostProcessor.class));

        listener.onMessage(incoming);

        // MDC воркера — тот же trace id, что вошёл (не свежий UUID), плюс PI.
        assertThat(seenTraceId[0]).isEqualTo(traceId);
        assertThat(seenPi[0]).isEqualTo("pi-obs8");

        // Тело completion несёт verbatim-forward для engine-стороны.
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<org.springframework.amqp.core.MessagePostProcessor> mppCaptor =
            ArgumentCaptor.forClass(org.springframework.amqp.core.MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(anyString(), bodyCaptor.capture(), mppCaptor.capture());
        assertThat(bodyCaptor.getValue()).isInstanceOf(com.zorrodev.bpm.exchange.ServiceTaskCompleteData.class);
        com.zorrodev.bpm.exchange.ServiceTaskCompleteData sent =
            (com.zorrodev.bpm.exchange.ServiceTaskCompleteData) bodyCaptor.getValue();
        assertThat(sent.getTraceParent()).isEqualTo(traceParent);
        assertThat(sent.getProcessInstanceId()).isEqualTo("pi-obs8");

        // И AMQP-заголовки completion — тот же traceparent (engine-сторона читает их первой).
        org.springframework.amqp.core.Message outMessage =
            new org.springframework.amqp.core.Message("x".getBytes(),
                new MessageProperties());
        try {
            org.springframework.amqp.core.Message processed =
                mppCaptor.getValue().postProcessMessage(outMessage);
            assertThat(processed.getMessageProperties().getHeaders())
                .containsEntry("traceparent", traceParent)
                .containsEntry("processInstanceId", "pi-obs8");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // Гигиена потока контейнера: чужой MDC не утёк наружу.
        assertThat(org.slf4j.MDC.get("traceId")).isNull();
        assertThat(org.slf4j.MDC.get("processInstanceId")).isNull();
    }
}
