package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * WO-C8-36 (M-1): горячая переотправка ограничена backoff'ом 1с→30с, НЕ drop'ом.
 *
 * <p>До правки контейнер при отказе доставки делал requeue немедленно, то есть
 * при немаршрутизируемом completion'е воркер крутил цикл без единой задержки.
 * Поведение проверяется не «было ли исключение» (оно и раньше бросалось), а
 * ПО ТЕМПУ ПОВТОРОВ: список фактических интервалов между попытками. Сними
 * backoff — список станет пустым/нулевым и тест упадёт.
 *
 * <p>Sleeper подменён на записывающий: иначе проверка 1с→2с→4с стоила бы
 * 7 секунд реального сна. Проброс на sleeper'е — единственное, что тест
 * «трогает» вместо боевого кода; сам интервал вычисляет прод-класс.
 */
@ExtendWith(MockitoExtension.class)
class CompletionRedeliveryBackoffTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private JobHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JobCompletionListener listener;
    private List<Long> slept;

    @BeforeEach
    void setUp() {
        listener = new JobCompletionListener(handler, rabbitTemplate, objectMapper, "q-in");
        slept = new ArrayList<>();
        listener.setRedeliveryBackoff(new CompletionRedeliveryBackoff(slept::add));
        org.springframework.amqp.rabbit.connection.ConnectionFactory cf =
            org.mockito.Mockito.mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        org.mockito.Mockito.lenient().when(cf.isPublisherConfirms()).thenReturn(true);
        org.mockito.Mockito.lenient().when(rabbitTemplate.getConnectionFactory()).thenReturn(cf);
        listener.setConfirmTimeoutMs(120L);
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

    /**
     * Confirm теряется на каждой отправке (future не завершаем → таймаут → throw).
     * Lenient-строгость обязательна: этот же хелпер в тесте восстановления
     * перестаёт быть последним стабом, и Mockito иначе ругается на «лишний».
     */
    private void everyConfirmLost() {
        org.mockito.Mockito.lenient().when(handler.handleJob(any())).thenReturn(List.of(outVar()));
        org.mockito.Mockito.lenient().doAnswer(inv -> null).when(rabbitTemplate)
            .convertAndSend(anyString(), (Object) any(),
                any(org.springframework.amqp.core.MessagePostProcessor.class),
                any(CorrelationData.class));
    }

    @Test
    void redeliveryBackoff_growsExponentially_andIsCappedAt30s() {
        everyConfirmLost();
        String correlationId = "corr-hot-loop";
        Message msg = message(jobJson(), correlationId);

        // Шесть неудачных доставок одной и той же отправки (confirm теряется).
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> listener.onMessage(msg))
                .as("попытка %s обязана бросить (вход не ACK'ается)", i)
                .isInstanceOf(AmqpException.class);
        }

        assertThat(slept)
            .as("интервалы между переотправками обязаны расти: без backoff цикл горячий")
            .containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L);
        assertThat(slept).allSatisfy(d -> assertThat(d)
            .as("интервал не превышает потолок 30с и не меньше стартовой секунды")
            .isBetween(1_000L, 30_000L));
    }

    @Test
    void redeliveryBackoff_neverDropsTheJob_andCountsRedeliveries() {
        everyConfirmLost();
        String correlationId = "corr-never-drop";
        Message msg = message(jobJson(), correlationId);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> listener.onMessage(msg))
                .as("задание обязано переотправляться, а не быть отброшенным: "
                    + "единственная причина redelivery — недоставленный результат, "
                    + "а разбирается он на стороне движка")
                .isInstanceOf(AmqpException.class);
        }

        assertThat(listener.redeliveryCountForTest())
            .as("каждая переотправка считается — оператор должен видеть темп потока")
            .isEqualTo(4L);
        assertThat(org.mockito.Mockito.mockingDetails(handler).getInvocations())
            .as("бизнес-эффект при этом НЕ повторяется (результат переигрывается из кэша)")
            .hasSize(1);
    }

    /**
     * WO-C8-36 (F-4): что этот тест ДОКАЗЫВАЕТ на самом деле.
     *
     * <p>Раньше он назывался {@code redeliveryBackoff_isPerJob_otherJobsNotDelayed}
     * и утверждал, что «разные задания не замедляют друг друга». Это неверно:
     * sleeper — это {@code Thread.sleep} на потоке потребителя, а контейнер
     * воркера по умолчанию однопоточный, поэтому задержка одного задания
     * ДЕЙСТВИТЕЛЬНО стоит всей очереди хендлера (≤30с за попытку). Обещать
     * изоляцию в javadoc и в имени теста было ровно то расхождение, которое
     * поймала рецензия.
     *
     * <p>Что остаётся по существу и проверяется здесь: шкала задержек ведётся
     * ПО ОТПРАВКЕ, а не на процесс. У «горячего» задания накоплено две неудачи,
     * и его третье ждало бы 4с — но чужое задание начинает свою шкалу с 1с.
     * Именно это и означает per-job: не «не замедляет очередь», а «не наследует
     * чужой счётчик».
     *
     * <p>Реальная блокировка очереди зафиксирована отдельным тестом
     * {@code #redeliveryBackoff_singleConsumerThread_blocksHeadOfLine}, чтобы
     * утверждение о ней было доказано, а не только описано в javadoc.
     */
    @Test
    void redeliveryBackoff_isKeyedPerSend_otherJobStartsFromFirstSecond() {
        everyConfirmLost();
        Message hot = message(jobJson(), "corr-hot");
        Message fresh = message(jobJson(), "corr-fresh");

        // Две неудачи у «горячего» задания…
        assertThatThrownBy(() -> listener.onMessage(hot)).isInstanceOf(AmqpException.class);
        assertThatThrownBy(() -> listener.onMessage(hot)).isInstanceOf(AmqpException.class);

        // …и одна у другого: его шкала обязана начаться с первой секунды.
        assertThatThrownBy(() -> listener.onMessage(fresh)).isInstanceOf(AmqpException.class);

        assertThat(slept)
            .as("шкала задержек ведётся на отправку, а не на процесс: чужое задание "
                + "не наследует накопленные неудачи")
            .containsExactly(1_000L, 2_000L, 1_000L);
    }

    /**
     * WO-C8-36 (F-4): блокировка head-of-line — РЕАЛЬНА, и тест это ИЗМЕРЯЕТ, а не
     * объявляет.
     *
     * <p>Sleeper здесь настоящий ({@code Thread.sleep}), как в проде, — иначе мерить
     * нечего. Измеряется ВРЕМЯ ДВУХ ПОСЛЕДОВАТЕЛЬНЫХ `onMessage` НА ОДНОМ ПОТОКЕ, и
     * сравнивается с суммой их собственных задержек (2 × 1с):
     *
     * <ul>
     *   <li>один поток потребителя (дефолт Spring для контейнера воркера, то есть
     *       прод-конфигурация): ~2с — вторая задержка начинается только после первой,
     *       то есть первое задание действительно СТОИТ второму;</li>
     *   <li>два и более потребителя: ~1с — обе задержки идут параллельно.</li>
     * </ul>
     *
     * <p>Порог стоит именно на разнице между этими двумя мирами (~1с против ~2с), и
     * поэтому тест ПАДАЕТ в мире, где задания не блокируют друг друга. Первая версия
     * теста снимала таймер ПОСЛЕ первого `onMessage` и тем самым мерила только вторую
     * задержку — то есть проходила одинаково в обоих мирах (@verifier раунда 3 поймал
     * именно это, P-67: ассерт стоял рядом с утверждением, а не на нём).
     *
     * <p>Что доказывает и чего не доказывает: ДОКАЗЫВАЕТ, что задержка блокирует поток
     * потребителя (очередь хендлера). НЕ доказывает, что блокировка приемлема — это
     * решение CTO (F-4), зафиксировано в javadoc и в §10.2 отчёта; устранение (DLQ /
     * отложенная доставка) вынесено в «Находки рядом».
     */
    @Test
    void redeliveryBackoff_singleConsumerThread_blocksHeadOfLine() {
        everyConfirmLost();
        listener.setRedeliveryBackoff(new CompletionRedeliveryBackoff(CompletionRedeliveryBackoffTest::realSleep));
        Message hot = message(jobJson(), "corr-hot");
        Message other = message(jobJson(), "corr-other");

        // Таймер ДО первого вызова: в измерение обязаны попасть обе задержки, иначе
        // ассерт не отличает «второе задание ждало первое» от «каждое ждало своё».
        long start = System.nanoTime();
        assertThatThrownBy(() -> listener.onMessage(hot)).isInstanceOf(AmqpException.class);
        assertThatThrownBy(() -> listener.onMessage(other)).isInstanceOf(AmqpException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // Порог = 2 × 1с − запас. На одном потоке фактически ~2с (+confirm-оверхед),
        // при двух потребителях было бы ~1с — то есть тест красный в том мире, где
        // блокировки нет, и это ровно то, что опровергало исходное обещание javadoc.
        assertThat(elapsedMs)
            .as("на одном потоке потребителя две задержки идут ПОСЛЕДОВАТЕЛЬНО (≈2с), то есть "
                + "первое задание действительно стоит второму; при двух потребителях было бы ≈1с — "
                + "и вот на этом тест обязан падать, иначе он не про блокировку")
            .isGreaterThanOrEqualTo(2 * CompletionRedeliveryBackoff.INITIAL_DELAY_MS - 100);
    }

    private static void realSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * WO-C8-36 (F-6): карта счётчиков ограничена — 10k ключей, вытеснение по
     * {@code maximumSize}. Мусорные {@code correlationId} (а у отравленного
     * задания успешной доставки не будет НИКОГДА, то есть и reset не придёт)
     * не могут расти в памяти без предела.
     */
    @Test
    void redeliveryBackoff_trackedKeys_areBounded() {
        CompletionRedeliveryBackoff backoff = new CompletionRedeliveryBackoff(slept::add);
        int overflow = 500;
        for (int i = 0; i < CompletionRedeliveryBackoff.MAX_TRACKED_KEYS + overflow; i++) {
            backoff.recordFailedAttempt("corr-" + i);
        }
        assertThat(backoff.trackedKeysForTest())
            .as("карта счётчиков обязана быть ограничена — иначе каждый новый отравленный "
                + "correlationId добавлял вечную запись (успешного reset у него не будет)")
            .isLessThanOrEqualTo(CompletionRedeliveryBackoff.MAX_TRACKED_KEYS);
    }

    /** Контроль: потолок не «съел» саму функцию — лимит выше нуля и счётчик считает. */
    @Test
    void redeliveryBackoff_keyLimitIsPositiveAndCountingStillWorks() {
        CompletionRedeliveryBackoff backoff = new CompletionRedeliveryBackoff(slept::add);
        assertThat(CompletionRedeliveryBackoff.MAX_TRACKED_KEYS).isPositive();
        assertThat(backoff.recordFailedAttempt("corr-x")).isEqualTo(1);
        assertThat(backoff.recordFailedAttempt("corr-x")).isEqualTo(2);
        assertThat(backoff.attemptsFor("corr-x")).isEqualTo(2);
        backoff.reset("corr-x");
        assertThat(backoff.attemptsFor("corr-x")).isZero();
    }

    @Test
    void successfulDelivery_resetsBackoff_soRecoveredJobIsNotStuckAt30s() {
        // Сначала две неудачи (маршрут битый), потом маршрут починили: следующая
        // неудача обязана начать шкалу заново, иначе задание навсегда осталось бы
        // на потолке 30с после починки.
        everyConfirmLost();
        String correlationId = "corr-recovered";
        Message msg = message(jobJson(), correlationId);
        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);
        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);

        // Успешная публикация: confirm ack, без возврата.
        doAnswer(inv -> {
            ((CorrelationData) inv.getArgument(3)).getFuture()
                .complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), (Object) any(),
            any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));
        listener.onMessage(msg);

        // Снова ломаем маршрут — шкала обязана начаться с 1с.
        everyConfirmLost();
        assertThatThrownBy(() -> listener.onMessage(msg)).isInstanceOf(AmqpException.class);

        assertThat(slept)
            .as("после успешной доставки счётчик сброшен — иначе починенное задание "
                + "вечно ждало бы 30с перед каждой попыткой")
            .containsExactly(1_000L, 2_000L, 1_000L);
    }

    @Test
    void delaySchedule_isBoundedAndMonotonic() {
        // Чистая шкала, без сети: контракт «1с → 30с и дальше не растёт».
        long previous = 0;
        for (int attempt = 1; attempt <= 8; attempt++) {
            long delay = CompletionRedeliveryBackoff.delayFor(attempt);
            assertThat(delay).isBetween(CompletionRedeliveryBackoff.INITIAL_DELAY_MS,
                CompletionRedeliveryBackoff.MAX_DELAY_MS);
            assertThat(delay).isGreaterThanOrEqualTo(previous);
            previous = delay;
        }
        assertThat(CompletionRedeliveryBackoff.delayFor(8))
            .as("потолок держится, а не уходит в бесконечность")
            .isEqualTo(CompletionRedeliveryBackoff.MAX_DELAY_MS);
    }
}
