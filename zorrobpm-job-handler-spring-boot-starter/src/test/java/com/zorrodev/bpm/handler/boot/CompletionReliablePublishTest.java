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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
        // WO-C8-36 (M-1): backoff-переотправки тут не проверяется (его тест —
        // отдельный класс), но он существует в боевом пути и иначе реально
        // СПИТ 1с на каждом падении: подменяем sleeper'ом-заглушкой, темп
        // проверяется там, где он и является предметом проверки.
        listener.setRedeliveryBackoff(new CompletionRedeliveryBackoff(millis -> { }));
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

    /**
     * Ответ на confirm по in-flight отправке. Прод-механизм ждёт per-send
     * {@code CorrelationData.getFuture()} — мок обязан его завершать, иначе тест
     * проверял бы несуществующий API (такое и служило блокером red-team 1.1:
     * {@code waitForConfirmsOrDie} вне invoke-scope кидает в бою, а мок был no-op).
     */
    private void answerConfirm(CorrelationData cd, boolean ack, String reason) {
        cd.getFuture().complete(new CorrelationData.Confirm(ack, reason));
    }

    @Test
    void confirmNack_throws_inputNotAcked() {
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        doAnswer(inv -> {
            answerConfirm((CorrelationData) inv.getArgument(3), false, "exchange down");
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));

        // CR-13/крит.5: NACK обязан выйти наружу — контейнер не подтвердит вход.
        assertThatThrownBy(() -> listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-nack")))
            .isInstanceOf(AmqpException.class)
            .hasMessageContaining("NACKed");
    }

    @Test
    void confirmTimeout_throws_inputNotAcked() {
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        listener.setConfirmTimeoutMs(120L);
        // Future НЕ завершаем — эмуляция потери confirm (NACK/разрыв после отправки).
        assertThatThrownBy(() -> listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-timeout")))
            .isInstanceOf(AmqpException.class)
            .hasMessageContaining("was not confirmed within 120ms");
    }

    @Test
    void confirmTimeout_nonPositiveValue_clampedToFloor_notImmediateTimeout() {
        // WO-C8-36 (red-team, P-41): ручка zorrobpm.worker.completion-confirm-timeout —
        // недоверенный ввод. При 0/отрицательном future.get() истекает мгновенно, то
        // confirm-wait бросал бы на КАЖДОМ completion'е: вход не ACK'ается никогда,
        // воркер уходит в бесконечную переотправку. Проверяем, что пол держит:
        // future НЕ завершаем, поэтому при Clamp'е к 100 c всё равно получим
        // таймаут с ПОЛОВЫМ значением в сообщении, а не мгновенный выход.
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        listener.setConfirmTimeoutMs(0L);

        assertThatThrownBy(() -> listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-zero")))
            .isInstanceOf(AmqpException.class)
            .hasMessageContaining("was not confirmed within "
                + JobCompletionListener.MIN_CONFIRM_TIMEOUT_MS + "ms");
    }

    /**
     * WO-C8-36 (M-2): потолок ожидания confirm. Значение сверху зажимается, иначе
     * воркер на дефолтных настройках контейнера (одна нить потребителя) молча
     * вставал бы на этот таймаут на каждом задании — и warn'а не было бы вовсе
     * (warn стоял только на полу). Ассерт на конкретное значение в сообщении:
     * future по-прежнему не завершаем, поэтому «потолок» читается из текста
     * таймаута, а не из «ошибки не было».
     */
    @Test
    void confirmTimeout_aboveCeiling_clamped_notUnboundedWait() {
        // Проверяется ПРИМЕНЁННОЕ значение, а не «ошибка была»: ждать потолок
        // (60с) в юните бессмысленно дорого, а именно значение уходит в
        // future.get(...). Мутация «снять потолок» возвращает 3_600_000 и
        // роняет ассерт; соседний confirmTimeout_throws_inputNotAcked
        // доказывает, что это значение реально применяется в ожидании.
        listener.setConfirmTimeoutMs(3_600_000L);

        assertThat(listener.confirmTimeoutMsForTest())
            .as("час ожидания на однопоточном воркере — это нерабочий воркер; "
                + "значение сверху потолка зажимается с warn'ом")
            .isEqualTo(JobCompletionListener.MAX_CONFIRM_TIMEOUT_MS);

        // И в пределах потолка значение проходит без изменений (ручка не «съедает» нормальные).
        listener.setConfirmTimeoutMs(7_500L);
        assertThat(listener.confirmTimeoutMsForTest()).isEqualTo(7_500L);
    }

    @Test
    void unroutableReturn_throws_inputNotAcked() {
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        // Return приходит раньше confirm (как на реальном брокере): к моменту
        // завершения future id уже в returned-сете — confirm ack=true обязан НЕ
        // считаться успехом.
        AtomicReference<String> inFlight = new AtomicReference<>();
        doAnswer(inv -> {
            CorrelationData cd = (CorrelationData) inv.getArgument(3);
            inFlight.set(cd.getId());
            // Так spring-amqp и помечает возврат: basic.return кладёт
            // ReturnedMessage в саму отправку ДО подтверждения.
            cd.setReturned(new org.springframework.amqp.core.ReturnedMessage(
                new org.springframework.amqp.core.Message(new byte[0]),
                312, "NO_ROUTE", "", "c836.it.complete.unroutable"));
            answerConfirm(cd, true, null);
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));

        assertThatThrownBy(() -> listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-ret")))
            .isInstanceOf(AmqpException.class)
            .hasMessageContaining("unroutable");

        // Дискриминатор P-67: id, попавший в исключение-путь, — именно тот, что ушёл в send.
        assertThat(inFlight.get()).startsWith("completion-");
        // WO-C8-36 (red-team re-pass-3): счётчик unroutable реально инкрементится.
        assertThat(listener.unroutableCountForTest()).isEqualTo(1L);
    }

    @Test
    void confirmedRoutable_sendsOnce_withPerSendCorrelationData() {
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        doAnswer(inv -> {
            answerConfirm((CorrelationData) inv.getArgument(3), true, null);
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));

        listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-ok"));

        // Счастливый путь: confirm ack=true, возвратов нет — исключения нет,
        // вход подтверждается (вызывающий код завершился нормой).
        CorrelationData cd = sentCorrelationData();
        assertThat(cd.getId()).startsWith("completion-");
        assertThat(cd.getFuture().isDone())
            .as("прод-код дождался confirm своей отправки — future завершён")
            .isTrue();
    }

    @Test
    void confirmsDisabled_syncExceptionsOnly_noWaitNoThrow() {
        // RED-контроль: именно confirm-wait/return-check дают throw выше. Со
        // старым поведением (ensure=false) те же NACK/return остаются тихими.
        listener.setEnsurePublisherConfirms(false);
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));

        listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-off"));

        CorrelationData captured = sentCorrelationData();
        assertThat(captured.getFuture().isDone())
            .as("без confirm-wait future отправки не ждём (старое поведение)")
            .isFalse();
    }

    @Test
    void unroutableDecisionIsPerSend_notSharedState() {
        // Red-team 1.2 (пересмотр): решение о доставке принимает САМА отправка
        // (CorrelationData.getReturned), а не разделяемый сет. Два соседних
        // вызова не могут увидеть чужой возврат — проверяем конкретно: у первой
        // отправки возврат есть, у второй — нет, и вторая обязана пройти.
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        AtomicInteger call = new AtomicInteger();
        List<CorrelationData> sends = new java.util.ArrayList<>();
        doAnswer(inv -> {
            CorrelationData cd = (CorrelationData) inv.getArgument(3);
            sends.add(cd);
            if (call.getAndIncrement() == 0) {
                cd.setReturned(new org.springframework.amqp.core.ReturnedMessage(
                    new org.springframework.amqp.core.Message(new byte[0]),
                    312, "NO_ROUTE", "", "nowhere"));
            }
            answerConfirm(cd, true, null);
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));

        assertThatThrownBy(() -> listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-a")))
            .isInstanceOf(AmqpException.class).hasMessageContaining("unroutable");

        // Второй вызов — чистый маршрут: никакого наследованного состояния.
        // CorrelationData смотрим через тот же Answer (второй вызов = вторая
        // отправка), а не через sentCorrelationData: тотverify-ит РОВНО один раз.
        listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-b"));
        assertThat(sends).as("две отправки — две независимые CorrelationData").hasSize(2);
        assertThat(sends.get(1).getReturned())
            .as("вторая отправка не унаследовала возврат первой — состояние per-send")
            .isNull();
        assertThat(listener.unroutableCountForTest()).isEqualTo(1L);
    }

    @Test
    void confirmsUnavailableFallback_countedAndLogged() {
        // WO-C8-36 (red-team HOLD-4): тихое ослабление посчитано (не молча).
        org.springframework.amqp.rabbit.connection.ConnectionFactory noConfirms =
            org.mockito.Mockito.mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        org.mockito.Mockito.when(noConfirms.isPublisherConfirms()).thenReturn(false);
        org.mockito.Mockito.lenient().when(rabbitTemplate.getConnectionFactory()).thenReturn(noConfirms);
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));

        listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-fb"));

        assertThat(listener.confirmsUnavailableCountForTest()).isEqualTo(1L);
        CorrelationData captured = sentCorrelationData();
        assertThat(captured.getFuture().isDone())
            .as("без confirms future не ждём — fallback честно помечен счётчиком")
            .isFalse();
    }

    @Test
    void completionId_echoedIntoCompletion_exactValue() {
        // WO-C8-36 (red-team HOLD-1 + проброс из незакоммиченной правки): воркер
        // обязан вернуть Идентификатор ОТПРАВКИ, по которому движок дедуплицирует
        // FAILED-дубликаты. Без этого охранник недостижим из живого пути.
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        doAnswer(inv -> {
            answerConfirm((CorrelationData) inv.getArgument(3), true, null);
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));

        listener.onMessage(message(jobJson(UUID.randomUUID()), "corr-cid"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(rabbitTemplate).convertAndSend(anyString(), payload.capture(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));
        ServiceTaskCompleteData sent = (ServiceTaskCompleteData) payload.getValue();
        assertThat(sent.getCompletionId())
            .as("completionId обязателен в теле completion — по нему движок дедуплицирует")
            .isNotNull()
            .startsWith("completion-");
    }

    @Test
    void dispatchPhase_echoedIntoCompletion_exactValues() {
        // WO-C8-36 (CR-01, сторона воркера): фаза/индекс входящего задания
        // возвращаются в completion без изменений (assert на КОНКРЕТНЫЕ значения).
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        doAnswer(inv -> {
            answerConfirm((CorrelationData) inv.getArgument(3), true, null);
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));
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
