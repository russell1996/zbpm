package com.zorrodev.bpm.engine;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-OPS-7: precondition migration 076 (drop service_account) must use onFail: CONTINUE,
 * not HALT — HALT stops the whole changelog and kills application startup (prod incident
 * 2026-08-21), CONTINUE skips the changeset without marking it executed and lets the rest
 * of the changelog (and the app) proceed.
 *
 * Isolation: every test runs against its OWN PostgreSQL schema ({@link #SCHEMA}), created
 * and dropped per test. The shared `public` schema (populated once by the Spring context
 * for all PgIT classes in the suite) is NEVER touched — the previous revision dropped all
 * public tables in cleanup and broke eight other classes downstream (P-59).
 */
@Tag("pg")
class MigrationPreconditionHaltPgIT {

    private static final String SCHEMA = "ops7_pg_it";
    private static final String CS_076 = "20260820-076-drop-service-account";
    private static final String CS_077 = "20260820-077-drop-api-key-owner-unique";

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

    /** Runs the REAL production master changelog into the test's own schema. */
    private void runMasterChangelog() throws Exception {
        try (Connection c = open()) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            db.setDefaultSchemaName(SCHEMA);
            new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), db).update("");
        }
    }

    /** Recreates the legacy service_account tables (original 20260711-049 shape) with one dead row. */
    private void seedLegacyServiceAccountWithDeadRow() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE service_account (id UUID PRIMARY KEY, process_id UUID, name VARCHAR(255), "
                + "key_hash VARCHAR(512), prefix VARCHAR(32), created_at TIMESTAMP)");
            s.execute("CREATE TABLE service_account_permission (service_account_id UUID NOT NULL, "
                + "permission VARCHAR(64) NOT NULL, PRIMARY KEY (service_account_id, permission))");
            s.execute("INSERT INTO service_account VALUES ('00000000-0000-0000-0000-000000000001', "
                + "'00000000-0000-0000-0000-000000000002', 'dead-account', 'hash', 'dead', now())");
        }
    }

    private void deleteChangelogRows(String... ids) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            for (String id : ids) {
                s.execute("DELETE FROM databasechangelog WHERE id = '" + id + "'");
            }
        }
    }

    /**
     * Undoes the physical effect of changeset 077 inside the own schema so that a re-run
     * of the master changelog re-executes 077 from scratch (as it would on a DB where
     * 077 had never completed): restore the pre-077 unique constraint and drop the index.
     */
    private void revertEffectOf077() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("DROP INDEX IF EXISTS idx_api_key_owner_user_id");
            // Defensive dedupe so UNIQUE(owner_user_id) can be recreated regardless of seed data:
            // everything here lives in the disposable own schema.
            s.execute("DELETE FROM api_key a USING api_key b "
                + "WHERE a.owner_user_id IS NOT NULL AND a.owner_user_id = b.owner_user_id AND a.ctid > b.ctid");
            s.execute("ALTER TABLE api_key ADD CONSTRAINT uq_api_key_owner UNIQUE (owner_user_id)");
        }
    }

    private boolean tableExists(String name) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM information_schema.tables "
                 + "WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + name + "'")) {
            rs.next(); return rs.getInt(1) > 0;
        }
    }

    private boolean indexExists(String name) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_indexes "
                 + "WHERE schemaname = '" + SCHEMA + "' AND indexname = '" + name + "'")) {
            rs.next(); return rs.getInt(1) > 0;
        }
    }

    private boolean changelogHas(String id) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM databasechangelog WHERE id = '" + id + "'")) {
            rs.next(); return rs.getInt(1) > 0;
        }
    }

    private int rowCount(String table) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next(); return rs.getInt(1);
        }
    }

    /**
     * POF RED: with onFail: HALT (the pre-fix production state), a non-empty service_account
     * makes Liquibase throw PreconditionFailedException — on prod this aborted the whole
     * changelog at context startup and the app never started.
     * The prod YAML file is loaded and mutated in memory (CONTINUE→HALT) because the classpath
     * serves the compiled copy; CTO note: this block is file-driven rather than wired through
     * Spring Boot startup, but the failure it reproduces is Liquibase's real HALT behaviour.
     */
    @Test
    void pof_halt_nonEmptyTable_throws() throws Exception {
        seedLegacyServiceAccountWithDeadRow();
        Path tempDir = Files.createTempDirectory("liq-halt-");
        Path sourcePath = Path.of(System.getProperty("user.dir"),
            "src", "main", "resources", "db", "changelog", "changesets", CS_076 + ".yml");
        String content = Files.readString(sourcePath).replace("onFail: CONTINUE", "onFail: HALT");
        Files.writeString(tempDir.resolve(CS_076 + ".yml"), content);
        try {
            assertThatThrownBy(() -> {
                try (Connection c = open()) {
                    var db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
                    new Liquibase(CS_076 + ".yml", new FileSystemResourceAccessor(tempDir.toFile()), db).update("");
                }
            }).isInstanceOf(Exception.class)
              .hasStackTraceContaining("PreconditionFailedException")
              .hasStackTraceContaining("Table service_account is not empty");
        } finally {
            Files.deleteIfExists(tempDir.resolve(CS_076 + ".yml"));
            Files.deleteIfExists(tempDir);
        }
        assertThat(tableExists("service_account")).as("HALT must leave the data untouched").isTrue();
    }

    /**
     * Criterion 2 (WO): non-empty service_account must NOT stop subsequent changesets —
     * in ONE pass 076 is skipped AND 077 (and the rest) applies. Scenario per CTO HOLD:
     * delete both 076 and 077 journal rows, revert 077's physical effect, keep a dead row
     * in service_account, run; then assert the 077 index exists, 077 is journalled and
     * 076 is absent — i.e. the run continued past the skip.
     */
    @Test
    void fix_continue_nonEmptyTable_appStarts() throws Exception {
        runMasterChangelog();                       // baseline: everything applied, empty tables dropped
        assertThat(changelogHas(CS_076)).isTrue();
        seedLegacyServiceAccountWithDeadRow();      // legacy leftovers appear again
        deleteChangelogRows(CS_076, CS_077);        // both pending again
        revertEffectOf077();                        // pre-077 state so 077 can re-execute

        runMasterChangelog();                       // THE deploy under test: one pass over the changelog

        assertThat(tableExists("service_account")).as("076 skipped - legacy data survives").isTrue();
        assertThat(changelogHas(CS_076)).as("skipped 076 is NOT journalled").isFalse();
        assertThat(changelogHas(CS_077)).as("077 applied in the SAME run").isTrue();
        assertThat(indexExists("idx_api_key_owner_user_id")).as("effect of 077 visible").isTrue();
        assertThat(rowCount("service_account")).as("data preserved").isEqualTo(1);
    }

    /** Criterion 3 (WO): an EMPTY service_account still gets dropped — no regression the other way. */
    @Test
    void emptyTable_dropExecutes() throws Exception {
        runMasterChangelog();                       // 049 creates empty tables, 076 drops them
        assertThat(changelogHas(CS_076)).as("drop executed and journalled").isTrue();
        assertThat(tableExists("service_account")).as("empty table dropped").isFalse();
        assertThat(tableExists("service_account_permission")).as("permission table dropped").isFalse();
    }

    /**
     * Criterion 4 (WO), verbatim: the changeset is NOT marked executed when skipped; a second
     * run on the still-non-empty table skips it AGAIN; a run after the table has been cleared
     * performs the drop.
     */
    @Test
    void criterion4_skipNotMarked_secondRunStillSkips_dropAfterClear() throws Exception {
        // Phase A: skip happens and is not marked executed
        runMasterChangelog();
        seedLegacyServiceAccountWithDeadRow();
        deleteChangelogRows(CS_076);
        runMasterChangelog();
        assertThat(changelogHas(CS_076)).as("skip must NOT mark the changeset executed").isFalse();
        assertThat(rowCount("service_account")).isEqualTo(1);

        // Phase B: second run, table STILL non-empty -> skips again
        runMasterChangelog();
        assertThat(changelogHas(CS_076)).as("second run on non-empty table skips again").isFalse();
        assertThat(rowCount("service_account")).as("still nothing destroyed").isEqualTo(1);

        // Phase C: after clearing the table, the drop executes
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM service_account");
        }
        runMasterChangelog();
        assertThat(changelogHas(CS_076)).as("now executed and journalled").isTrue();
        assertThat(tableExists("service_account")).as("table dropped once empty").isFalse();
    }
}
