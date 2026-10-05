package com.zorrodev.bpm.engine;

import com.zorrodev.bpm.engine.scheduler.FeedPositionAssigner;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NEW5-04 (WO-QW-10): метрика возраста бэклога не должна зависеть от часового пояса
 * сессии БД.
 *
 * <p>Суть дефекта: {@code events.occurred_at} на PostgreSQL — {@code timestamp WITHOUT
 * time zone} (сверено с живой БД, не по changelog: там написано
 * {@code datetime with timezone}, а Liquibase кладёт without-tz), а {@code now()}
 * возвращает {@code timestamptz}. Нативное {@code now() - MIN(occurred_at)} заставляет
 * PG прочитать значение как СЕССИОННОЕ локальное время, и одна и та же строка даёт
 * 120 секунд при session TZ=UTC и 18120 при Asia/Almaty — ровно +5 часов. В проде
 * приложение ходит с `TZ=Asia/Almaty` (docker-compose), а CI-контейнер — без TZ (UTC):
 * одна и та же метрика означает разные вещи в двух средах.
 *
 * <p>Тест делает расхождение видимым: JVM зафиксирована в UTC, а сессия БД внутри
 * той же транзакции переведена на Asia/Almaty — то есть ровно та пара, которую даёт
 * прод. Метрика обязана показать фактический возраст строки.
 *
 * <p>POF: на коде до фикса (нативная арифметика в SQL) ассерт падает — метрика
 * показывает ~18120 вместо ~120.
 */
public class FeedBacklogAgeTimeZonePgIT extends PostgresIT {

    /** Строка старше AGE_SECONDS — возраст известен точно. */
    private static final long AGE_SECONDS = 120;
    private static final long SEQUENCE = 9_900_101L;

    @Autowired JdbcTemplate jdbc;
    @Autowired FeedPositionAssigner assigner;
    @Autowired MeterRegistry meterRegistry;
    @Autowired PlatformTransactionManager txManager;

    private TimeZone originalTz;

    @BeforeEach
    void fixJvmZoneToUtc() {
        // Метрика обязана считаться по часам приложения. Фиксируем JVM в UTC, чтобы
        // тест не зависел от TZ хоста, и контраст с сессией БД был воспроизводим.
        originalTz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreJvmZone() {
        TimeZone.setDefault(originalTz);
        jdbc.update("DELETE FROM events WHERE sequence = ?", SEQUENCE);
    }

    @Test
    void backlogAgeMetric_ignoresDatabaseSessionTimeZone() {
        // Сессия БД переводится на Asia/Almaty ВНУТРИ той же транзакции, что и тик
        // ассигнера, — те же соединения, та же пара JVM=UTC / сессия=Asia/Almaty,
        // которую даёт прод (docker-compose: TZ=Asia/Almaty для app).
        new TransactionTemplate(txManager).execute(status -> {
            jdbc.execute("SET TIME ZONE 'Asia/Almaty'");
            // Строка вставляется в ЭТОЙ ЖЕ транзакции: её xmin равен текущему
            // снапшоту, поэтому оба прохода ассигнера (xmin < pg_snapshot_xmin)
            // её не видят — ряд остаётся в бэклоке, и метрика в конце тика
            // показывает его возраст. Иначе тик разобрал бы бэклог и метрика
            // стала бы 0 — то есть проверяла бы не то.
            LocalDateTime occurredAt = LocalDateTime.ofInstant(Instant.now().minusSeconds(AGE_SECONDS),
                ZoneId.systemDefault());
            jdbc.update("INSERT INTO events (sequence, id, type, version, occurred_at, feed_position) "
                + "VALUES (?, gen_random_uuid(), 'PING', 1, ?, NULL)", SEQUENCE, occurredAt);
            assigner.assignPendingPositions();
            return null;
        });

        Long stillPending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM events WHERE sequence = " + SEQUENCE + " AND feed_position IS NULL",
            Long.class);
        assertThat(stillPending).as("предпосылка теста: ряд остался в бэклоке").isEqualTo(1L);

        long reported = feedAgeSeconds();
        assertThat(reported)
            .as("метрика возраста не должна зависеть от TZ сессии БД (JVM=UTC, сессия=Asia/Almaty)")
            .isBetween(AGE_SECONDS - 30, AGE_SECONDS + 30);
    }

    /**
     * Контроль к предыдущему: когда ничего не ждёт, метрика обязана быть нулевой —
     * иначе «нулевая» может означать «испорченный расчёт», а не «пусто».
     */
    @Test
    void backlogAgeMetric_zeroWhenNothingPending() {
        new TransactionTemplate(txManager).execute(status -> {
            jdbc.execute("SET TIME ZONE 'Asia/Almaty'");
            assigner.assignPendingPositions();
            return null;
        });

        Long pending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM events WHERE feed_position IS NULL", Long.class);
        assertThat(pending).as("ассIGNER разобрал весь хвост — нечего считать").isZero();
        assertThat(feedAgeSeconds()).as("пустой бэклог → возраст 0").isZero();
    }

    /** Читаем РЕАЛЬНЫ экспонированный gauge, а не внутреннее поле (оператор видит то же). */
    private long feedAgeSeconds() {
        io.micrometer.core.instrument.Gauge gauge = meterRegistry.find("zbpm.feed.age.max").gauge();
        assertThat(gauge).as("gauge zbpm.feed.age.max зарегистрирован").isNotNull();
        return (long) gauge.value();
    }
}
