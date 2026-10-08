package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.OutboxStatus;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WO-REL-68: ложный {@code ack=true} при наложившихся публикациях одного
 * outbox-id помечает незамаршрутизируемую запись {@code published} (тихая
 * потеря, класс P-15).
 *
 * <p>Механика на master (CI job 560691 + красная команда 4/4): подавление
 * ложного {@code ack=true} после unroutable-возврата ведётся множеством по
 * одному лишь outbox-id ({@code RabbitConfiguration.returnedIds}). Два
 * наложившихся publish'а одного id дают кадры
 * {@code return,return,confirm,confirm}: второй {@code add} в Set схлопывается,
 * первый confirm снимает id, второй {@code ack=true} уходит БЕЗ подавления →
 * {@code OutboxDeliveryResultListener} делает {@code markPublished} на реально
 * недоставленной (NO_ROUTE) строке.
 *
 * <p>Флейк CI (pipeline 177046 job 561523) был в свидетеле наложения:
 * {@code attempts >= 2} — это счётчик БД с lost updates, а не факт прихода
 * кадров. При быстром результате первый confirm успевал пометить строку
 * published до повторной выборки, и тест падал с {@code actual 1}.</p>
 * <p>Здесь наложение строится конструкцией: все {@code return}/{@code confirm}
 * колбэки замораживаются обёртками вокруг реальных колбэков прод-шаблона до
 * вызова {@link #releaseFrozenResultFrames()}. Счётчики реальных кадров
 * ({@code returnedMessage}, {@code confirm(..., ack=true, ...)}) — свидетель
 * наложения ({@code returns >= 2}), а обработка результатов через
 * {@code OutboxDeliveryResultListener} идёт только после release: сначала все
 * return-события, затем все confirm-события. Set-модель на этом стабильно
 * краснеет (второй confirm публикует ложный ack), счётчик REL-68 подавляет
 * каждый ack индивидуально.</p>
 *
 * <p>Запуск: свой брокер + сьют {@code ci/run-rabbit-tests.sh}.</p>
 */
@Tag("rabbit")
@ActiveProfiles("test")
@SpringBootTest(classes = TestMain.class, properties = {
    "spring.rabbitmq.host=${RABBITMQ_HOST:localhost}",
    "spring.rabbitmq.port=${RABBITMQ_PORT:5672}",
    "spring.rabbitmq.username=${RABBITMQ_USER:zorrodev}",
    "spring.rabbitmq.password=${RABBITMQ_PASSWORD:zorrodev}",
    "spring.rabbitmq.publisher-confirm-type=correlated",
    "spring.rabbitmq.publisher-returns=true",
    // WO-REL-68: десятки неудач — ни одна не терминальна. Все строки остаются
    // PENDING, неудачи только считают attempts; карантина/уведомлений нет.
    "zorrobpm.outbox.max-retries=50"
})
class OutboxFalseAckOverlapRabbitIT {

    /** Отправок одного id в раунде наложения. */
    private static final int OVERLAP_SENDS = 5;
    /** Раундов наложения (свежая строка каждый): RED, если потёк ХОТЯ БЫ один. */
    private static final int OVERLAP_ROUNDS = 4;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OutboxBatchProcessor outboxBatchProcessor;
    @Autowired private RabbitTemplate rabbitTemplate;

    private RabbitTemplate.ConfirmCallback originalConfirmCallback;
    private RabbitTemplate.ReturnsCallback originalReturnsCallback;

    /** Реальных broker-returned событий, посчитанных обёрткой-над-продом. */
    private final AtomicInteger returnFrames = new AtomicInteger();
    /** Реальных ack=true confirm событий, посчитанных обёрткой-над-продом. */
    private final AtomicInteger ackedConfirmFrames = new AtomicInteger();

    /**
     * Реальные return/confirm события, пока что НЕ пропущенные в прод-колбэки.
     * Параллельно состоянию broker API: пустые только после {@link
     * #releaseFrozenResultFrames()}.
     */
    private final LinkedBlockingQueue<DelayedResultFrame> delayedResults = new LinkedBlockingQueue<>();

    @BeforeEach
    void cleanOutboxAndFreezeResults() throws Exception {
        outboxRepository.deleteAllInBatch();
        returnFrames.set(0);
        ackedConfirmFrames.set(0);
        delayedResults.clear();

        originalConfirmCallback = privateField("confirmCallback");
        originalReturnsCallback = privateField("returnsCallback");
        assertThat(originalConfirmCallback).isNotNull();
        assertThat(originalReturnsCallback).isNotNull();

        // обёртка над RabbitConfiguration#rabbitTemplate callback'ами:
        // считать кадры, отложить пока до release.
        // Direct field injection: Spring 4.0.4 forbids second setter calls; we are
        // wrapping the existing prod callbacks, not adding a concurrent consumer.
        setPrivateField("returnsCallback", (RabbitTemplate.ReturnsCallback) returned -> {
            returnFrames.incrementAndGet();
            delayedResults.offer(new DelayedResultFrame(true, () -> originalReturnsCallback.returnedMessage(returned)));
        });
        setPrivateField("confirmCallback", (RabbitTemplate.ConfirmCallback) (correlationData, ack, cause) -> {
            if (ack) {
                ackedConfirmFrames.incrementAndGet();
                delayedResults.offer(new DelayedResultFrame(false, () -> originalConfirmCallback.confirm(correlationData, ack, cause)));
            } else {
                delayedResults.offer(new DelayedResultFrame(false, () -> originalConfirmCallback.confirm(correlationData, ack, cause)));
            }
        });
    }

    @AfterEach
    void restoreRabbitTemplate() throws Exception {
        if (originalConfirmCallback != null) {
            setPrivateField("confirmCallback", originalConfirmCallback);
        }
        if (originalReturnsCallback != null) {
            setPrivateField("returnsCallback", originalReturnsCallback);
        }
    }

    @Test
    void overlappingPublishesOfOneId_neverFalselyMarkPublished() throws Exception {
        for (int round = 0; round < OVERLAP_ROUNDS; round++) {
            final int r = round;
            OutboxEntry probe = insertProbe("rel68.overlap.probe");

            // Детерминированное наложение: пять processBatch() ДО release
            // return/confirm-колбэков => строка перечитывается как pending и
            // повторно публикуется каждый раз.
            for (int i = 0; i < OVERLAP_SENDS; i++) {
                outboxBatchProcessor.processBatch();
            }

            // Свидетель наложения — настоящие кадры брокера, а не attempts:
            // attempts подвержен lost updates и на быстром CI давал actual 1.
            await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    assertThat(returnFrames.get())
                        .as("раунд %d: реальных return-кадров >=2; outbox: %s", r, describeOutbox())
                        .isGreaterThanOrEqualTo(2);
                    assertThat(ackedConfirmFrames.get())
                        .as("раунд %d: реальных ack=true confirm-кадров >=2; outbox: %s", r, describeOutbox())
                        .isGreaterThanOrEqualTo(2);
                });

            // Пока confirms на паузе — fallback published не может быть true
            // только из return-пути: якорь неподвижной строки.
            assertThat(publishedOf(probe.getId()))
                .as("раунд %d: до unfreeze published=false; outbox: %s", r, describeOutbox())
                .isFalse();

            // Release сначала проводит все return-события, потом все
            // confirm-события — канонический порядок наложенных кадров.
            releaseFrozenResultFrames();

            // На Set-модели второй confirm публикует ложный ack и строка
            // становится published=true: этот assert стабильно RED.
            assertThat(publishedOf(probe.getId()))
                .as("раунд %d: после обработки наложенных return/confirm published=false; returns=%d,acks=%d; outbox: %s",
                    r, returnFrames.get(), ackedConfirmFrames.get(), describeOutbox())
                .isFalse();
            // Ядро WO-REL-68: незамаршрутизируемая строка НЕ помечена published.
            OutboxEntry reloaded = outboxRepository.findById(probe.getId()).orElseThrow();
            assertThat(reloaded.getStatus())
                .as("раунд %d: после наложенных return/confirm строка всё ещё PENDING; outbox: %s",
                    r, describeOutbox())
                .isEqualTo(OutboxStatus.PENDING);
        }
    }

    @Test
    void singlePublish_parityWithPreFixBehavior() throws Exception {
        OutboxEntry probe = insertProbe("rel68.single.parity");

        outboxBatchProcessor.processBatch();

        await().atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> {
                assertThat(returnFrames.get())
                    .as("одиночная отправка: ровно один return-кадр; outbox: %s", describeOutbox())
                    .isEqualTo(1);
                assertThat(ackedConfirmFrames.get())
                    .as("одиночная отправка: ровно один ack=true confirm-кадр; outbox: %s", describeOutbox())
                    .isEqualTo(1);
            });
        releaseFrozenResultFrames();

        // Паритетный пин одиночного полёта: незамаршрутизируемая строка ждёт
        // (published=false, PENDING, attempts=1) — так было до фикса
        // (RabbitOutboxConfirmIT.criterion3) и так обязано остаться после.
        OutboxEntry reloaded = outboxRepository.findById(probe.getId()).orElseThrow();
        assertThat(reloaded.isPublished())
            .as("одиночная отправка: published=false; outbox: %s", describeOutbox())
            .isFalse();
        assertThat(reloaded.getStatus())
            .as("одиночная отправка: строка остаётся PENDING; outbox: %s", describeOutbox())
            .isEqualTo(OutboxStatus.PENDING);
        assertThat(reloaded.getAttempts())
            .as("одиночная отправка: ровно одна неудача, лишних отправок нет; outbox: %s",
                describeOutbox())
            .isEqualTo(1);
    }

    private void releaseFrozenResultFrames() {
        List<DelayedResultFrame> perKind = new ArrayList<>();
        delayedResults.drainTo(perKind);

        // Все возвраты — первыми, все подтверждающие — вторыми. Так
        // overlapped ordering в каноническом виде return-then-confirm.
        for (DelayedResultFrame f : perKind) {
            if (f.isReturn()) {
                f.run();
            }
        }
        for (DelayedResultFrame f : perKind) {
            if (!f.isReturn()) {
                f.run();
            }
        }
    }

    private OutboxEntry insertProbe(String type) throws Exception {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setKind(OutboxKind.DOMAIN_EVENT);
        // Тип без привязки на zorrobpm.events (topic exchange) → NO_ROUTE:
        // брокер вернёт сообщение (mandatory) + confirm ack=true.
        entry.setPayload(objectMapper.writeValueAsString(Map.of(
            "id", UUID.randomUUID().toString(),
            "sequence", 1,
            "type", type,
            "data", Map.of()
        )));
        entry.setCreatedAt(Instant.now());
        entry.setPublished(false);
        return outboxRepository.save(entry);
    }

    private boolean publishedOf(UUID id) {
        return outboxRepository.findById(id).map(OutboxEntry::isPublished).orElse(false);
    }

    @SuppressWarnings("unchecked")
    private <T> T privateField(String name) throws Exception {
        Field f = RabbitTemplate.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(rabbitTemplate);
    }

    private void setPrivateField(String name, Object value) throws Exception {
        Field f = RabbitTemplate.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(rabbitTemplate, value);
    }

    record DelayedResultFrame(boolean isReturn, Runnable runnable) {
        void run() {
            runnable.run();
        }
    }

    private String describeOutbox() {
        StringBuilder sb = new StringBuilder("[");
        for (OutboxEntry e : outboxRepository.findAll()) {
            String type;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> envelope = objectMapper.readValue(e.getPayload(), Map.class);
                type = String.valueOf(envelope.get("type"));
            } catch (Exception ex) {
                type = "<unparseable>";
            }
            sb.append("{id=").append(e.getId())
                .append(",status=").append(e.getStatus())
                .append(",published=").append(e.isPublished())
                .append(",attempts=").append(e.getAttempts())
                .append(",type=").append(type)
                .append(",lastError=").append(e.getLastError())
                .append("};");
        }
        return sb.append("]").toString();
    }
}
