package com.zorrodev.bpm.engine;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-IN-3: бэкфилл кандидатов-ГРУПП (changeset 20261006-119) на строках, которые ДО него были.
 *
 * <p>Почему отдельный класс и не в общем контексте: бэкфилл по определению работает на данных,
 * созданных ДО changeset'а. В {@code public}-схеме таблица появляется на старте контекста и
 * бэкфиллит ПУСТУЮ таблицу — «бэкфилл применился» там недоказуемо, а на непустой базе именно он и
 * нужен: без него все задачи, созданные до деплоя, исчезли бы из фильтров кандидата.
 *
 * <p>Схема прокручивается дважды, и это ключевая часть проверки: сначала ПОЛНЫЙ мастер-чанжлог
 * (чтобы узнать, сколько changeset'ов всего), потом схема пересоздаётся и мастер применяется
 * заново на {@code N-2} changeset'ах — ровно до наших двух. Только после этого в таблицу кладутся
 * легаси-строки и changeset'ы доезжают. SQL бэкфилла в тесте не написан ни разу: его выполняет
 * Liquibase (G-N/G9 — вычеркни changeset, и тест упадёт на отсутствии таблицы, а не останется
 * «зелёным»).
 *
 * <p>Точка остановки проверяется явно (user_tasks есть, user_task_candidates ещё нет): если бы
 * счёт changeset'ов разошёлся, тест упал бы с внятным сообщением, а не «прошёл бы мимо».
 *
 * <p>Изоляция: своя схема {@value #SCHEMA}, создаётся и сносится на каждый тест. Общая
 * {@code public}-схема и любые чужие базы не трогаются.
 */
@Tag("pg")
class UserTaskCandidatesBackfillPgIT {

    private static final String SCHEMA = "in3_backfill_pg_it";

    /** Легаси-колонка → ожидаемые GROUP-кандидаты. NULL и пустая строка — не кандидаты вовсе. */
    private static final Map<String, List<String>> LEGACY = new LinkedHashMap<>();

    static {
        LEGACY.put("sales", List.of("sales"));
        LEGACY.put(" sales , east ", List.of("sales", "east"));
        LEGACY.put("a,,b", List.of("a", "b"));
        LEGACY.put(null, List.of());
        LEGACY.put("", List.of());
    }

    private static String jdbcUrl() {
        String h = System.getenv("PG_HOST"); if (h == null) h = System.getProperty("PG_HOST", "127.0.0.1");
        String p = System.getenv("PG_PORT"); if (p == null) p = System.getProperty("PG_PORT", "55432");
        String d = System.getenv("PG_DB"); if (d == null) d = System.getProperty("PG_DB", "zorrobpm-db");
        String u = System.getenv("PG_USER"); if (u == null) u = System.getProperty("PG_USER", "zorrodev");
        String w = System.getenv("PG_PASSWORD"); if (w == null) w = System.getProperty("PG_PASSWORD", "zorrodev");
        return "jdbc:postgresql://" + h + ":" + p + "/" + d
            + "?sslmode=disable&user=" + u + "&password=" + w + "&currentSchema=" + SCHEMA;
    }

    private Connection open() throws Exception { return DriverManager.getConnection(jdbcUrl()); }

    @BeforeEach
    void createOwnSchema() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            s.execute("CREATE SCHEMA " + SCHEMA);
        }
    }

    @AfterEach
    void dropOwnSchema() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private Liquibase liquibaseOn(Connection c) throws Exception {
        Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
        db.setDefaultSchemaName(SCHEMA);
        return new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), db);
    }

    private void runMaster() throws Exception {
        try (Connection c = open()) {
            liquibaseOn(c).update(new Contexts(), new LabelExpression());
        }
    }

    /** Полный прогон + число записанных changeset'ов: столько нужно отнять, чтобы остановиться
     *  ровно перед двумя нашими. */
    private int fullRunAndCount() throws Exception {
        runMaster();
        return recordedChangeSets();
    }

    /** Вычёркивает отметку о выполнении бэкфилла, чтобы следующий update выполнил его ЗАНОВО. */
    private void forgetBackfillChangeset() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM " + SCHEMA + ".databasechangelog WHERE id LIKE '20261006-119%'");
        }
    }

    /**
     * Останавливает мастер-чанжлог на {@code N-2} changeset'ах — на двух наших.
     *
     * <p>ЗАВИСИМОСТЬ, которую надо знать: наши два changeset'а обязаны быть ПОСЛЕДНИМИ в
     * master-чанжлоге (includeAll сортирует по имени файла, а наши имена — самые новые). Если
     * появится более поздняя миграция, счёт разойдётся. Падение будет ГРОМКИМ и понятным, а не
     * тихим: следующая проверка требует, чтобы {@code user_task_candidates} ещё НЕ существовала,
     * и при лишнем changeset'е она не сойдётся. Фикс на будущее — искать позицию своих
     * changeset'ов по id, а не по «последние два».
     */
    private void runMasterUpTo(int changesToRun) throws Exception {
        try (Connection c = open()) {
            liquibaseOn(c).update(changesToRun, new Contexts(), new LabelExpression());
        }
    }

    private int recordedChangeSets() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + SCHEMA + ".databasechangelog")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM information_schema.tables "
                 + "WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + table + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    // ==================== 1. бэкфилл на непустой легаси-таблице ====================

    @Test
    void backfill_copiesEveryLegacyGroupWithAuthorizationTrimAndSkipsBlankOnes() throws Exception {
        int total = fullRunAndCount();

        createOwnSchema();
        runMasterUpTo(total - 2);

        assertThat(tableExists("user_tasks")).as("остановились ПОСЛЕ создания user_tasks").isTrue();
        assertThat(tableExists("user_task_candidates"))
            .as("остановились ДО наших changeset'ов — иначе проверяли бы пустую таблицу")
            .isFalse();

        seedLegacyRows();
        runMaster();

        Map<String, List<String>> byLegacyValue = groupCandidatesByLegacyValue();
        LEGACY.forEach((legacyValue, expected) ->
            assertThat(byLegacyValue.get(groupKey(legacyValue)))
                .as("легаси-колонка %s", legacyValue == null ? "NULL" : "'" + legacyValue + "'")
                .containsExactlyInAnyOrderElementsOf(expected));
    }

    /**
     * Повторный прогон changeset'а (перезапуск деплоя) обязан быть безвредным: дубль PK на
     * PostgreSQL без {@code ON CONFLICT} перевёл бы транзакцию вызывающего в aborted — ровно тот
     * класс, который чинили в WO-C8-36 (F-1).
     *
     * <p>Повтор здесь НАСТОЯЩИЙ: строка changeset'а вычеркивается из {@code databasechangelog},
     * и Liquibase executes'ит его заново. Просто позвать {@code update} второй раз было бы
     * проверкой «ничего не делает» — такой тест зелёный всегда.
     */
    @Test
    void backfill_isRerunnable_withoutFailingOrDuplicating() throws Exception {
        int total = fullRunAndCount();

        createOwnSchema();
        runMasterUpTo(total - 2);
        seedLegacyRows();
        runMaster();

        assertThat(countByKind("GROUP")).as("до повтора кандидаты на месте").isEqualTo(5);

        forgetBackfillChangeset();
        runMaster();

        assertThat(groupCandidatesByLegacyValue().get(groupKey("sales")))
            .as("повторный прогон не задублировал кандидатов")
            .containsExactly("sales");
        assertThat(countByKind("GROUP")).isEqualTo(5);
    }

    /**
     * Кандидаты-ПОЛЬЗОВАТЕЛИ бэкфилля не имеют и не могут иметь: до changeset'а значение
     * {@code candidateUsers} не сохранялось нигде (E-IN2-1), легаси-данных для него не
     * существует. Проверяется на реальной схеме — пользовательские строки не появляются из
     * ничего, даже когда бэкфилл отрабатывает по-настоящему.
     */
    @Test
    void backfill_neverInventsCandidateUsers() throws Exception {
        int total = fullRunAndCount();

        createOwnSchema();
        runMasterUpTo(total - 2);
        seedLegacyRows();
        runMaster();

        assertThat(countByKind("USER"))
            .as("у пользователей не было ни одной записи — значит и бэкфиллить нечего")
            .isZero();
        assertThat(countByKind("GROUP")).isEqualTo(5);
    }

    // ==================== helpers ====================

    /**
     * Ключ группировки — сама легаси-колонка: она различает строки и не требует от таблицы
     * кандидатов ничего, чего там нет (меток времени, суррогатных id).
     */
    private static String key(String legacyValue) {
        // Ровно то, что вернёт SELECT coalesce(candidate_groups, '<NULL>'): значение колонки
        // БЕЗ кавычек (кавычки — только для отображения в сообщении ассерта).
        return legacyValue == null ? "<NULL>" : legacyValue;
    }

    /** Ключ карты: легаси-колонка + роль. */
    private static String groupKey(String legacyValue) {
        return key(legacyValue) + "|GROUP";
    }

    /** По одной задаче на каждый вариант колонки: null, пустая, с пробелами, с дырками, обычная. */
    private void seedLegacyRows() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO process_definitions (id, code, version, name, sha256, created_at) "
                + "VALUES (gen_random_uuid(), 'in3-backfill', 1, 'IN-3 backfill', "
                + "gen_random_uuid()::text, now() - interval '2 days')");
            for (String legacyValue : LEGACY.keySet()) {
                String literal = legacyValue == null
                    ? "NULL"
                    : "'" + legacyValue.replace("'", "''") + "'";
                s.execute("INSERT INTO process_instances "
                        + "(id, process_definition_id, started_at, completed_at, cancelled) "
                        + "SELECT gen_random_uuid(), id, now() - interval '1 day', "
                        + "now() - interval '1 hour', false FROM process_definitions "
                        + "WHERE code = 'in3-backfill'");
                // activities.token NOT NULL — токен нужен, иначе сидится на первом же INSERT.
                s.execute("INSERT INTO tokens (id) VALUES (gen_random_uuid())");
                s.execute("INSERT INTO activities "
                        + "(id, process_instance_id, bpmn_element_id, created_at, completed_at, type, status, token) "
                        + "SELECT gen_random_uuid(), pi.id, 'reviewTask', now() - interval '1 day', NULL, "
                        + "'USER_TASK', 'CREATED', t.id FROM process_instances pi "
                        + "JOIN process_definitions pd ON pd.id = pi.process_definition_id "
                        + "CROSS JOIN (SELECT id FROM tokens ORDER BY random() DESC LIMIT 1) t "
                        + "WHERE pd.code = 'in3-backfill' ORDER BY pi.started_at DESC LIMIT 1");
                s.execute("INSERT INTO user_tasks "
                        + "(id, process_instance_id, process_definition_id, bpmn_element_id, created_at, candidate_groups) "
                        + "SELECT a.id, a.process_instance_id, pi.process_definition_id, a.bpmn_element_id, "
                        + "a.created_at, " + literal + " FROM activities a "
                        + "JOIN process_instances pi ON pi.id = a.process_instance_id "
                        + "WHERE a.bpmn_element_id = 'reviewTask' AND a.process_instance_id NOT IN "
                        + "(SELECT process_instance_id FROM user_tasks) LIMIT 1");
            }
        }
    }

    /** Кандидаты, сгруппированные по легаси-колонке их задачи. */
    private Map<String, List<String>> groupCandidatesByLegacyValue() throws Exception {
        Map<String, List<String>> byLegacyValue = new LinkedHashMap<>();
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT coalesce(t.candidate_groups, '<NULL>') AS legacy_value, c.kind, c.candidate "
                     + "FROM " + SCHEMA + ".user_tasks t JOIN " + SCHEMA + ".user_task_candidates c "
                     + "ON c.user_task_id = t.id ORDER BY t.candidate_groups NULLS FIRST, c.kind, c.candidate")) {
            while (rs.next()) {
                byLegacyValue
                    .computeIfAbsent(rs.getString(1) + "|" + rs.getString(2), k -> new ArrayList<>())
                    .add(rs.getString(3));
            }
        }
        // groupBy схлопывает метку NULL-значения обратно в кандидаты: без него у задачи без
        // кандидатов не было бы ключа вообще, и «ничего не пришло» нельзя было бы отличить от
        // «строки не существует».
        for (String legacyValue : LEGACY.keySet()) {
            byLegacyValue.putIfAbsent(groupKey(legacyValue), List.of());
        }
        return byLegacyValue;
    }

    private int countByKind(String kind) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + SCHEMA
                 + ".user_task_candidates WHERE kind = '" + kind + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
