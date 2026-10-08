package com.zorrodev.bpm.engine;

import com.zorrodev.bpm.engine.entity.VariablePresetEntity;
import com.zorrodev.bpm.engine.entity.VariablePresetTargetKind;
import com.zorrodev.bpm.engine.entity.VariablePresetVisibility;
import com.zorrodev.bpm.engine.repository.VariablePresetRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-VT-1 критерий 1: changesets 20261007-120/121 на реальном PostgreSQL 16.
 *
 * <p>Доказывается АРТЕФАКТ, а не строка (G-N/G9): схему гоняет Liquibase
 * (как на проде при старте контекста), SQL changeset'а в тесте не написан ни
 * разу — вычеркни changeset, и тесты упадут на отсутствии таблицы. Своя схема
 * на каждый тест, чужие БД не трогаются.
 */
@Tag("pg")
class VariablePresetsPgIT extends PostgresIT {

    private static final String SCHEMA = "vt1_presets_pg_it";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private VariablePresetRepository presetRepository;

    private static String jdbcUrl() {
        String h = System.getenv("PG_HOST"); if (h == null) h = System.getProperty("PG_HOST", "127.0.0.1");
        String p = System.getenv("PG_PORT"); if (p == null) p = System.getProperty("PG_PORT", "5432");
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

    private void runMasterUpTo(int changesToRun) throws Exception {
        try (Connection c = open()) {
            liquibaseOn(c).update(changesToRun, new Contexts(), new LabelExpression());
        }
    }

    /**
     * WO-VT-1 раунд 2 (Б-3): позиция changeset'а по его id в
     * {@code databasechangelog} полного прогона — а не «последние N».
     * Допущение «наши два — последние» (ниже) разошлось, как только в мастер
     * встали 120/121 после 119, и уронило чужой IN-3-класс в полном
     * {@code ci/run-pg-tests.sh}. Id — константы, инъекции нет.
     */
    private int orderOf(String changeSetId) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT orderexecuted FROM " + SCHEMA
                 + ".databasechangelog WHERE id = '" + changeSetId + "'")) {
            assertThat(rs.next()).as("changeset %s записан в databasechangelog", changeSetId).isTrue();
            int order = rs.getInt(1);
            assertThat(rs.next()).as("changeset %s записан ровно один раз", changeSetId).isFalse();
            return order;
        }
    }

    /**
     * WO-VT-1 раунд 2 (Б-3): останавливает мастер-чанжлог ровно ПЕРЕД
     * changeset'ом с данным id. Полный прогон → позиция по id → чистая схема →
     * прогон до позиции-1. Хрупкое «первые N-2» больше нигде не используется.
     */
    private void runMasterUpToBefore(String changeSetId) throws Exception {
        runMaster();
        int stopAt = orderOf(changeSetId) - 1;
        createOwnSchema();
        runMasterUpTo(stopAt);
    }

    private void rollbackLast(int count) throws Exception {
        try (Connection c = open()) {
            liquibaseOn(c).rollback(count, new Contexts(), new LabelExpression());
        }
    }

    // ==================== helpers ====================

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

    private String columnType(String table, String column) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT data_type FROM information_schema.columns "
                 + "WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + table
                 + "' AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    // ==================== 1. применение: таблицы, типы, CHECK, комментарии ====================

    @Test
    void masterRun_createsPresetTables_withJsonbChecksAndComments() throws Exception {
        runMaster();

        assertThat(tableExists("variable_presets")).isTrue();
        assertThat(tableExists("variable_preset_favorites")).isTrue();
        assertThat(tableExists("variable_preset_history")).isTrue();

        // jsonb на PG (на H2 — text, другой changeset): H2-тест этого не видит (P-17).
        assertThat(columnType("variable_presets", "variables")).isEqualTo("jsonb");
        assertThat(columnType("variable_preset_history", "variables_after")).isEqualTo("jsonb");

        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT conname FROM pg_constraint WHERE conrelid = '" + SCHEMA + ".variable_presets'::regclass"
                     + " UNION ALL SELECT conname FROM pg_constraint"
                     + " WHERE conrelid = '" + SCHEMA + ".variable_preset_history'::regclass")) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            while (rs.next()) names.add(rs.getString(1));
            assertThat(names).contains("ck_variable_presets__target_kind",
                "ck_variable_presets__visibility", "uq_variable_presets__owner_scope_name",
                "ck_preset_history__action");
        }

        // Каскад избранного/истории за шаблоном — confdeltype 'c'.
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT count(*) FROM pg_constraint WHERE conrelid = '" + SCHEMA + ".variable_preset_favorites'::regclass"
                     + " AND confdeltype = 'c'")) {
            rs.next();
            assertThat(rs.getInt(1)).as("FK избранного — ON DELETE CASCADE").isPositive();
        }

        // Комментарии колонок (gate WO-OPS-10).
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT obj_description('" + SCHEMA + ".variable_presets'::regclass)")) {
            rs.next();
            assertThat(rs.getString(1)).contains("WO-VT-1");
        }
    }

    // ==================== 2. артефакт: стоп до наших — таблиц нет; прогон — есть ====================

    @Test
    void stopBeforeOurs_tablesAbsent_thenArriveWithMaster() throws Exception {
        // Б-3: наши позиции — по id, а не «последние два» (следующая миграция
        // этот тест не роняет: 120 идёт раньше 121, остальное неважно).
        runMaster();
        assertThat(orderOf("20261007-120"))
            .as("120 записан раньше 121")
            .isLessThan(orderOf("20261007-121"));

        runMasterUpToBefore("20261007-120");

        assertThat(tableExists("user_task_candidates")).as("остановились ПОСЛЕ 118").isTrue();
        assertThat(tableExists("variable_presets"))
            .as("остановились ДО наших changeset'ов").isFalse();

        runMaster();
        assertThat(tableExists("variable_presets")).isTrue();
        assertThat(tableExists("variable_preset_favorites")).isTrue();
        assertThat(tableExists("variable_preset_history")).isTrue();
    }

    // ==================== 3. откат: наши уходят, соседние остаются ====================

    @Test
    void rollbackLastTwo_dropsOurs_keepsNeighbours() throws Exception {
        runMaster();
        assertThat(orderOf("20261007-120"))
            .as("120 записан раньше 121")
            .isLessThan(orderOf("20261007-121"));

        // Откатываем от вершины до 120 ВКЛЮЧИТЕЛЬНО — сколько бы миграций ни
        // встало после нас (своя схема, чужого не трогаем); затем накат обратно.
        int tip = recordedChangeSets();
        int backToOurs = tip - orderOf("20261007-120") + 1;
        assertThat(backToOurs).as("120 входит в откат").isPositive();

        createOwnSchema();
        runMaster();
        assertThat(tableExists("variable_presets")).isTrue();

        rollbackLast(backToOurs);
        assertThat(tableExists("variable_presets")).as("откат убрал 120").isFalse();
        assertThat(tableExists("variable_preset_favorites")).as("откат убрал 121").isFalse();
        assertThat(tableExists("user_task_candidates")).as("соседний 118 жив").isTrue();

        // И накат обратно — идемпотентен.
        runMaster();
        assertThat(tableExists("variable_presets")).isTrue();
    }

    // ==================== 4. UNIQUE и каскад на живой схеме ====================

    @Test
    void uniqueName_withinBinding_rejectedAtDb() {
        UUID owner = UUID.randomUUID();
        insertPresetRaw(UUID.randomUUID(), owner, "k1", "START", "", "dupname");
        assertThatThrownBy(
            () -> insertPresetRaw(UUID.randomUUID(), owner, "k1", "START", "", "dupname"))
            .as("дубль (owner, привязка, name) — UNIQUE uq_variable_presets__owner_scope_name")
            .hasMessageContaining("uq_variable_presets__owner_scope_name");
    }

    @Test
    void deletePreset_cascadesFavoritesAndHistory() {
        UUID presetId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        insertPresetRaw(presetId, owner, "kc", "START", "", "n1");
        jdbc.update("INSERT INTO variable_preset_favorites(user_id, preset_id, created_at) VALUES (?,?,?)",
            owner, presetId, Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO variable_preset_history(id, preset_id, action, actor_user_id, at, variables_after)"
            + " VALUES (?,?,?,?,?,?::jsonb)", UUID.randomUUID(), presetId, "CREATE", owner,
            Timestamp.from(Instant.now()), "[]");

        jdbc.update("DELETE FROM variable_presets WHERE id = ?", presetId);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM variable_preset_favorites WHERE preset_id = ?", Long.class, presetId))
            .as("избранное каскадно удалено").isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM variable_preset_history WHERE preset_id = ?", Long.class, presetId))
            .as("история каскадно удалена").isZero();
    }

    // ==================== 5. jsonb round-trip с кириллицей через entity ====================

    @Test
    void entityRoundTrip_cyrillicJsonb_survivesOnPg() {
        VariablePresetEntity entity = new VariablePresetEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessDefinitionKey("pg-" + UUID.randomUUID());
        entity.setTargetKind(VariablePresetTargetKind.START);
        entity.setTargetRef("");
        entity.setName("кириллица-имя");
        entity.setVariables("[{\"name\":\"stages\",\"type\":\"JSON\",\"value\":\"{\\\"t\\\":\\\"Привет\\\"}\"}]");
        entity.setOwnerUserId(UUID.randomUUID());
        entity.setVisibility(VariablePresetVisibility.PRIVATE);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        entity.setVersion(0);

        // P-17-ловушка: Instant → timestamptz биндится только на живой PG.
        presetRepository.saveAndFlush(entity);
        VariablePresetEntity reread = presetRepository.findById(entity.getId()).orElseThrow();
        assertThat(reread.getName()).isEqualTo("кириллица-имя");
        assertThat(reread.getVariables()).contains("Привет");
        assertThat(reread.getTargetRef()).isEmpty();
    }

    // ==================== helpers ====================

    private void insertPresetRaw(UUID id, UUID owner, String key, String kind, String ref, String name) {
        // P-17: голая строка в jsonb не биндится (PG: Can't infer) — явный каст.
        jdbc.update("INSERT INTO variable_presets(id, process_definition_key, target_kind, target_ref,"
            + " name, variables, owner_user_id, visibility, created_at, updated_at, version)"
            + " VALUES (?,?,?,?,?,?::jsonb,?, 'PRIVATE',?,?,0)",
            id, key, kind, ref, name, "[]", owner, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }
}
