package com.zorrodev.bpm.engine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
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
 * конфликт по PK = дубль, и проигравший НЕ идёт дальше (бюджет не трогает).
 *
 * <p>Раунд 4: формулировка «проигравший откатывается» была удалена как неверная.
 * После F-1 проигравший на PostgreSQL НЕ откатывается — конфликт там не ошибка,
 * а признак «маркер уже чужой»; его транзакция остаётся годной к записи, и
 * вызывающий просто заканчивает работу без расхода бюджета. На H2 (ветка с
 * {@link org.springframework.dao.DuplicateKeyException}) откатывается только
 * statement — тоже не транзакция. Откатывается всё вместе лишь в одном случае:
 * транзакция вызывающего сама упала, и тогда маркер уходит вместе с ней, что и
 * оставляет сбой переигрываемым (см. ниже).
 *
 * <p>Маркер ставится в ТОЙ ЖЕ транзакции, что и списание бюджета. Отсюда
 * следует важное свойство, ради которого механизм и выбран: откат транзакции
 * (сбой внутри обработки) убирает маркер САМ, поэтому потерявшийся сбой
 * остаётся переигрываемым тем же {@code completionId}. Ручного снятия
 * маркера в коде нет и не нужно — «red-team 1.4» закрывается структурно.
 *
 * <p>WO-C8-36 (F-1, red-team раунда 2) — почему конфликт больше НЕ ловится
 * исключением. Предыдущая версия делала {@code INSERT} и отлавливала
 * {@link org.springframework.dao.DuplicateKeyException}. На PostgreSQL это
 * ломает ВСЮ транзакцию вызывающего: дубль PK (SQLState 23505) переводит её в
 * aborted, каждая следующая команда отвергается с 25P02, а {@code commit()}
 * pgjdbc на aborted-транзакции возвращает {@code ROLLBACK} БЕЗ исключения —
 * то есть Spring считает, что закоммитилось. Воспроизведено живьём: всё
 * записанное до claim'а в этой транзакции исчезало молча. Сегодня перед
 * claim'ом пишущих операций нет ({@code lockInstanceFirst} — только чтения и
 * локи), поэтому дефект был безвреден — но ловушка оставалась, а инвариант
 * «перед claim'ом ничего не пишется» не был закреплён ничем.
 *
 * <p>Поэтому на PostgreSQL — {@code INSERT … ON CONFLICT (completion_id) DO
 * NOTHING}: решение о захвате принимает БД, конфликт НЕ является ошибкой,
 * транзакция остаётся пригодной к записи, а результат — число затронутых
 * строк (1 = наш, 0 = дубль). Именно это и проверяет PG-IT на двух реальных
 * транзакциях.
 *
 * <p>Два диалекта, а не один SQL: H2 грамматики {@code ON CONFLICT} не знает
 * (проба прямой JDBC на 2.4.240: {@code Syntax error … [*]ON CONFLICT (id)
 * DO NOTHING [42000-240]}; {@code MERGE … WHEN NOT MATCHED} тоже не ест), а
 * на H2 живут юнит-тесты движка с реальным бином. H2-ветка оставляет INSERT +
 * отлов {@code DuplicateKeyException} — там конфликт роняет только statement,
 * транзакция выживает (в этом ровно и расхождение H2/PG). Product-detect по
 * метаданным соединения — тот же приём, что в {@link AdvisoryDeployLock} и
 * {@code VariableDbOperationsImpl}; конфликт исключения с переводом 23505 в
 * {@code DuplicateKeyException} больше не является несущей конструкцией
 * корректности.
 *
 * <p>TTL: маркеры живут дольше окна confirm-loss воркера (кеш результатов у
 * воркера — 10 минут), потом чистятся существующим механизмом
 * {@link CompletionDedupCleanupJob}. Объём таблицы ограничен числом
 * отправок за TTL, а не историей процесса.
 */
@Slf4j
@Component
public class CompletionDedupStore {

    /**
     * PostgreSQL: конфликт по PK — не ошибка, а «маркер уже чужой». Возвращает
     * 1 (вставили) или 0 (дубль), и НЕ переводит транзакцию в aborted.
     */
    private static final String INSERT_MARKER_PG =
        "INSERT INTO completion_dedup (completion_id, created_at) VALUES (?, ?) "
            + "ON CONFLICT (completion_id) DO NOTHING";
    /** H2: {@code ON CONFLICT} нет, конфликт ловится исключением (транзакция выживает). */
    private static final String INSERT_MARKER_H2 =
        "INSERT INTO completion_dedup (completion_id, created_at) VALUES (?, ?)";
    private static final String MARKER_EXISTS =
        "SELECT count(*) FROM completion_dedup WHERE completion_id = ?";
    private static final String DELETE_EXPIRED =
        "DELETE FROM completion_dedup WHERE created_at < ?";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private volatile String databaseProduct;

    public CompletionDedupStore(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    /** Product-detect как в {@link AdvisoryDeployLock}: JdbcTemplate + DataSource, volatile-кэш. */
    private boolean isPostgres() {
        String cached = databaseProduct;
        if (cached == null) {
            try (var conn = dataSource.getConnection()) {
                cached = conn.getMetaData().getDatabaseProductName();
                databaseProduct = cached;
            } catch (Exception e) {
                // Fail-closed к БЕЗОПАСНОМ варианту: PostgreSQL — единственная СУБД,
                // где ON CONFLICT обязателен (конфликт иначе роняет транзакцию).
                log.warn("CompletionDedupStore: не удалось определить СУБД, считаем PostgreSQL", e);
                return true;
            }
        }
        return "PostgreSQL".equals(cached);
    }

    /**
     * Захватывает {@code completionId} за текущей транзакцией.
     *
     * <p>Инвариант вызывающего (проверен PG-IT на двух реальных транзакциях):
     * <b>после возврата {@code false} транзакция остаётся годной к записи</b> —
     * дубль не должен обнулять то, что вызывающий успел записать. На PG это
     * обеспечивает {@code ON CONFLICT DO NOTHING} (0 строк, не ошибка); H2
     * переживает и исключение, поэтому обе ветки дают один контракт.
     *
     * <p><b>WO-C8-36 (раунд 4, F-8): TTL сюда НЕ передаётся.</b> Прежняя сигнатура
     * принимала {@code ttlSeconds}, но параметр не читался нигде в теле метода —
     * время жизни маркера целиком задаёт порасписание
     * {@link CompletionDedupCleanupJob}, а не этот вызов. Вызывающая сторона
     * держала поле с javadoc «совпадает с TTL-очисткой, иначе маркер мог бы быть
     * удалён раньше», то есть мину под следующую правку в самом чувствительном
     * месте (транзакционный захват): поверивший javadoc вставил бы сюда логику по
     * TTL и не заметил, что очистка живёт по часовой отдельно (класс P-14).
     *
     * @return {@code true} — маркер вставлен нами, отправка наша (бюджет трогаем);
     *         {@code false} — маркер уже есть, это дубль чужой (или нашей
     *         переотправки после подтверждённого коммита) отправки, бюджет НЕ трогаем.
     */
    public boolean claim(String completionId) {
        if (completionId == null || completionId.isBlank()) {
            // Старый воркер без идентификатора отправки: дедупировать нечем.
            // Не fail-closed — это обратная совместимость, а не защита (E-3).
            return true;
        }
        if (isPostgres()) {
            int affected = jdbcTemplate.update(INSERT_MARKER_PG, completionId,
                Timestamp.from(Instant.now()));
            // 1 — маркер наш; 0 — PK уже занят (дубль). Других значений у PG не бывает,
            // но fail-closed на неожиданном — молча продолжив, мы бы списали бюджет дважды.
            if (affected == 1) {
                return true;
            }
            if (affected == 0) {
                return false;
            }
            throw new IllegalStateException(
                "completion_dedup: unexpected insert result " + affected + " for id " + completionId);
        }
        try {
            int inserted = jdbcTemplate.update(INSERT_MARKER_H2, completionId,
                Timestamp.from(Instant.now()));
            if (inserted != 1) {
                // Строка не вставилась и не конфликтнула: БД в неожиданном состоянии.
                throw new IllegalStateException(
                    "completion_dedup: unexpected insert result " + inserted + " for id " + completionId);
            }
            return true;
        } catch (org.springframework.dao.DuplicateKeyException alreadyClaimed) {
            // PK занят — отправка уже обработана (возможно, на другой реплике).
            // На H2 statement откатывается, транзакция остаётся годной.
            return false;
        }
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
