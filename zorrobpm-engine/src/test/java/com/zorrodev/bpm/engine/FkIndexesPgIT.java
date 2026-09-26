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
 * WO-AUDIT-2: changeset 20260911-104 must create {@code idx_variables_process_instance_scope}
 * and {@code idx_tokens_parent_id} on real PostgreSQL, and the profiled queries must use
 * them (no Seq Scan). Own schema, never touches shared public (same isolation idiom as
 * ElementArtifactBindingIndexPgIT).
 *
 * <p>NOT created on purpose (verified by {@code \d} on a fully migrated PG, see report):
 * api_key_grant(api_key_id) — duplicate of PK (api_key_id, process_id) left prefix;
 * process_member.process_definition_id — no such column exists at all.
 */
@Tag("pg")
class FkIndexesPgIT {

    private static final String SCHEMA = "audit2_fk_index_pg_it";

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

    private void seed() throws Exception {
        String pd = UUID.randomUUID().toString();
        StringBuilder vars = new StringBuilder();
        String[] pis = new String[5];
        String[] scopes = new String[5];
        for (int i = 0; i < 5; i++) { pis[i] = UUID.randomUUID().toString(); scopes[i] = UUID.randomUUID().toString(); }
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO process_definitions (id, created_at) VALUES ('" + pd + "', now())");
            for (String pi : pis) {
                s.execute("INSERT INTO process_instances (id, process_definition_id, started_at, cancelled) VALUES ('"
                    + pi + "', '" + pd + "', now(), false)");
            }
            for (int i = 0; i < 5; i++) {
                for (int v = 0; v < 400; v++) {
                    vars.append("('").append(UUID.randomUUID()).append("','").append(pis[i])
                        .append("','var").append(v).append("','v','string','").append(scopes[i]).append("'),");
                }
            }
            vars.setLength(vars.length() - 1);
            s.execute("INSERT INTO variables (id, process_instance_id, name, text_value, type, scope_id) VALUES " + vars);
            StringBuilder toks = new StringBuilder();
            String[] parents = new String[200];
            for (int i = 0; i < 200; i++) { parents[i] = UUID.randomUUID().toString(); }
            for (String p : parents) { toks.append("('").append(p).append("',NULL),"); }
            for (int i = 0; i < 800; i++) {
                toks.append("('").append(UUID.randomUUID()).append("','").append(parents[i % 200]).append("'),");
            }
            toks.setLength(toks.length() - 1);
            s.execute("INSERT INTO tokens (id, parent_id) VALUES " + toks);
            s.execute("ANALYZE variables");
            s.execute("ANALYZE tokens");
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

    @Test
    void changeset104_createsBothFkIndexes() throws Exception {
        runMasterChangelog();
        assertThat(indexExists("idx_variables_process_instance_scope"))
            .as("idx_variables_process_instance_scope created by 20260911-104").isTrue();
        assertThat(indexExists("idx_tokens_parent_id"))
            .as("idx_tokens_parent_id created by 20260911-104").isTrue();
    }

    @Test
    void profiledQueries_useIndexNoSeqScan() throws Exception {
        runMasterChangelog();
        seed();
        String pi, scope;
        try (Connection c = open(); Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery(
                 "SELECT process_instance_id, scope_id FROM variables LIMIT 1")) {
            rs.next(); pi = rs.getString(1); scope = rs.getString(2);
        }
        String parent;
        try (Connection c = open(); Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery(
                 "SELECT parent_id FROM tokens WHERE parent_id IS NOT NULL LIMIT 1")) {
            rs.next(); parent = rs.getString(1);
        }
        String varPlan = explain("DELETE FROM variables WHERE process_instance_id = '"
            + pi + "' AND scope_id = '" + scope + "'");
        assertThat(varPlan).as("variables delete path uses an index").doesNotContain("Seq Scan");
        String tokPlan = explain("SELECT 1 FROM tokens WHERE parent_id = '" + parent + "'");
        assertThat(tokPlan).as("tokens FK-check path uses an index").doesNotContain("Seq Scan");
    }
}
