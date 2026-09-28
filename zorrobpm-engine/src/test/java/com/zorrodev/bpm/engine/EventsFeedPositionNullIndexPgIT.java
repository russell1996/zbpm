package com.zorrodev.bpm.engine;

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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-23 (NEW4-04): {@code FeedPositionAssigner} делает до 6 запросов с
 * {@code WHERE feed_position IS NULL} на холостом тике каждые 2с, а единственный
 * существующий индекс {@code uq_events__feed_position} — обратный предикат
 * ({@code WHERE feed_position IS NOT NULL}). Changeset 20260928-116 добавляет
 * частичный индекс {@code idx_events_unassigned ON events(sequence)
 * WHERE feed_position IS NULL} (CONCURRENTLY, вне транзакции).
 *
 * <p>Own schema, never touches shared public (same isolation idiom as
 * {@code FkIndexesPgIT}): G-N держится на прогоне НАСТОЯЩЕГО master-changelog
 * (артефакт доказывается через {@code pg_indexes} после миграции, а не копией
 * её SQL), а RED-направление — снятием индекса и возвратом Seq Scan на том же
 * запросе.
 */
@Tag("pg")
class EventsFeedPositionNullIndexPgIT {

    private static final String SCHEMA = "ops23_feed_null_index_pg_it";

    private static String jdbcUrl() {
        String h = System.getenv("PG_HOST"); if (h == null) h = System.getProperty("PG_HOST", "127.0.0.1");
        String p = System.getenv("PG_PORT"); if (p == null) p = System.getProperty("PG_PORT", "55432");
        String d = System.getenv("PG_DB"); if (d == null) d = System.getProperty("PG_DB", "zorrobpm-db");
        String u = System.getenv("PG_USER"); if (u == null) u = System.getProperty("PG_USER", "zorrodev");
        String w = System.getenv("PG_PASSWORD"); if (w == null) w = System.getProperty("PG_PASSWORD", "zorrodev");
        return "jdbc:postgresql://" + h + ":" + p + "/" + d + "?sslmode=disable&user=" + u + "&password=" + w + "&currentSchema=" + SCHEMA;
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

    private void runMasterChangelog() throws Exception {
        try (Connection c = open()) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            db.setDefaultSchemaName(SCHEMA);
            new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), db).update("");
        }
    }

    private boolean indexExists(String name) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_indexes "
                 + "WHERE schemaname = '" + SCHEMA + "' AND indexname = '" + name + "'")) {
            rs.next(); return rs.getInt(1) > 0;
        }
    }

    private String explain(String sql) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (Connection c = open(); Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) { plan.append(rs.getString(1)).append('\n'); }
        }
        return plan.toString();
    }

    /**
     * Насыпка под планировщик: 20000 назначенных (feed_position уникальна —
     * обратный индекс uq требует distinct) + 2000 неназначенных. Пропорция
     * (~9% NULL) — здоровый прод: почти всё назначено, хвост ждёт джоба.
     */
    private void seed() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            StringBuilder assigned = new StringBuilder();
            for (int i = 0; i < 20000; i++) {
                if (i > 0) assigned.append(',');
                assigned.append("('").append(UUID.randomUUID()).append("','ops23.e',1,now(),")
                    .append(30001 + i).append(')');
            }
            s.execute("INSERT INTO events (id, type, version, occurred_at, feed_position) VALUES " + assigned);
            StringBuilder unassigned = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                if (i > 0) unassigned.append(',');
                unassigned.append("('").append(UUID.randomUUID()).append("','ops23.e',1,now(),NULL)");
            }
            s.execute("INSERT INTO events (id, type, version, occurred_at, feed_position) VALUES " + unassigned);
            s.execute("ANALYZE events");
        }
    }

    @Test
    void migration_createsNullIndexAndKeepsReverseIndex() throws Exception {
        runMasterChangelog();
        assertThat(indexExists("idx_events_unassigned"))
            .as("idx_events_unassigned created by 20260928-116").isTrue();
        assertThat(indexExists("uq_events__feed_position"))
            .as("reverse-predicate uq_events__feed_position still coexists").isTrue();
    }

    @Test
    void countQuery_usesIndexNoSeqScan() throws Exception {
        runMasterChangelog();
        seed();
        // Дословная форма publishBacklogMetrics (FeedPositionAssigner:146):
        // SELECT COUNT(*) FROM events WHERE feed_position IS NULL.
        String plan = explain("SELECT COUNT(*) FROM events WHERE feed_position IS NULL");
        assertThat(plan).as("backlog COUNT uses the new partial index:\n" + plan)
            .contains("idx_events_unassigned");
        assertThat(plan).as("backlog COUNT has no Seq Scan:\n" + plan)
            .doesNotContain("Seq Scan");
        // Форма eligible-select (H2-ветка прод-кода дословно; PG-ветка добавляет
        // только xmin/commit-timestamp предикаты, IS NULL-часть та же).
        String eligible = explain(
            "SELECT sequence FROM events WHERE feed_position IS NULL ORDER BY sequence LIMIT 500");
        assertThat(eligible).as("eligible SELECT has no Seq Scan:\n" + eligible)
            .doesNotContain("Seq Scan");
    }

    @Test
    void countQuery_withoutIndex_seqScan() throws Exception {
        runMasterChangelog();
        seed();
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("DROP INDEX " + SCHEMA + ".idx_events_unassigned");
            s.execute("ANALYZE events");
        }
        // RED-направление: без индекса тот же запрос — полный скан.
        String plan = explain("SELECT COUNT(*) FROM events WHERE feed_position IS NULL");
        assertThat(plan).as("backlog COUNT without the index falls back to Seq Scan:\n" + plan)
            .contains("Seq Scan");
    }
}
