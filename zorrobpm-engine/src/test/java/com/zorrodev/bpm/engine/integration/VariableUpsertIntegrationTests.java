package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-31 CR-1: H2-сторона upsert'а на живой H2 (тестовый профиль).
 *
 * <p>PG-путь ({@code ON CONFLICT}) доказан {@code VariableUpsertRacePgIT} на
 * реальном PG; здесь доказывается, что H2-путь (guarded UPDATE + INSERT +
 * retry, т.к. в H2 нет грамматики ON CONFLICT) выполняется на настоящей H2:
 * повторная запись той же переменной даёт ровно одну строку с новым
 * значением — и для root-scope, и для scoped. Заодно доказывает накат
 * миграции 108 на H2 (контекст стартует с полным Liquibase-чейном).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class VariableUpsertIntegrationTests {

    @Autowired private com.zorrodev.bpm.engine.service.db.VariableDbOperations variableDb;
    @Autowired private JdbcTemplate jdbc;

    private UUID sharedPdId;
    private UUID pi;

    @BeforeEach
    void seed() {
        sharedPdId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_definitions (id, code, version, name, sha256, created_at) " +
            "VALUES (?, 'upsert-h2', 1, 'Upsert H2', ?, ?)",
            sharedPdId, UUID.randomUUID().toString(), Timestamp.from(Instant.now()));
        pi = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO process_instances (id, process_definition_id, started_at, completed_at, cancelled) " +
            "VALUES (?, ?, ?, NULL, false)",
            pi, sharedPdId, Timestamp.from(Instant.now()));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM variables WHERE process_instance_id = ?", pi);
        jdbc.update("DELETE FROM process_instances WHERE id = ?", pi);
        jdbc.update("DELETE FROM process_definitions WHERE id = ?", sharedPdId);
    }

    private static ProcessVariable pv(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(ProcessVariableType.STRING);
        return v;
    }

    private int countRows(String name) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM variables WHERE process_instance_id = ? AND name = ?",
            Integer.class, pi, name);
        return n == null ? 0 : n;
    }

    @Test
    void repeatedRootWrite_leavesExactlyOneRowWithLatestValue() {
        variableDb.setVariables(pi, List.of(pv("k", "first")));
        variableDb.setVariables(pi, List.of(pv("k", "second")));

        assertThat(countRows("k")).isEqualTo(1);
        assertThat(variableDb.getVariables(pi)).hasSize(1);
        assertThat(variableDb.getVariables(pi).get(0).getValue()).isEqualTo("second");
    }

    @Test
    void repeatedScopedWrite_leavesExactlyOneRowWithLatestValue() {
        UUID scope = UUID.randomUUID();
        variableDb.setVariables(pi, scope, List.of(pv("s", "one")));
        variableDb.setVariables(pi, scope, List.of(pv("s", "two")));

        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id = ?",
            Integer.class, pi, "s", scope);
        assertThat(n).isEqualTo(1);
        assertThat(variableDb.getVariables(pi, scope)).hasSize(1);
        assertThat(variableDb.getVariables(pi, scope).get(0).getValue()).isEqualTo("two");
    }

    private String textOf(String name) {
        return jdbc.queryForObject(
            "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ?",
            String.class, pi, name);
    }

    // WO-REL-41 (B-8, п.1): H2-сторона атомарного append на живой H2 —
    // доказывает, что APPEND_H2_UPDATE реально выполняется (TRIM/LIKE/
    // SUBSTRING-синтаксис), а не только мокается.

    @Test
    void appendJsonElement_createsListThenExtendsIt() {
        variableDb.appendJsonElement(pi, "agg", "\"a\"");
        assertThat(countRows("agg")).isEqualTo(1);
        assertThat(textOf("agg")).isEqualTo("[\"a\"]");

        variableDb.appendJsonElement(pi, "agg", "1");
        assertThat(countRows("agg")).isEqualTo(1);
        assertThat(textOf("agg")).isEqualTo("[\"a\",1]");
    }

    @Test
    void appendJsonElement_replacesScalarWithFreshList() {
        variableDb.setVariables(pi, List.of(pv("k", "scalar")));
        variableDb.appendJsonElement(pi, "k", "\"x\"");

        assertThat(countRows("k")).isEqualTo(1);
        assertThat(textOf("k")).isEqualTo("[\"x\"]");
    }

    @Test
    void appendJsonElement_emptyArray_extendsWithoutCorruption() {
        // Verifier WO-REL-41: вырожденный '[]' обязан дать '["a"]', не '[,"a"]'.
        variableDb.setVariables(pi, List.of(pv("e", "[]")));
        variableDb.appendJsonElement(pi, "e", "\"a\"");

        assertThat(countRows("e")).isEqualTo(1);
        assertThat(textOf("e")).isEqualTo("[\"a\"]");
    }

    @Test
    void appendJsonElement_nestsArrayElementAsSingle() {
        // WO-REL-41: list.add-семантика — массив-элемент вкладывается, не конкатенируется.
        variableDb.appendJsonElement(pi, "n", "[1,2]");

        assertThat(textOf("n")).isEqualTo("[[1,2]]");
    }

    @Test
    void getVariableTextValue_readsOneRow() {
        variableDb.setVariables(pi, List.of(pv("batch", "b-1")));

        assertThat(variableDb.getVariableTextValue(pi, "batch")).hasValue("b-1");
        assertThat(variableDb.getVariableTextValue(pi, "missing")).isEmpty();
    }
}
