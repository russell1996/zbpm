package com.zorrodev.bpm.engine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * WO-C8-36 (H-2): durable-дедуп отправок результата задачи.
 *
 * <p>Зачем в БД, а не в памяти JVM: дедуп нужен для защиты от confirm-loss
 * переотправки ОДНОЙ отправки. На одной реплике этого хватало, но проект
 * гоняет три реплики на одном PG ({@code docker-compose.multi.yml}) — там
 * каждый процесс держал своё множество, и повтор списывал бюджет ретраев
 * по одному разу на реплику. Критерий WO (защита от повторного расхода) на
 * такой топологии просто не держался, и ни один тест этого не видел: все
 * тесты жили в одном процессе.
 *
 * <p>Почему INSERT, а не SELECT-then-INSERT: чтение «есть ли маркер» и запись
 * маркера — две раздельные операции, между которыми другая реплика успевает
 * вставить свой. Решение о захвате принимает САМА БД: успешный INSERT = наш,
 * конфликт по PK = дубль (проигравший откатывается, а не идёт дальше).
 *
 * <p>Маркер ставится в ТОЙ ЖЕ транзакции, что и списание бюджета. Отсюда
 * следует важное свойство, ради которого механизм и выбран: откат транзакции
 * (сбой внутри обработки) убирает маркер САМ, поэтому потерявшийся сбой
 * остаётся переигрываемым тем же {@code completionId}. Ручного снятия
 * маркера в коде нет и не нужно — «red-team 1.4» закрывается структурно.
 *
 * <p>Почему не {@code INSERT ... ON CONFLICT DO NOTHING} (дешевле на одну
 * round-trip): такая грамматика есть только у PostgreSQL, а юнит-тесты ходят
 * по H2, где её нет (проверено пробой в {@code PgRateLimiter}). Здесь важнее
 * переносимость SQL, чем один лишний round-trip на пути отказа.
 *
 * <p>TTL: маркеры живут дольше окна confirm-loss воркера (кеш результатов у
 * воркера — 10 минут), потом чистятся существующим механизмом
 * {@link CompletionDedupCleanupJob}. Объём таблицы ограничен числом
 * отправок за TTL, а не историей процесса.
 */
@Slf4j
@Component
public class CompletionDedupStore {

    private static final String INSERT_MARKER =
        "INSERT INTO completion_dedup (completion_id, created_at) VALUES (?, ?)";
    private static final String MARKER_EXISTS =
        "SELECT count(*) FROM completion_dedup WHERE completion_id = ?";
    private static final String DELETE_EXPIRED =
        "DELETE FROM completion_dedup WHERE created_at < ?";

    private final JdbcTemplate jdbcTemplate;

    public CompletionDedupStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Захватывает {@code completionId} за текущей транзакцией.
     *
     * @return {@code true} — маркер вставлен нами, отправка наша (бюджет трогаем);
     *         {@code false} — маркер уже есть, это дубль чужой (или нашей
     *         переотправки после подтверждённого коммита) отправки, бюджет НЕ трогаем.
     */
    public boolean claim(String completionId, long ttlSeconds) {
        if (completionId == null || completionId.isBlank()) {
            // Старый воркер без идентификатора отправки: дедупировать нечем.
            // Не fail-closed — это обратная совместимость, а не защита (E-3).
            return true;
        }
        int inserted;
        try {
            inserted = jdbcTemplate.update(INSERT_MARKER, completionId, Timestamp.from(Instant.now()));
        } catch (org.springframework.dao.DuplicateKeyException alreadyClaimed) {
            // PK занят — отправка уже обработана (возможно, на другой реплике).
            return false;
        }
        if (inserted != 1) {
            // Строка не вставилась и не конфликтнула: БД в неожиданном состоянии.
            // Fail-closed — молча продолжив, мы бы расходовали бюджет дважды.
            throw new IllegalStateException(
                "completion_dedup: unexpected insert result " + inserted + " for id " + completionId);
        }
        return true;
    }

    /** Есть ли уже маркер (только для диагностики/тестов; решение принимает claim). */
    public boolean isClaimed(String completionId) {
        return jdbcTemplate.queryForObject(MARKER_EXISTS, Integer.class, completionId) > 0;
    }

    /**
     * TTL-очистка: удаляет маркеры старше отсечки. Возвращает число удалённых.
     * Вызывается существующим по расписанию {@link CompletionDedupCleanupJob}.
     */
    public int deleteExpiredBefore(Timestamp cutoff) {
        return jdbcTemplate.update(DELETE_EXPIRED, cutoff);
    }
}
