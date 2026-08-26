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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-59 #4: changeset 20260715-062 failed to create the two element_artifact_binding indexes
 * because of a typo'd 'index_name:' key (Liquibase expects 'indexName:'). This PgIT runs the REAL
 * production master changelog into its own schema and asserts that the fix changeset
 * 20260826-079 actually creates idx_binding_pd_element and idx_binding_artifact on PostgreSQL.
 * Mirrors MigrationPreconditionHaltPgIT's isolation (own schema, never touches shared public).
 */
@Tag("pg")
class ElementArtifactBindingIndexPgIT {

    private static final String SCHEMA = "sec59_index_pg_it";

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

    @Test
    void fixChangeset_createsBothBindingIndexes() throws Exception {
        runMasterChangelog();
        assertThat(indexExists("idx_binding_pd_element"))
            .as("idx_binding_pd_element created by WO-SEC-59 #4").isTrue();
        assertThat(indexExists("idx_binding_artifact"))
            .as("idx_binding_artifact created by WO-SEC-59 #4").isTrue();
    }
}
