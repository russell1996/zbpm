package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.handler.ExecutionContext;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WO-DEBT-1g: домен Variables — реализация.
 * Перенесено 1:1 из DBServiceImpl (5 методов + toProcessVariable).
 *
 * WO-REL-31 CR-1: setVariables — upsert вместо select-then-insert. Старый
 * find-by-scope-null + saveAll двумя конкурентными потоками оба находили
 * пустоту и оба вставляли (молчаливый дубликат до 108, DuplicateKey после
 * 108). Два диалекта: PG — один стейтмент ON CONFLICT (единственный
 * безопасный вариант внутри транзакции — TimerJobExecutor ходит сюда под
 * REQUIRES_NEW, проигранная гонка через DuplicateKey там роняла бы всю
 * транзакцию 25P02); H2 — guarded UPDATE + INSERT + retry (ON CONFLICT в
 * H2 нет, PgRateLimiter-прецедент). Product-detect как в AdvisoryDeployLock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VariableDbOperationsImpl implements VariableDbOperations {

    private static final String UPSERT_PG =
        "INSERT INTO variables (id, process_instance_id, scope_id, name, type, text_value) " +
        "VALUES (?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT (process_instance_id, name, scope_id) DO UPDATE " +
        "SET type = EXCLUDED.type, text_value = EXCLUDED.text_value " +
        "RETURNING (xmax = 0)";

    private static final String UPDATE_H2_ROOT =
        "UPDATE variables SET type = ?, text_value = ? " +
        "WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL";

    private static final String UPDATE_H2_SCOPED =
        "UPDATE variables SET type = ?, text_value = ? " +
        "WHERE process_instance_id = ? AND name = ? AND scope_id = ?";

    private static final String INSERT_H2 =
        "INSERT INTO variables (id, process_instance_id, scope_id, name, type, text_value) " +
        "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String SELECT_ID_BY_KEY =
        "SELECT id FROM variables WHERE process_instance_id = ? AND name = ? AND %s";

    private final VariableRepository variableRepository;
    private final ExecutionContext executionContext;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final EntityManager entityManager;

    private volatile String databaseProduct;

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId) {
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId).stream()
            .map(this::toProcessVariable)
            .toList();
    }

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId, UUID scopeId) {
        // WO-REL-31 CR-4: single root+scoped query instead of two repository reads.
        // Repository orders root rows before scope rows, so the merge keeps the
        // documented "scoped wins for duplicate names" semantics.
        Map<String, ProcessVariable> merged = new LinkedHashMap<>();
        for (ProcessVariableEntity e : variableRepository.findRootAndScoped(processInstanceId, scopeId)) {
            merged.put(e.getName(), toProcessVariable(e));
        }
        return new ArrayList<>(merged.values());
    }

    private ProcessVariable toProcessVariable(ProcessVariableEntity variable) {
        ProcessVariable result = new ProcessVariable();
        result.setName(variable.getName());
        result.setType(variable.getType());
        result.setValue(variable.getTextValue());
        return result;
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables) {
        setVariables(processInstanceId, null, variables);
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, UUID scopeId, List<ProcessVariable> variables) {
        boolean postgres = "PostgreSQL".equals(getDatabaseProduct());
        for (ProcessVariable variable : variables) {
            String kind = postgres
                ? upsertPostgres(processInstanceId, scopeId, variable)
                : upsertGuarded(processInstanceId, scopeId, variable);
            if ("update".equals(kind)) {
                // WO-REL-31 CR-1 regression fix (AdHocJobWorkerIntegrationTests.
                // staleJobCompletion_failsExplicitly): the upsert writes via JDBC,
                // bypassing the persistence context. A managed ProcessVariableEntity
                // for the same row loaded EARLIER in this transaction would stay
                // stale — subsequent JPA reads in the same tx (triggerConditionalEvents,
                // AdHocJoin.resolve, test harnesses) would see the OLD value. Detach
                // the row's managed copy so the next read re-fetches the new value.
                // Surgical detach, not clear(): activities/instances must stay managed.
                evictVariable(processInstanceId, scopeId, variable.getName());
            }
            // WO-C8-29: record for conditionalFilter matching (create vs update is known
            // exactly here — the upsert reports which one happened).
            executionContext.recordVariableChange(variable.getName(), kind);
        }
    }

    /**
     * WO-REL-31 CR-1: detaches the persistence-context copy of one variable row
     * (by natural key), so same-transaction JPA re-reads after a JDBC upsert see
     * the new value instead of the stale managed snapshot. Null-safe: unknown or
     * concurrently deleted rows are simply skipped.
     */
    private void evictVariable(UUID processInstanceId, UUID scopeId, String name) {
        try {
            String predicate = scopeId == null ? "scope_id IS NULL" : "scope_id = ?";
            List<UUID> ids = scopeId == null
                ? jdbcTemplate.queryForList(
                    String.format(SELECT_ID_BY_KEY, predicate), UUID.class, processInstanceId, name)
                : jdbcTemplate.queryForList(
                    String.format(SELECT_ID_BY_KEY, predicate), UUID.class, processInstanceId, name, scopeId);
            for (UUID id : ids) {
                if (id != null) {
                    entityManager.detach(entityManager.getReference(ProcessVariableEntity.class, id));
                }
            }
        } catch (RuntimeException e) {
            // Eviction is a read-visibility optimization, never part of the write
            // contract: a failed evict must not fail the variable write itself.
            log.warn("Failed to evict variable {} for process {}", name, processInstanceId, e);
        }
    }

    /**
     * WO-REL-31 CR-1, прод-путь: один стейтмент, атомарно, безопасно внутри
     * чужой транзакции. {@code RETURNING (xmax = 0)} отличает вставку
     * (xmax = 0 — строка создана этим стейтментом) от обновления.
     *
     * @return "create" если строка вставлена, "update" если обновлена.
     */
    private String upsertPostgres(UUID processInstanceId, UUID scopeId, ProcessVariable variable) {
        String textValue = variable.getValue() != null ? variable.getValue() : "";
        Boolean inserted = jdbcTemplate.queryForObject(UPSERT_PG, Boolean.class,
            UUID.randomUUID(), processInstanceId, scopeId,
            variable.getName(), variable.getType().name(), textValue);
        return Boolean.TRUE.equals(inserted) ? "create" : "update";
    }

    /**
     * WO-REL-31 CR-1, H2-путь (тесты): ON CONFLICT в H2 нет, поэтому
     * guarded UPDATE, затем INSERT; проигранная гонка на вставке ловится
     * DuplicateKey и сходится повторным UPDATE (паттерн PgRateLimiter).
     * Внутри транзакции не вызывается — H2-контексты тестов это допускают.
     *
     * @return "create" если строка вставлена, "update" если обновлена.
     */
    private String upsertGuarded(UUID processInstanceId, UUID scopeId, ProcessVariable variable) {
        String textValue = variable.getValue() != null ? variable.getValue() : "";
        String typeName = variable.getType().name();
        if (updateExisting(processInstanceId, scopeId, variable.getName(), typeName, textValue) == 1) {
            return "update";
        }
        try {
            jdbcTemplate.update(INSERT_H2, UUID.randomUUID(), processInstanceId, scopeId,
                variable.getName(), typeName, textValue);
            return "create";
        } catch (DuplicateKeyException insertRaceLost) {
            updateExisting(processInstanceId, scopeId, variable.getName(), typeName, textValue);
            return "update";
        }
    }

    private int updateExisting(UUID processInstanceId, UUID scopeId, String name, String typeName, String textValue) {
        return scopeId == null
            ? jdbcTemplate.update(UPDATE_H2_ROOT, typeName, textValue, processInstanceId, name)
            : jdbcTemplate.update(UPDATE_H2_SCOPED, typeName, textValue, processInstanceId, name, scopeId);
    }

    /** Product-detect как в AdvisoryDeployLock: JdbcTemplate + DataSource, volatile-кэш. */
    private String getDatabaseProduct() {
        String cached = databaseProduct;
        if (cached != null) {
            return cached;
        }
        try (var conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            databaseProduct = product;
            return product;
        } catch (Exception e) {
            log.warn("Failed to detect database product, assuming PostgreSQL", e);
            return "PostgreSQL";
        }
    }

    @Override
    public void deleteVariables(@NonNull UUID processInstanceId, UUID scopeId) {
        variableRepository.deleteByProcessInstanceIdAndScopeId(processInstanceId, scopeId);
    }
}
