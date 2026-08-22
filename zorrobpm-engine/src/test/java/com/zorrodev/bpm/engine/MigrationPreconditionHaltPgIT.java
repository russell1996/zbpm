package com.zorrodev.bpm.engine;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("pg")
class MigrationPreconditionHaltPgIT {

    private static String jdbcUrl() {
        String h = System.getenv("PG_HOST"); if (h == null) h = System.getProperty("PG_HOST", "127.0.0.1");
        String p = System.getenv("PG_PORT"); if (p == null) p = System.getProperty("PG_PORT", "55432");
        String d = System.getenv("PG_DB"); if (d == null) d = System.getProperty("PG_DB", "zorrobpm-db");
        String u = System.getenv("PG_USER"); if (u == null) u = System.getProperty("PG_USER", "zorrodev");
        String w = System.getenv("PG_PASSWORD"); if (w == null) w = System.getProperty("PG_PASSWORD", "zorrodev");
        return "jdbc:postgresql://" + h + ":" + p + "/" + d + "?sslmode=disable&user=" + u + "&password=" + w;
    }

    private void fullCleanup() {
        try (Connection c = DriverManager.getConnection(jdbcUrl()); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'");
            var tables = new java.util.ArrayList<String>();
            while (rs.next()) { tables.add(rs.getString(1)); }
            rs.close();
            for (String t : tables) { s.execute("DROP TABLE IF EXISTS " + t + " CASCADE"); }
            try { s.execute("DELETE FROM databasechangelog"); } catch (Exception ignored) {}
            try { s.execute("DELETE FROM databasechangeloglock"); } catch (Exception ignored) {}
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void runFullChangelog() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl())) {
            var db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), db).update("");
        }
    }

    private boolean tableExists(String name) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl()); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_name = '" + name + "'")) {
            rs.next(); return rs.getInt(1) > 0;
        }
    }

    private boolean isMarkedExecuted() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl()); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM databasechangelog WHERE id = '20260820-076-drop-service-account'")) {
            rs.next(); return rs.getInt(1) > 0;
        }
    }

    private void insertDeadRow() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl()); Statement s = c.createStatement()) {
            s.execute("INSERT INTO service_account (id, process_id, name, key_hash, prefix, created_at) "
                + "VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', "
                + "'dead-account', 'hash', 'dead', now())");
        }
    }

    private void deleteChangeset076FromLog() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl()); Statement s = c.createStatement()) {
            s.execute("DELETE FROM databasechangelog WHERE id = '20260820-076-drop-service-account'");
        }
    }

    @Test
    void pof_halt_nonEmptyTable_throws() throws Exception {
        fullCleanup();
        try (Connection c = DriverManager.getConnection(jdbcUrl()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE service_account (id UUID PRIMARY KEY, name VARCHAR(255))");
            s.execute("INSERT INTO service_account VALUES ('00000000-0000-0000-0000-000000000001', 'dead')");
        }
        // Write HALT version to a temp directory and use FileSystemResourceAccessor
        // (ClassLoaderResourceAccessor reads from compiled classpath, ignores source edits)
        var tempDir = java.nio.file.Files.createTempDirectory("liq-halt-");
        var tempFile = tempDir.resolve("20260820-076-drop-service-account.yml");
        var sourcePath = java.nio.file.Path.of(System.getProperty("user.dir"),
            "src", "main", "resources", "db", "changelog", "changesets", "20260820-076-drop-service-account.yml");
        String content = java.nio.file.Files.readString(sourcePath);
        java.nio.file.Files.writeString(tempFile, content.replace("onFail: CONTINUE", "onFail: HALT"));
        try {
            assertThatThrownBy(() -> {
                try (Connection c = DriverManager.getConnection(jdbcUrl())) {
                    var db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
                    new Liquibase("20260820-076-drop-service-account.yml",
                        new FileSystemResourceAccessor(tempDir.toFile()), db).update("");
                }
            }).isInstanceOf(Exception.class)
              .hasStackTraceContaining("PreconditionFailedException")
              .hasStackTraceContaining("Table service_account is not empty");
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
            java.nio.file.Files.deleteIfExists(tempDir);
        }
        fullCleanup();
    }

    @Test
    void fix_continue_nonEmptyTable_appStarts() throws Exception {
        fullCleanup();
        runFullChangelog();
        assertThat(tableExists("service_account")).isFalse();
        try (Connection c = DriverManager.getConnection(jdbcUrl()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE service_account (id UUID PRIMARY KEY, process_id UUID, name VARCHAR(255), "
                + "key_hash VARCHAR(512), prefix VARCHAR(32), created_at TIMESTAMP)");
        }
        insertDeadRow();
        deleteChangeset076FromLog();
        runFullChangelog();
        assertThat(tableExists("service_account")).as("076 skipped - table survives").isTrue();
        fullCleanup();
    }

    @Test
    void emptyTable_dropExecutes() throws Exception {
        fullCleanup();
        runFullChangelog();
        assertThat(tableExists("service_account")).as("empty table dropped").isFalse();
        assertThat(tableExists("service_account_permission")).as("empty permission table dropped").isFalse();
        fullCleanup();
    }

    @Test
    void skippedChangeset_notMarkedExecuted() throws Exception {
        fullCleanup();
        runFullChangelog();
        try (Connection c = DriverManager.getConnection(jdbcUrl()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE service_account (id UUID PRIMARY KEY, process_id UUID, name VARCHAR(255), "
                + "key_hash VARCHAR(512), prefix VARCHAR(32), created_at TIMESTAMP)");
        }
        insertDeadRow();
        deleteChangeset076FromLog();
        runFullChangelog();
        assertThat(isMarkedExecuted()).as("skipped changeset must NOT be in DATABASECHANGELOG").isFalse();
        assertThat(tableExists("service_account")).isTrue();
        fullCleanup();
    }
}