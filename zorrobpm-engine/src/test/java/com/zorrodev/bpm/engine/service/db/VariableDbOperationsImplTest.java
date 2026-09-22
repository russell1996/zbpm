package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VariableDbOperationsImplTest {

    @Mock private VariableRepository variableRepository;
    // WO-C8-29: запись изменений для conditionalFilter (мок — существующие тесты
    // трекинг не проверяют; поведение трекера покрыто отдельно).
    @Mock private com.zorrodev.bpm.engine.handler.ExecutionContext executionContext;
    // WO-REL-31 CR-1: upsert идёт через JdbcTemplate + product-detect.
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;
    // WO-REL-31 CR-1 regression fix: JDBC-upsert evicts the managed copy after
    // "update" so same-tx JPA re-reads see the new value (mock — detach is no-op).
    @Mock private jakarta.persistence.EntityManager entityManager;
    // WO-ENG-16: audit-след изменений (мок — поведение writer'а покрыто IT).
    @Mock private VariableHistoryWriter historyWriter;
    @Mock private com.zorrodev.bpm.engine.repository.VariableHistoryRepository historyRepository;
    @InjectMocks private VariableDbOperationsImpl db;

    private void givenProduct(String product) throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(product);
    }

    private static ProcessVariable pv(String name, String value) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(value);
        pv.setType(ProcessVariableType.STRING);
        return pv;
    }

    @Test
    void getVariables_root_returnsMapped() {
        UUID pi = UUID.randomUUID();
        ProcessVariableEntity e = new ProcessVariableEntity(); e.setId(UUID.randomUUID()); e.setProcessInstanceId(pi); e.setName("k"); e.setType(ProcessVariableType.STRING); e.setTextValue("v");
        when(variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi)).thenReturn(List.of(e));
        List<ProcessVariable> result = db.getVariables(pi);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("k");
        assertThat(result.get(0).getValue()).isEqualTo("v");
    }

    @Test
    void getVariables_withScope_mergesRootAndScope() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        ProcessVariableEntity root = new ProcessVariableEntity(); root.setId(UUID.randomUUID()); root.setProcessInstanceId(pi); root.setName("k"); root.setType(ProcessVariableType.STRING); root.setTextValue("root");
        ProcessVariableEntity scoped = new ProcessVariableEntity(); scoped.setId(UUID.randomUUID()); scoped.setProcessInstanceId(pi); scoped.setScopeId(scope); scoped.setName("k"); scoped.setType(ProcessVariableType.STRING); scoped.setTextValue("scoped");
        when(variableRepository.findRootAndScoped(pi, scope)).thenReturn(List.of(root, scoped));
        List<ProcessVariable> result = db.getVariables(pi, scope);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("scoped");
    }

    @Test
    void getVariablesByNames_mapsOnlyNamedRows() {
        UUID pi = UUID.randomUUID();
        ProcessVariableEntity e = new ProcessVariableEntity(); e.setId(UUID.randomUUID()); e.setProcessInstanceId(pi); e.setName("items"); e.setType(ProcessVariableType.JSON); e.setTextValue("[1,2]");
        when(variableRepository.findByProcessInstanceIdAndScopeIdIsNullAndNameIn(eq(pi), org.mockito.ArgumentMatchers.argThat(names -> names != null && names.contains("items"))))
            .thenReturn(List.of(e));
        List<ProcessVariable> result = db.getVariablesByNames(pi, java.util.Set.of("items", "k"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("items");
        assertThat(result.get(0).getValue()).isEqualTo("[1,2]");
    }

    @Test
    void getVariablesByNames_emptyNames_noQuery() {
        UUID pi = UUID.randomUUID();
        assertThat(db.getVariablesByNames(pi, java.util.Set.of())).isEmpty();
        // WO-PERF-9: пустой набор → запроса нет вообще (IN () невалиден, да и не нужен).
        verify(variableRepository, never()).findByProcessInstanceIdAndScopeIdIsNullAndNameIn(any(), any());
        verify(variableRepository, never()).findByProcessInstanceIdAndScopeIdIsNull(any());
    }

    @Test
    void getScopedVariablesByNames_scopedWins() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        ProcessVariableEntity root = new ProcessVariableEntity(); root.setId(UUID.randomUUID()); root.setProcessInstanceId(pi); root.setName("item"); root.setType(ProcessVariableType.LONG); root.setTextValue("1");
        ProcessVariableEntity scoped = new ProcessVariableEntity(); scoped.setId(UUID.randomUUID()); scoped.setProcessInstanceId(pi); scoped.setScopeId(scope); scoped.setName("item"); scoped.setType(ProcessVariableType.LONG); scoped.setTextValue("2");
        when(variableRepository.findRootAndScopedByNames(eq(pi), eq(scope), any())).thenReturn(List.of(root, scoped));
        List<ProcessVariable> result = db.getScopedVariablesByNames(pi, scope, java.util.Set.of("item"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("2");
    }

    @Test
    void getScopedVariablesByNames_emptyNames_noQuery() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        assertThat(db.getScopedVariablesByNames(pi, scope, java.util.Set.of())).isEmpty();
        verify(variableRepository, never()).findRootAndScopedByNames(any(), any(), any());
        verify(variableRepository, never()).findRootAndScoped(any(), any());
    }

    @Test
    void setVariables_postgres_insert_recordsCreate() throws Exception {
        givenProduct("PostgreSQL");
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(contains("ON CONFLICT"), eq(Boolean.class), any(), any(), any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        db.setVariables(pi, scope, List.of(pv("k", "v")));
        verify(jdbcTemplate).queryForObject(contains("ON CONFLICT"), eq(Boolean.class), any(), any(), any(), any(), any(), any());
        verify(executionContext).recordVariableChange("k", "create");
        // WO-ENG-16: единая точка пишет историю тем же вызовом (источник CREATE).
        verify(historyWriter).record(eq(pi), eq(scope), any(ProcessVariable.class), eq("CREATE"));
        verify(variableRepository, never()).saveAll(any());
    }

    @Test
    void setVariables_postgres_conflict_recordsUpdate() throws Exception {
        givenProduct("PostgreSQL");
        UUID pi = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(contains("ON CONFLICT"), eq(Boolean.class), any(), any(), any(), any(), any(), any())).thenReturn(Boolean.FALSE);
        db.setVariables(pi, List.of(pv("k", "v")));
        verify(executionContext).recordVariableChange("k", "update");
        // WO-ENG-16: перезапись — источник UPDATE.
        verify(historyWriter).record(eq(pi), eq(null), any(ProcessVariable.class), eq("UPDATE"));
        verify(variableRepository, never()).saveAll(any());
    }

    @Test
    void setVariables_h2_updateHit_recordsUpdateWithoutInsert() throws Exception {
        givenProduct("H2");
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        when(jdbcTemplate.update(contains("UPDATE"), any(Object[].class))).thenReturn(1);
        db.setVariables(pi, scope, List.of(pv("k", "v")));
        verify(jdbcTemplate).update(contains("UPDATE"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT"), any(Object[].class));
        verify(executionContext).recordVariableChange("k", "update");
        verify(variableRepository, never()).saveAll(any());
    }

    @Test
    void setVariables_h2_updateMiss_insertsAndRecordsCreate() throws Exception {
        givenProduct("H2");
        UUID pi = UUID.randomUUID();
        when(jdbcTemplate.update(contains("UPDATE"), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.update(contains("INSERT"), any(Object[].class))).thenReturn(1);
        db.setVariables(pi, List.of(pv("k", "v")));
        verify(jdbcTemplate).update(contains("INSERT"), any(Object[].class));
        verify(executionContext).recordVariableChange("k", "create");
        verify(variableRepository, never()).saveAll(any());
    }

    @Test
    void setVariables_h2_insertRace_retriesUpdate() throws Exception {
        givenProduct("H2");
        UUID pi = UUID.randomUUID();
        when(jdbcTemplate.update(contains("UPDATE"), any(Object[].class))).thenReturn(0, 1);
        when(jdbcTemplate.update(contains("INSERT"), any(Object[].class)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("race"));
        db.setVariables(pi, List.of(pv("k", "v")));
        verify(jdbcTemplate).update(contains("INSERT"), any(Object[].class));
        verify(executionContext).recordVariableChange("k", "update");
        verify(variableRepository, never()).saveAll(any());
    }

    @Test
    void setVariables_h2_update_evictsManagedCopy() throws Exception {
        // WO-REL-31 CR-1 regression fix: after a JDBC "update" the managed copy
        // must be detached so same-tx JPA re-reads see the new value.
        givenProduct("H2");
        UUID pi = UUID.randomUUID(); UUID rowId = UUID.randomUUID();
        when(jdbcTemplate.update(contains("UPDATE"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForList(contains("SELECT"), eq(UUID.class), any(Object[].class)))
            .thenReturn(List.of(rowId));
        ProcessVariableEntity ref = new ProcessVariableEntity();
        when(entityManager.getReference(eq(ProcessVariableEntity.class), eq(rowId))).thenReturn(ref);
        db.setVariables(pi, List.of(pv("k", "v")));
        verify(entityManager).detach(ref);
    }

    @Test
    void setVariables_h2_insert_doesNotEvict() throws Exception {
        // Fresh rows have no managed copy — no detach on "create".
        givenProduct("H2");
        UUID pi = UUID.randomUUID();
        when(jdbcTemplate.update(contains("UPDATE"), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.update(contains("INSERT"), any(Object[].class))).thenReturn(1);
        db.setVariables(pi, List.of(pv("k", "v")));
        verify(entityManager, never()).detach(any());
    }

    @Test
    void deleteVariables_deletes() {
        UUID pi = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        db.deleteVariables(pi, scope);
        verify(variableRepository).deleteByProcessInstanceIdAndScopeId(pi, scope);
    }

    // WO-REL-41 (B-8): проводка новых методов — SQL-форма и kind-репорт.

    @Test
    void getVariableTextValue_mapsRow() {
        UUID pi = UUID.randomUUID();
        ProcessVariableEntity e = new ProcessVariableEntity();
        e.setTextValue("b-1");
        when(variableRepository.findByProcessInstanceIdAndNameAndScopeIdIsNull(pi, "_mi_batch_mi"))
            .thenReturn(java.util.Optional.of(e));

        assertThat(db.getVariableTextValue(pi, "_mi_batch_mi")).hasValue("b-1");
        verify(variableRepository, never()).findByProcessInstanceIdAndScopeIdIsNull(any());
    }

    @Test
    void getVariableTextValue_emptyWhenAbsent() {
        UUID pi = UUID.randomUUID();
        when(variableRepository.findByProcessInstanceIdAndNameAndScopeIdIsNull(pi, "_mi_batch_mi"))
            .thenReturn(java.util.Optional.empty());

        assertThat(db.getVariableTextValue(pi, "_mi_batch_mi")).isEmpty();
    }

    @Test
    void appendJsonElement_postgres_insert_recordsCreate() throws Exception {
        givenProduct("PostgreSQL");
        UUID pi = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(contains("jsonb"), eq(Boolean.class), any(), any(), any(), any(), any(), any()))
            .thenReturn(Boolean.TRUE);

        db.appendJsonElement(pi, "agg", "\"a\"");

        verify(jdbcTemplate).queryForObject(contains("jsonb"), eq(Boolean.class), any(), any(), any(), any(), any(), any());
        verify(executionContext).recordVariableChange("agg", "create");
        verify(variableRepository, never()).saveAll(any());
        verify(entityManager, never()).detach(any());
    }

    @Test
    void appendJsonElement_postgres_conflict_recordsUpdateAndEvicts() throws Exception {
        givenProduct("PostgreSQL");
        UUID pi = UUID.randomUUID(); UUID rowId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(contains("jsonb"), eq(Boolean.class), any(), any(), any(), any(), any(), any()))
            .thenReturn(Boolean.FALSE);
        when(jdbcTemplate.queryForList(contains("SELECT"), eq(UUID.class), any(Object[].class)))
            .thenReturn(List.of(rowId));
        ProcessVariableEntity ref = new ProcessVariableEntity();
        when(entityManager.getReference(eq(ProcessVariableEntity.class), eq(rowId))).thenReturn(ref);

        db.appendJsonElement(pi, "agg", "\"a\"");

        verify(executionContext).recordVariableChange("agg", "update");
        verify(entityManager).detach(ref);
    }

    @Test
    void appendJsonElement_h2_updateHit_recordsUpdateWithoutInsert() throws Exception {
        givenProduct("H2");
        UUID pi = UUID.randomUUID();
        when(jdbcTemplate.update(contains("SUBSTRING"), any(Object[].class))).thenReturn(1);

        db.appendJsonElement(pi, "agg", "\"a\"");

        verify(jdbcTemplate).update(contains("SUBSTRING"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT"), any(Object[].class));
        verify(executionContext).recordVariableChange("agg", "update");
    }

    @Test
    void appendJsonElement_h2_updateMiss_insertsAndRecordsCreate() throws Exception {
        givenProduct("H2");
        UUID pi = UUID.randomUUID();
        when(jdbcTemplate.update(contains("SUBSTRING"), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.update(contains("INSERT"), any(Object[].class))).thenReturn(1);

        db.appendJsonElement(pi, "agg", "\"a\"");

        verify(jdbcTemplate).update(contains("INSERT"), any(Object[].class));
        verify(executionContext).recordVariableChange("agg", "create");
        verify(entityManager, never()).detach(any());
    }

    @Test
    void appendJsonElement_h2_insertRace_retriesUpdate() throws Exception {
        givenProduct("H2");
        UUID pi = UUID.randomUUID();
        when(jdbcTemplate.update(contains("SUBSTRING"), any(Object[].class))).thenReturn(0, 1);
        when(jdbcTemplate.update(contains("INSERT"), any(Object[].class)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("race"));

        db.appendJsonElement(pi, "agg", "\"a\"");

        verify(jdbcTemplate).update(contains("INSERT"), any(Object[].class));
        verify(executionContext).recordVariableChange("agg", "update");
    }

    // WO-REL-44 (CRITICAL прод-инцидент): канонический порядок апсертов.
    // Две конкурентные транзакции одного инстанса с пересекающимися именами
    // в разном порядке = ABBA-deadlock (3 реальных в прод-логах). Сортировка
    // по имени перед циклом заставляет любые такие транзакции брать локи в
    // одном порядке. Оба теста гоняют вход в ОБРАТНОМ порядке и ассертят
    // порядок реальных JDBC-вызовов (не "сортировка вызвана", а наблюдаемый
    // порядок записей — P-67: мутация "убрать sort" валит оба).

    @Test
    void setVariables_postgres_upsertsInCanonicalNameOrder() throws Exception {
        givenProduct("PostgreSQL");
        UUID pi = UUID.randomUUID();
        java.util.List<String> namesInJdbcOrder = new java.util.ArrayList<>();
        when(jdbcTemplate.queryForObject(contains("ON CONFLICT"), eq(Boolean.class),
            any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                namesInJdbcOrder.add((String) inv.getArguments()[5]);
                return Boolean.TRUE;
            });

        // List.of — immutable: заодно доказывает, что прод копирует список
        // перед сортировкой, а не мутирует вход вызывающих.
        db.setVariables(pi, List.of(pv("z-var", "1"), pv("m-var", "2"), pv("a-var", "3")));

        assertThat(namesInJdbcOrder).containsExactly("a-var", "m-var", "z-var");
        verify(executionContext).recordVariableChange("a-var", "create");
        verify(executionContext).recordVariableChange("m-var", "create");
        verify(executionContext).recordVariableChange("z-var", "create");
    }

    @Test
    void setVariables_h2_upsertsInCanonicalNameOrder() throws Exception {
        givenProduct("H2");
        UUID pi = UUID.randomUUID();
        java.util.List<String> namesInJdbcOrder = new java.util.ArrayList<>();
        when(jdbcTemplate.update(contains("UPDATE"), any(Object[].class))).thenAnswer(inv -> {
            // Mockito может отдать varargs как упакованный Object[] ([1][3])
            // или развёрнуто ([4] = name в (sql, type, value, pi, name)).
            Object[] callArgs = inv.getArguments();
            String name = callArgs[1] instanceof Object[] packed
                ? (String) packed[3]
                : (String) callArgs[4];
            namesInJdbcOrder.add(name);
            return 1;
        });

        db.setVariables(pi, List.of(pv("z-var", "1"), pv("m-var", "2"), pv("a-var", "3")));

        assertThat(namesInJdbcOrder).containsExactly("a-var", "m-var", "z-var");
    }
}
