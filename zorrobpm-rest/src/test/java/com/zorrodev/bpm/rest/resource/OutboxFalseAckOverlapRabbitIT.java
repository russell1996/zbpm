package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.OutboxStatus;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
 * <p>Драйвер наложения: K вызовов {@code processBatch()} подряд без ожиданий
 * между ними — каждый перечитывает pending и перепубликует ту же строку, пока
 * delivery-result предыдущих отправок ещё в полёте. Поллер в этом контексте
 * выключен ({@code OutboxPollerService @Profile("!test")}), лишних отправок
 * нет: число sends == числу вызовов, число returns == attempts.
 *
 * <p>Про отсутствие гонки в самом вердикте: после того как все возвраты
 * обработаны (attempts == числу отправок), тест ждёт quiescence — attempts
 * стабилен 1 с. Возврат/confirm на localhost идут миллисекунды, опоздавший
 * confirm после quiescence практически исключён; наложение крутится
 * несколькими раундами (см. ниже), RED достаточно хотя бы одного потёкшего
 * раунда. На дереве С фиксом (WO-REL-68) ожидание станет race-free через
 * трекер возвратов — этот класс тогда же получит точечную правку ожидания
 * (тело ассертов не меняется).
 *
 * <p>Запуск: свой брокер + сьют {@code ci/run-rabbit-tests.sh}.
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

    @BeforeEach
    void cleanOutbox() {
        outboxRepository.deleteAllInBatch();
    }

    @Test
    void overlappingPublishesOfOneId_neverFalselyMarkPublished() throws Exception {
        for (int round = 0; round < OVERLAP_ROUNDS; round++) {
            final int r = round;
            OutboxEntry probe = insertProbe("rel68.overlap.probe");

            // Наложение: K отправок подряд, без ожиданий между ними.
            for (int i = 0; i < OVERLAP_SENDS; i++) {
                outboxBatchProcessor.processBatch();
            }

            // Все возвраты обработаны. Порог — >= 2, а не == K: пять
            // конкурентных return-обработок делают read-modify-write
            // (OutboxDeliveryResultListener.on читает attempts и пишет
            // абсолютное recordFailure(id, attempts+1)) — возможны lost
            // updates (наблюдено attempts=4 при 5 отправках). Это отдельная
            // прод-слабость (V7-находка в отчёте WO-REL-68, вне scope), а для
            // доказательства наложения достаточно >= 2: минимум две отправки
            // ушли до прихода результатов, т.е. кадры реально наложились.
            await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(attemptsOf(probe.getId()))
                    .as("раунд %d: возвраты обработаны, наложение было; outbox: %s",
                        r, describeOutbox())
                    .isGreaterThanOrEqualTo(2));
            awaitQuiescence(probe.getId());

            // Ядро WO-REL-68: незамаршрутизируемая строка НЕ помечена
            // published. Других писателей published=true на этом пути нет:
            // markPublished зовут только ack-ветка OutboxDeliveryResultListener
            // и QuarantineNotificationGuard.dropUndeliverable — последний
            // только для type=outbox.quarantined, а зонд — rel68.*. Значит
            // published=true здесь = ложный ack=true без подавления.
            assertThat(publishedOf(probe.getId()))
                .as("раунд %d: незамаршрутизируемая строка НЕ помечена published "
                    + "ложным ack (attempts=%d, lastError — в дампе); outbox: %s",
                    r, attemptsOf(probe.getId()), describeOutbox())
                .isFalse();
        }
    }

    @Test
    void singlePublish_parityWithPreFixBehavior() throws Exception {
        OutboxEntry probe = insertProbe("rel68.single.parity");

        outboxBatchProcessor.processBatch();

        await().atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(attemptsOf(probe.getId()))
                .as("одиночная отправка: возврат обработан; outbox: %s", describeOutbox())
                .isGreaterThanOrEqualTo(1));
        awaitQuiescence(probe.getId());

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

    /**
     * Ждёт, пока по id не останется ничего в полёте: attempts (монотонный —
     * только растёт, других писателей у поля на этом пути нет) стабилен 1 с.
     * Возврат/confirm на localhost — миллисекунды. Вызывается только после
     * того, как attempts >= 2.
     */
    private void awaitQuiescence(UUID id) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
            .until(() -> {
                int before = attemptsOf(id);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return attemptsOf(id) == before;
            });
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

    private int attemptsOf(UUID id) {
        return outboxRepository.findById(id).map(OutboxEntry::getAttempts).orElse(-1);
    }

    private boolean publishedOf(UUID id) {
        return outboxRepository.findById(id).map(OutboxEntry::isPublished).orElse(false);
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
