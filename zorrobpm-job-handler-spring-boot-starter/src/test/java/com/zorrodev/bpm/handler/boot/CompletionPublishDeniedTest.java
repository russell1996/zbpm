package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * WO-INT-10 (Q3): синхронный отказ брокера по правам (403/access_refused)
 * распознаётся отдельно от транспортной болезни.
 *
 * <p>До WO отказ тонул в общем {@code AmqpException}: вызыватель уходил в
 * бесконечный backoff-redelivery без инцидента — misconfig permissions
 * выглядел как больной брокер. Теперь {@code sendAndConfirm} бросает
 * {@code CompletionPublishDeniedException} с exchange/ключом в сообщении.
 *
 * <p>P-67: каждый тест называет мутацию, которая обязана его ронять
 * (убрать denial-ветку / сузить предикат до одного сигнала).
 */
@ExtendWith(MockitoExtension.class)
class CompletionPublishDeniedTest {

    @Mock private RabbitTemplate rabbitTemplate;

    private static ServiceTaskCompleteData body() {
        ServiceTaskCompleteData data = new ServiceTaskCompleteData();
        data.setServiceTaskId(java.util.UUID.randomUUID());
        data.setCompletionId("completion-int10-" + java.util.UUID.randomUUID());
        data.setStatus("SUCCESS");
        return data;
    }

    private void send(ServiceTaskCompleteData data) {
        ConfirmedCompletionSender.sendAndConfirm(rabbitTemplate,
            CompletionTopology.COMPLETION_EXCHANGE, "q-target", data,
            null, null, 5_000L, true, null);
    }

    /** Брокер закрыл канал 403 — типичный синхронный ответ на publish без write-права. */
    private static AmqpException channelClose403() {
        com.rabbitmq.client.AMQP.Channel.Close close = channelClose(403,
            "ACCESS_REFUSED - write access to exchange 'zorrobpm.completions' "
                + "in vhost '/' refused for user 'worker-b'");
        com.rabbitmq.client.ShutdownSignalException sse =
            new com.rabbitmq.client.ShutdownSignalException(false, false, close, null);
        return new AmqpException("publish failed", sse);
    }

    /**
     * WO: проектный паттерн построения сигналов закрытия (см.
     * {@code JobQueueDeclarerTest.preconditionFailed}): {@code AMQP.*.Close}
     * абстрактны, поэтому анонимный подкласс с reply-code/текстом.
     */
    private static com.rabbitmq.client.AMQP.Channel.Close channelClose(
            int replyCode, String replyText) {
        return new com.rabbitmq.client.AMQP.Channel.Close() {
            @Override public int getReplyCode() { return replyCode; }
            @Override public String getReplyText() { return replyText; }
            @Override public int getClassId() { return 20; }
            @Override public int getMethodId() { return 40; }
            @Override public int protocolClassId() { return 20; }
            @Override public int protocolMethodId() { return 40; }
            @Override public String protocolMethodName() { return "basic.publish"; }
        };
    }

    private static com.rabbitmq.client.AMQP.Connection.Close connectionClose(
            int replyCode, String replyText) {
        return new com.rabbitmq.client.AMQP.Connection.Close() {
            @Override public int getReplyCode() { return replyCode; }
            @Override public String getReplyText() { return replyText; }
            @Override public int getClassId() { return 10; }
            @Override public int getMethodId() { return 50; }
            @Override public int protocolClassId() { return 10; }
            @Override public int protocolMethodId() { return 50; }
            @Override public String protocolMethodName() { return "connection.close"; }
        };
    }

    @Test
    void channelClose403_throwsDeniedWithExchangeAndKey() {
        ServiceTaskCompleteData data = body();
        doThrow(channelClose403()).when(rabbitTemplate).convertAndSend(
            anyString(), anyString(), any(Object.class),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));

        // Мутация «убрать denial-ветку»: здесь был бы голый AmqpException —
        // тест требует КОНКРЕТНЫЙ тип (P-67).
        assertThatThrownBy(() -> send(data))
            .isInstanceOf(CompletionPublishDeniedException.class)
            .hasMessageContaining(CompletionTopology.COMPLETION_EXCHANGE)
            .hasMessageContaining("q-target")
            .hasMessageContaining(data.getCompletionId());
    }

    @Test
    void textualAccessRefused_throwsDenied() {
        ServiceTaskCompleteData data = body();
        doThrow(new AmqpException(
            "ACCESS_REFUSED - write access to exchange 'amq.default' in vhost '/' "
                + "refused for user 'worker-b'"))
            .when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(Object.class),
                any(org.springframework.amqp.core.MessagePostProcessor.class),
                any(CorrelationData.class));

        // Мутация «предикат только по reply-code 403»: текстовый сигнал
        // перестал бы распознаваться — тест требует denial и здесь.
        assertThatThrownBy(() -> send(data))
            .isInstanceOf(CompletionPublishDeniedException.class);
    }

    @Test
    void nestedAccessRefused_throwsDenied() {
        // Spring оборачивает по-разному на разных путях — сигнал может лежать
        // глубже первого уровня.
        ServiceTaskCompleteData data = body();
        RuntimeException wrapped = new AmqpException("channel error",
            new RuntimeException("caused by: (403) ACCESS_REFUSED - write access refused"));
        doThrow(wrapped).when(rabbitTemplate).convertAndSend(
            anyString(), anyString(), any(Object.class),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));

        assertThatThrownBy(() -> send(data))
            .isInstanceOf(CompletionPublishDeniedException.class);
    }

    @Test
    void transportFailure_staysPlainAmqpException_notDenied() {
        ServiceTaskCompleteData data = body();
        doThrow(new AmqpException("NO_ROUTE")).when(rabbitTemplate).convertAndSend(
            anyString(), anyString(), any(Object.class),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));

        // Мутация «предикат всегда true»: транспорт стал бы denial'ом —
        // тест требует РАЗЛИЧЕНИЕ (P-67: over-classification тоже баг).
        assertThatThrownBy(() -> send(data))
            .isInstanceOf(AmqpException.class)
            .isNotInstanceOf(CompletionPublishDeniedException.class);
    }

    @Test
    void channelClose404_isNotDenied() {
        com.rabbitmq.client.AMQP.Channel.Close close =
            channelClose(404, "NOT_FOUND - no queue 'q-x' in vhost '/'");
        com.rabbitmq.client.ShutdownSignalException sse =
            new com.rabbitmq.client.ShutdownSignalException(false, false, close, null);
        ServiceTaskCompleteData data = body();
        doThrow(new AmqpException("not found", sse)).when(rabbitTemplate).convertAndSend(
            anyString(), anyString(), any(Object.class),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));

        assertThatThrownBy(() -> send(data))
            .isInstanceOf(AmqpException.class)
            .isNotInstanceOf(CompletionPublishDeniedException.class);
    }

    @Test
    void bare403NumberWithoutKeyword_isNotDenied() {
        // Голый «403» в тексте без ACCESS_REFUSED — не гадаем (не наш сигнал).
        ServiceTaskCompleteData data = body();
        doThrow(new AmqpException("request failed with status 403")).when(rabbitTemplate)
            .convertAndSend(anyString(), anyString(), any(Object.class),
                any(org.springframework.amqp.core.MessagePostProcessor.class),
                any(CorrelationData.class));

        assertThatThrownBy(() -> send(data))
            .isInstanceOf(AmqpException.class)
            .isNotInstanceOf(CompletionPublishDeniedException.class);
    }

    @Test
    void isAccessRefused_matrix() {
        com.rabbitmq.client.AMQP.Channel.Close close403 =
            channelClose(403, "ACCESS_REFUSED");
        assertThat(ConfirmedCompletionSender.isAccessRefused(new AmqpException("x",
            new com.rabbitmq.client.ShutdownSignalException(false, false, close403, null))))
            .as("channel close 403").isTrue();
        com.rabbitmq.client.AMQP.Connection.Close conn403 =
            connectionClose(403, "ACCESS_REFUSED");
        assertThat(ConfirmedCompletionSender.isAccessRefused(new AmqpException("x",
            new com.rabbitmq.client.ShutdownSignalException(true, false, conn403, null))))
            .as("connection close 403").isTrue();
        assertThat(ConfirmedCompletionSender.isAccessRefused(
            new AmqpException("ACCESS_REFUSED - write access refused")))
            .as("bare text").isTrue();
        com.rabbitmq.client.AMQP.Channel.Close close404 =
            channelClose(404, "NOT_FOUND");
        assertThat(ConfirmedCompletionSender.isAccessRefused(new AmqpException("x",
            new com.rabbitmq.client.ShutdownSignalException(false, false, close404, null))))
            .as("channel close 404").isFalse();
        assertThat(ConfirmedCompletionSender.isAccessRefused(new AmqpException("NO_ROUTE")))
            .as("transport text").isFalse();
        assertThat(ConfirmedCompletionSender.isAccessRefused(new AmqpException((String) null)))
            .as("null message").isFalse();
    }

    @Test
    void asyncNackWithAccessRefusedReason_throwsDenied() {
        // Живая форма из MUT-5: брокер убил канал 403, confirm пришёл NACK с
        // текстом отказа в reason. Мутация «убрать NACK-ветку» роняет ровно здесь
        // (голый AmqpException вместо Denied — misconfig снова неотличим).
        ServiceTaskCompleteData data = body();
        org.mockito.Mockito.doAnswer(inv -> {
            CorrelationData cd =
                inv.getArgument(4, CorrelationData.class);
            cd.getFuture().complete(new CorrelationData.Confirm(false,
                "channel error; protocol method: #method<channel.close>(reply-code=403, "
                    + "reply-text=ACCESS_REFUSED - write access to exchange 'amq.default' "
                    + "in vhost '/' refused for user 'worker-b', class-id=60, method-id=40)"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            anyString(), anyString(), any(Object.class),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));

        assertThatThrownBy(() -> send(data))
            .isInstanceOf(CompletionPublishDeniedException.class)
            .hasMessageContaining(CompletionTopology.COMPLETION_EXCHANGE);
    }

    @Test
    void asyncNackWithoutRefusedReason_staysPlainAmqpException() {
        ServiceTaskCompleteData data = body();
        org.mockito.Mockito.doAnswer(inv -> {
            CorrelationData cd =
                inv.getArgument(4, CorrelationData.class);
            cd.getFuture().complete(new CorrelationData.Confirm(false, "channel error: timeout"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            anyString(), anyString(), any(Object.class),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));

        assertThatThrownBy(() -> send(data))
            .isInstanceOf(AmqpException.class)
            .isNotInstanceOf(CompletionPublishDeniedException.class);
    }

    @Test
    void publish_goesThroughCompletionExchangeWithQueueAsKey() {
        ServiceTaskCompleteData data = body();
        ConfirmedCompletionSender.sendAndConfirm(rabbitTemplate,
            CompletionTopology.COMPLETION_EXCHANGE, "q-target", data,
            Map.of(), null, 5_000L, false, null);

        // G-N на юните: прод-вызов идёт через exchange с ключом = имя очереди,
        // а не голым convertAndSend(queue, …) через default exchange.
        // Мутация «вернуть голый вызов» не компилируется/не матчится —
        // verify требует ровно 5-арную форму.
        verify(rabbitTemplate).convertAndSend(eq(CompletionTopology.COMPLETION_EXCHANGE),
            eq("q-target"), eq(data),
            any(org.springframework.amqp.core.MessagePostProcessor.class),
            any(CorrelationData.class));
    }

    @Test
    void topologyNames_pinEngineSideMirror() {
        // WO-INT-10: имена дублируются строкой в engine-side RabbitConfiguration
        // (стартер не зависит от zorrobpm-rabbitmq в compile-scope, в test-scope —
        // да). Расхождение = воркер публикует мимо биндингов движка.
        assertThat(CompletionTopology.COMPLETION_EXCHANGE)
            .isEqualTo(
                com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration.COMPLETIONS_EXCHANGE);
        assertThat(com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration.COMPLETE_QUEUE)
            .isEqualTo(JobCompletionListener.COMPLETE_QUEUE);
        assertThat(com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration
                .COMPLETION_POISON_QUEUE)
            .isEqualTo(CompletionPoisonRetryListener.POISON_QUEUE);
        assertThat(com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration
                .COMPLETION_RETRY_DELAY_QUEUE)
            .isEqualTo(CompletionPoisonRetryListener.RETRY_DELAY_QUEUE);
    }
}
