package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.OutboxStatus;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import io.micrometer.core.instrument.MeterRegistry;
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
 * WO-REL-66 критерий 3: разрыв самопитающейся петли {@code outbox.quarantined}.
 *
 * <p>Механика петли на master (инцидент 2026-10-07, ~24 записи/мин): доменное
 * событие, которому не на что маршрутизироваться (нет привязанной очереди на
 * {@code zorrobpm.events}), возвращается брокером (NO_ROUTE) → запись уходит в
 * карантин → карантин эмитит {@code outbox.quarantined} → оно ТОЖЕ
 * незамаршрутизируемо → ещё карантин → ещё событие. Счётчик карантина растёт
 * безостановочно.
 *
 * <p>Ожидаемое поведение: уведомление о карантине, которое само не
 * доставлено, дропается с учётом (WARN + метрика
 * {@code zbpm.domain.event.unroutable{type="outbox.quarantined"}}) и НЕ
 * карантинится рекурсивно — счётчик карантина стабилен.
 *
 * <p><b>WO-REL-66-fix (детерминированность; флейк CI 176897/job 560691).</b>
 * Прежняя версия гоняла {@code processBatch()} в цикле и перепубликовывала одну
 * и ту же строку, пока её delivery-result был в полёте. Подавление ложного
 * {@code ack=true} после unroutable-возврата живёт в
 * {@code RabbitConfiguration} в множестве по одному лишь outbox-id: два
 * наложившихся publish'а одного id схлопывают add, и один confirm уходит без
 * подавления → незамаршрутизируемая строка помечается published и выпадает из
 * обработки (дроп/метрика не наступают). Это прод-слабость отправки
 * (эскалирована CTO, V10) — тест обязан доказывать разрыв петли, а не гонку
 * подавления, поэтому теперь каждая строка публикуется РОВНО ОДИН раз:
 * {@code max-retries=1} делает одну неудачу терминальной, а недоставленные
 * результаты ожидаются Awaitility по фактическому состоянию, без sleep-подгонки.
 *
 * <p>Запуск: свой брокер + сьют {@code ci/run-rabbit-tests.sh} (см. отчёт
 * WO-REL-66-fix-quarantine-it-flake).
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
    // WO-REL-66-fix: 1 неудача = терминал. Механика гарда та же (терминальный
    // переход после failed delivery), но каждая строка публикуется один раз —
    // переплетение confirm/return одного id невозможно по построению.
    "zorrobpm.outbox.max-retries=1"
})
class QuarantineLoopBreakIT {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OutboxBatchProcessor outboxBatchProcessor;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanOutbox() {
        outboxRepository.deleteAllInBatch();
    }

    @Test
    void unroutableQuarantineNotification_doesNotGrowQuarantine() throws Exception {
        // Доменное событие без привязки на exchange → NO_ROUTE.
        OutboxEntry seed = new OutboxEntry();
        seed.setId(UUID.randomUUID());
        seed.setKind(OutboxKind.DOMAIN_EVENT);
        seed.setPayload(objectMapper.writeValueAsString(Map.of(
            "id", UUID.randomUUID().toString(),
            "sequence", 1,
            "type", "rel66.unroutable",
            "data", Map.of()
        )));
        seed.setCreatedAt(Instant.now());
        seed.setPublished(false);
        outboxRepository.save(seed);

        // Публикуем seed РОВНО ОДИН раз: единственная pending-строка — seed.
        // Первый же NO_ROUTE терминален (max-retries=1): запись уходит в
        // карантин и эмитит уведомление outbox.quarantined.
        outboxBatchProcessor.processBatch();
        await().atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(statusOf(seed.getId()))
                .as("seed докарантинен после первой же неудачи (max-retries=1)")
                .isEqualTo(OutboxStatus.FAILED));
        long quarantinedAfterSeed = outboxRepository.countQuarantined();
        assertThat(quarantinedAfterSeed)
            .as("seed докарантинен (ровно одна запись)")
            .isEqualTo(1);

        // Уведомление о карантине — теперь единственная pending-строка.
        // Публикуем и её РОВНО ОДИН раз (см. javadoc о гонке подавления ack).
        OutboxEntry notification = findQuarantineNotification();
        assertThat(notification)
            .as("карантин эмитил уведомление outbox.quarantined")
            .isNotNull();
        assertThat(notification.getStatus())
            .as("уведомление ждёт публикации")
            .isEqualTo(OutboxStatus.PENDING);
        outboxBatchProcessor.processBatch();

        // NO_ROUTE на уведомлении → guard дропает его с учётом: терминальный
        // markPublished + метрика, БЕЗ нового карантина и нового события.
        await().atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> {
                assertThat(unroutableCount())
                    .as("дроп незамаршрутизируемого уведомления учтён метрикой; "
                        + "строки outbox: %s", describeOutbox())
                    .isGreaterThanOrEqualTo(1.0);
                assertThat(publishedOf(notification.getId()))
                    .as("уведомление помечено published (терминал, без ре-эмита)")
                    .isTrue();
            });

        // Каскада нет — счётчик карантина не изменился.
        assertThat(outboxRepository.countQuarantined())
            .as("карантин не растёт каскадом (петля разорвана)")
            .isEqualTo(quarantinedAfterSeed);
    }

    private double unroutableCount() {
        io.micrometer.core.instrument.Counter counter = meterRegistry
            .find("zbpm.domain.event.unroutable").tag("type", "outbox.quarantined")
            .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private OutboxStatus statusOf(UUID id) {
        return outboxRepository.findById(id).map(OutboxEntry::getStatus).orElse(null);
    }

    private boolean publishedOf(UUID id) {
        return outboxRepository.findById(id).map(OutboxEntry::isPublished).orElse(false);
    }

    private OutboxEntry findQuarantineNotification() {
        return outboxRepository.findAll().stream()
            .filter(e -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope =
                        objectMapper.readValue(e.getPayload(), Map.class);
                    return "outbox.quarantined".equals(envelope.get("type"));
                } catch (Exception ex) {
                    return false;
                }
            })
            .findFirst()
            .orElse(null);
    }

    /**
     * Дословный снимок outbox для диагностики расхождений: по нему видно,
     * какая строка заморожена/опубликована без учёта и с каким lastError
     * (пригодилось при разборе флейка CI 176897/job 560691).
     */
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
                .append(",kind=").append(e.getKind())
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
