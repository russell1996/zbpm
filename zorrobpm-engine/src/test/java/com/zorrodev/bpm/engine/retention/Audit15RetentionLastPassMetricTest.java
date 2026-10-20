package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.event.SseLiveCursorTracker;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * WO-AUDIT-15 (F-11): метрика «сколько строк удалил ПОСЛЕДНИЙ проход» по
 * таблицам {@code events} и {@code outbox}.
 *
 * <p><b>Зачем она нужна сверх счётчика {@code *.deleted.total}.</b> Счётчик —
 * сумма за всю жизнь процесса; по нему «чистка идёт и работает» и «чистка
 * настроена, но ничего не находит» неразличимы, а у второй картины есть
 * ровно один тревожный сценарий — чистка не настроена вовсе. Gauge последнего
 * прохода различает их: 0 = проход отработал и ничего не удалил, N = удалил N.
 *
 * <p>Тест гоняет НАСТОЯЩИЙ {@link RetentionJob} (маршрутизация проходов и
 * публикация метрики) с подменённым только хранилищем (мок
 * {@link RetentionBatchProcessor} — SQL чистки проверяется в PgIT рядом, здесь
 * важна публикация числа).
 *
 * <p>Ключевая проверка — вторая: gauge обязан показывать результат ПОСЛЕДНЕГО
 * прохода, а не накапливать. Если проход удалил 3 строки, а следующий удалил 0,
 * gauge обязан стать 0, а не остаться 3 — иначе оператор видит «чистка работает»
 * на таблице, которая давно пуста.
 *
 * <p>Мутации, которые обязаны ронять этот тест: убрать вызов
 * {@code setRetentionEventsDeletedLastPass(...)} из {@link RetentionJob};
 * публиковать в gauge накопленный итог вместо результата прохода; не
 * обнулять gauge в dry-run-ветке.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Audit15RetentionLastPassMetricTest {

    private static final String EVENTS_GAUGE = "zbpm.retention.events.deleted.last_pass";
    private static final String OUTBOX_GAUGE = "zbpm.retention.outbox.deleted.last_pass";

    @Mock private RetentionBatchProcessor batchProcessor;
    private MeterRegistry registry;
    private RetentionConfig config;
    private RetentionJob job;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        config = new RetentionConfig();
        job = new RetentionJob(config, batchProcessor, new BpmMetrics(registry),
            new SseLiveCursorTracker());
    }

    @Test
    void eventsPass_publishesTheNumberOfRowsDeletedByThatPass() {
        config.setEventsTtlDays(30);
        when(batchProcessor.claimAndDeleteEventsBatch(any(), eq(25), any()))
            .thenReturn(new RetentionBatchProcessor.EventsBatch(List.of(1L, 2L, 3L), 3))
            .thenReturn(new RetentionBatchProcessor.EventsBatch(List.of(), 0));

        job.run();

        assertThat(gauge(EVENTS_GAUGE))
            .as("gauge последнего прохода по events обязан показать, сколько строк удалил ЭТОТ проход")
            .isEqualTo(3.0);
    }

    @Test
    void aPassThatDeletesNothing_resetsTheGaugeToZero() {
        config.setEventsTtlDays(30);
        when(batchProcessor.claimAndDeleteEventsBatch(any(), eq(25), any()))
            .thenReturn(new RetentionBatchProcessor.EventsBatch(List.of(1L, 2L, 3L), 3))
            .thenReturn(new RetentionBatchProcessor.EventsBatch(List.of(), 0));

        job.run();
        assertThat(gauge(EVENTS_GAUGE)).isEqualTo(3.0);

        // Второй проход: eligible нет — удалено 0. Gauge обязан стать 0, а не
        // остаться 3 (иначе «чистка работает» видно на давно пустой таблице).
        job.run();

        assertThat(gauge(EVENTS_GAUGE))
            .as("gauge — состояние ПОСЛЕДНЕГО прохода; проход без удалений обязан обнулить его")
            .isEqualTo(0.0);
    }

    @Test
    void outboxPass_publishesTheNumberOfRowsDeletedByThatPass() {
        config.setOutboxTtlDays(14);
        when(batchProcessor.claimAndDeleteOutboxBatch(any(), eq(25)))
            .thenReturn(new RetentionBatchProcessor.OutboxBatch(
                java.util.List.of(java.util.UUID.randomUUID()), 1))
            .thenReturn(new RetentionBatchProcessor.OutboxBatch(List.of(), 0));

        job.run();

        assertThat(gauge(OUTBOX_GAUGE)).isEqualTo(1.0);
    }

    @Test
    void dryRun_publishesZero_becauseNothingIsDeleted() {
        config.setEventsTtlDays(30);
        config.setOutboxTtlDays(14);
        config.setDryRun(true);
        when(batchProcessor.countEligibleEvents(any(), any())).thenReturn(42L);
        when(batchProcessor.countEligibleOutbox(any())).thenReturn(7L);

        job.run();

        assertThat(gauge(EVENTS_GAUGE))
            .as("dry-run ничего не удаляет — gauge последнего прохода обязан быть 0, "
                + "а не «удалил бы 42»")
            .isEqualTo(0.0);
        assertThat(gauge(OUTBOX_GAUGE)).isEqualTo(0.0);
    }

    @Test
    void theCounterKeepsTheTotalWhileTheGaugeShowsTheLastPass() {
        config.setEventsTtlDays(30);
        // Каждый проход забирает один непустой батч и добирает пустой (конец
        // eligible). Первый проход удаляет 2 строки, второй — 1. Ответы мока
        // чередуются по счётчику вызовов, а не «фиксированной очередью»:
        // очередь consumed-бы первым же проходом.
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(batchProcessor.claimAndDeleteEventsBatch(any(), eq(25), any()))
            .thenAnswer(invocation -> {
                int n = call.getAndIncrement();
                return (n % 2 == 0)
                    ? new RetentionBatchProcessor.EventsBatch(List.of((long) n), n == 0 ? 2 : 1)
                    : new RetentionBatchProcessor.EventsBatch(List.of(), 0);
            });

        job.run();
        job.run();

        assertThat(registry.get("zbpm.retention.events.deleted.total").counter().count())
            .as("счётчик — сумма за всё время (2 + 1)")
            .isEqualTo(3.0);
        assertThat(gauge(EVENTS_GAUGE))
            .as("gauge — только последний проход (1)")
            .isEqualTo(1.0);
    }

    private double gauge(String name) {
        Gauge g = registry.find(name).gauge();
        assertThat(g).as("метрика %s обязана быть зарегистрирована", name).isNotNull();
        return g.value();
    }
}
