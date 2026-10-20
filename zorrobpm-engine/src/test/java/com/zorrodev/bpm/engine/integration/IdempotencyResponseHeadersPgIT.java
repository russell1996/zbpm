package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.IdempotencyRecord;
import com.zorrodev.bpm.engine.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IdempotencyRecordRepository repository;

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
