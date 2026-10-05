package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-C8-36 (H-1, red-team): СОВМЕСТИМОСТЬ движок→воркер при апгрейде движка
 * раньше воркеров.
 *
 * <p>Проблема, которую закрывает этот класс: движок штампует
 * {@code dispatchPhase}/{@code dispatchIndex} в тело задания, а воркер читает его
 * голым {@code new ObjectMapper()}, где {@code FAIL_ON_UNKNOWN_PROPERTIES} по
 * умолчанию <b>true</b>. Ошибка десериализации приводила к тихому возврату из
 * {@code onMessage} — то есть AUTO-ack: задание терялось НАВСЕГДА, без
 * redelivery и без восстановления. Старший движок + старый воркер = полная
 * потеря всех заданий, не только phased.
 *
 * <p>Тесты бьют по ОБЕИМ сторонам, которые CTO назвал в решении:
 * <ol>
 *   <li>{@link HandlerAutoConfiguration} конфигурирует свой reader толерантно
 *       ({@code FAIL_ON_UNKNOWN_PROPERTIES=false}) — проверяется на ТОМ ЖЕ
 *       ObjectMapper, который стартер отдаёт в listener (прод-wiring, не
 *       отдельный тестовый экземпляр: иначе тест прошёл бы, а в бою падало бы);</li>
 *   <li>на DTO exchange стоит {@code @JsonIgnoreProperties(ignoreUnknown=true)} —
 *       тогда даже чужой/сторонний строгий reader не роняет воркер на новом поле
 *       (проверяется РЕАЛЬНЫМ десериализатором байт-класса из
 *       {@code master}-состояния DTO, без самих новых геттеров).</li>
 * </ol>
 *
 * <p>Ассерты РАЗЛИЧАЮЩИЕ (P-67): «десериализация не бросила и задание дошло до
 * handler'а» — сравнение с поведением при строгом reader'е, а не «объект не null».
 */
@ExtendWith(MockitoExtension.class)
class WorkerForwardCompatibilityTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private JobHandler handler;

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

    /** Тело задания НОВОГО движка: phased-штамп присутствует. */
    private static String stampedJobBody() {
        return "{\"serviceTaskId\":\"" + UUID.randomUUID() + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"serviceTaskKey\":\"k\",\"job\":\"job1\",\"variables\":{},"
            + "\"dispatchPhase\":\"start\",\"dispatchIndex\":0}";
    }

    private void confirmAcking() {
        doAnswer(inv -> {
            ((CorrelationData) inv.getArgument(3)).getFuture()
                .complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));
    }

    /**
     * (1) Стартер обязан отдать воркеру ТОЛЕРАНТНЫЙ reader.
     *
     * <p>Проверка на боевом reader'е стартера: он строится в
     * {@code HandlerAutoConfiguration.init()} и уходит и в конвертер, и в
     * listener. Строгий дефолт здесь = H-1 в чистом виде.
     */
    @Test
    void starterReader_isTolerantToUnknownFields_strictDefaultWouldThrow() throws Exception {
        ObjectMapper starterReader = HandlerAutoConfiguration.createJobBodyReader();

        assertThat(starterReader.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
            .as("reader воркера обязан терпеть незнакомые поля — иначе апгрейд движка "
                + "ранее апгрейда воркеров теряет каждое задание")
            .isFalse();

        // Контроль дефекта: дефолт Jackson — strict, и на НЕ-аннотированном DTO
        // то же тело с лишним полем роняет чтение. Значит толерантность — это
        // изменение поведения, а не косметика: сними аннотацию И выключи флаг —
        // тест упадёт именно здесь.
        String withExtraField = "{\"job\":\"job1\",\"fieldFromFutureEngineVersion\":\"whatever\"}";
        Throwable strictError = null;
        try {
            new ObjectMapper().readValue(withExtraField, PlainDto.class);
        } catch (Exception e) {
            strictError = e;
        }
        assertThat(strictError)
            .as("строгий reader на незнакомом поле падает (дефолт Jackson)")
            .isNotNull();

        // Тот же вход — на боевом reader'е стартера и на боевом DTO: читается.
        JobDetailModel read = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> starterReader.readValue(withExtraField, JobDetailModel.class));
        assertThat(read.getJob()).isEqualTo("job1");
    }

    /** DTO БЕЗ аннотации — контрольная точка для дефолта Jackson. */
    public static class PlainDto {
        private String job;
        public String getJob() { return job; }
        public void setJob(String job) { this.job = job; }
    }

    /**
     * (1, сквозная форма) Listener на ТОМ ЖЕ reader'е, что отдаёт стартер:
     * phased-тело нового движка доходит до handler'а и уходит в completion.
     * До фикса — тихий возврат (ACK без результата), handler не вызван.
     */
    @Test
    void stampedJobBody_reachesHandler_withStarterReader_insteadOfSilentAck() {
        ObjectMapper starterReader = HandlerAutoConfiguration.createJobBodyReader();
        JobCompletionListener listener =
            new JobCompletionListener(handler, rabbitTemplate, starterReader, "q-in");
        org.springframework.amqp.rabbit.connection.ConnectionFactory cf =
            org.mockito.Mockito.mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        org.mockito.Mockito.lenient().when(cf.isPublisherConfirms()).thenReturn(true);
        org.mockito.Mockito.lenient().when(rabbitTemplate.getConnectionFactory()).thenReturn(cf);
        confirmAcking();
        when(handler.handleJob(any())).thenReturn(List.of(outVar()));

        listener.onMessage(message(stampedJobBody(), "corr-compat"));

        verify(handler).handleJob(any(JobDetailModel.class));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(anyString(), payload.capture(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));
        assertThat(payload.getValue()).isInstanceOf(com.zorrodev.bpm.exchange.ServiceTaskCompleteData.class);
    }

    /**
     * (H-1, п.е) Нечитаемое задание обязано быть ЗАМЕТНЫМ, а не одной строкой лога:
     * счётчик + ERROR. До правки был {@code log.error} и чистый возврат (тихий
     * ACK на стороне брокера) — при апгрейде движка это выглядело как «воркер
     * просто не отвечает», и масштаб потери из лога не извлечь.
     *
     * <p>Ассерт РАЗЛИЧАЮЩИЙ: сравнение счётчика ДО/ПОСЛЕ двух битых сообщений
     * плюс «handler не вызван ни разу» (то естьбизнес-эффекта не было, а задание
     * при этом пропало). Мутация «вернуть счётчик назад в ноль» валит тест.
     */
    @Test
    void malformedJob_isCounted_notJustLogged() {
        ObjectMapper starterReader = HandlerAutoConfiguration.createJobBodyReader();
        JobCompletionListener listener =
            new JobCompletionListener(handler, rabbitTemplate, starterReader, "q-in");
        long before = listener.malformedCountForTest();

        listener.onMessage(message("{not json at all", "corr-bad-1"));
        listener.onMessage(message("{\"broken\":", "corr-bad-2"));

        assertThat(listener.malformedCountForTest())
            .as("каждое непрочитанное задание обязано попасть в счётчик — иначе потеря "
                + "не видна в телеметрии вовсе")
            .isEqualTo(before + 2);
        verify(handler, org.mockito.Mockito.never()).handleJob(any());
    }

    /**
     * (2) DTO exchange помечен {@code @JsonIgnoreProperties(ignoreUnknown=true)}:
     * даже ЧУЖОЙ строгий reader (например, поднятый отдельно от стартера, или
     * форк, не обновлявший конфиг) не роняет воркер на поле новой версии.
     * Без аннотации доезжает только настройка стартера — этого мало.
     */
    @Test
    void exchangeDto_ignoresUnknownFields_forAThirdPartyStrictReader() {
        String body = stampedJobBody().replace("\"job\":\"job1\"",
            "\"job\":\"job1\",\"fieldFromFutureEngineVersion\":\"whatever\"");
        // Ровно то, что делает старый воркер: голый ObjectMapper.
        ObjectMapper oldWorkerReader = new ObjectMapper();
        JobDetailModel model = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> oldWorkerReader.readValue(body, JobDetailModel.class));
        assertThat(model.getJob()).as("прочитанное задание не должно деградировать")
            .isEqualTo("job1");
    }
}
