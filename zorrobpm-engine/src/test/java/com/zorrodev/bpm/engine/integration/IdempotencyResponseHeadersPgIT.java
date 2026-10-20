package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.IdempotencyRecord;
import com.zorrodev.bpm.engine.repository.IdempotencyRecordRepository;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-14 (F-16): Liquibase changeset {@code 20261020-122} — the
 * {@code idempotency_record.response_headers} column on REAL PostgreSQL.
 *
 * <p>H2-green is not prod-green (V11): this class runs against postgres:16,
 * where the Spring context applies every changeset (including 122) and then
 * asserts the resulting schema state and a real round-trip of the value.
 *
 * <p>What is pinned:
 * <ul>
 *   <li>the column exists and is NULLABLE (records written before the
 *       migration must survive it unchanged);</li>
 *   <li>the entity round-trips the JSON blob of headers on PG;</li>
 *   <li>a record written without headers keeps NULL (legacy shape).</li>
 * </ul>
 */
class IdempotencyResponseHeadersPgIT extends PostgresIT {

    /**
     * Своя схема для проверки ОБРАТНОГО действия changeset'а: общий public-schema
     * трогать нельзя (там живут чужие тесты), аLiquibase-откат требует
     * доступа к мастер-чанжлогу целиком.
     */
    private static final String SCHEMA = "audit14_idem_headers_pg_it";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IdempotencyRecordRepository repository;

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

    private void rollbackLast(int count) throws Exception {
        try (Connection c = open()) {
            liquibaseOn(c).rollback(count, new Contexts(), new LabelExpression());
        }
    }

    private boolean columnExists() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM information_schema.columns "
                 + "WHERE table_schema = '" + SCHEMA + "' AND table_name = 'idempotency_record' "
                 + "AND column_name = 'response_headers'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    /**
     * WO-AUDIT-14 (F-16): у changeset'а 20261020-122 должно быть РАБОТАЮЩЕЕ
     * обратное действие.
     *
     * <p>История этого места: комментарий к колонке сначала жил в отдельном
     * changeset'е с {@code RawSQLChange} — откат, проходящий через него, падал
     * с {@code RollbackImpossibleException} (это задело чужой откат в
     * {@code VariablePresetsPgIT}). Комментарий перенесли внутрь changeset'а с
     * колонкой, но «откат changeset'а = dropColumn» осталось ПРЕДПОЛОЖЕНИЕМ —
     * его никто не проверял. Здесь оно и проверяется: мастер-чанжлог накатывается
     * на СВОЮ схему, откатывается последний changeset, и колонка обязана
     * исчезнуть. Если обратное действие не сработает — тест упадёт (не тихо).
     */
    @Test
    void migration122_rollbackDropsTheColumn() throws Exception {
        runMaster();
        assertThat(columnExists())
            .as("накат мастер-чанжлога добавил колонку")
            .isTrue();

        rollbackLast(1);

        assertThat(columnExists())
            .as("обратное действие changeset'а 122 обязано убрать колонку "
                + "(dropColumn), а не упасть и не оставить мусор")
            .isFalse();
    }

    @Test
    void migration122_addsNullableResponseHeadersColumn() {
        Integer cols = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'idempotency_record' AND column_name = 'response_headers'",
            Integer.class);
        assertThat(cols).as("changeset 20261020-122 must have added the column").isEqualTo(1);

        String dataType = jdbcTemplate.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'idempotency_record' AND column_name = 'response_headers'",
            String.class);
        assertThat(dataType).as("the blob is a JSON text, not a structured type").isEqualTo("text");

        String isNullable = jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'idempotency_record' AND column_name = 'response_headers'",
            String.class);
        assertThat(isNullable)
            .as("records created before the migration must survive it unchanged")
            .isEqualTo("YES");
    }

    @Test
    void migration122_commentOnColumn_isInRussian() {
        // Правило проекта: новая колонка несёт COMMENT на русском. Проверяем
        // САМ артефакт (текст комментария в каталоге PostgreSQL), а не наличие
        // вызова в changeset'е — комментарий мог не примениться.
        String comment = jdbcTemplate.queryForObject(
            "SELECT col_description('idempotency_record'::regclass, attnum) "
                + "FROM pg_attribute "
                + "WHERE attrelid = 'idempotency_record'::regclass AND attname = 'response_headers'",
            String.class);
        assertThat(comment)
            .as("the new column must carry a comment (project rule: Russian)")
            .isNotNull()
            .isNotBlank();
        assertThat(comment)
            .as("the comment must explain the column for the DBA reading the schema")
            .contains("идемпотентного replay");
    }

    @Test
    void responseHeaders_roundTripOnPostgres() {
        String headers = "{\"Location\":\"/process-instances/"
            + UUID.randomUUID() + "\",\"ETag\":\"\\\"v1\\\"\"}";

        IdempotencyRecord saved = repository.save(record(headers));

        IdempotencyRecord reloaded = repository.findByIdemKeyAndEndpointAndActorId(
            saved.getIdemKey(), saved.getEndpoint(), saved.getActorId()).orElseThrow();
        assertThat(reloaded.getResponseHeaders())
            .as("the headers must survive the round-trip byte-for-byte")
            .isEqualTo(headers);
    }

    @Test
    void recordWithoutHeaders_keepsNull() {
        IdempotencyRecord saved = repository.save(record(null));

        IdempotencyRecord reloaded = repository.findByIdemKeyAndEndpointAndActorId(
            saved.getIdemKey(), saved.getEndpoint(), saved.getActorId()).orElseThrow();
        assertThat(reloaded.getResponseHeaders())
            .as("no headers -> no value (not an empty JSON object)")
            .isNull();
    }

    private IdempotencyRecord record(String headers) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdemKey("audit14-" + UUID.randomUUID());
        record.setEndpoint("/process-instances");
        record.setActorId("actor-" + UUID.randomUUID());
        record.setCredentialHash("a".repeat(64));
        record.setRequestHash("b".repeat(64));
        record.setResponseStatus(201);
        record.setResponseBody("{}");
        record.setResponseContentType("application/json");
        record.setResponseHeaders(headers);
        record.setCreatedAt(Instant.now());
        return record;
    }
}
