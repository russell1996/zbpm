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
import java.util.Optional;
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

    /**
     * WO-REL-41 (B-8, п.1): one statement, no Java-side read-modify-write.
     * INSERT covers the absent row; ON CONFLICT merges the element into the
     * stored JSON array. {@code jsonb_build_array} forces append-as-single —
     * a bare {@code ||} would CONCATENATE an array-valued element instead of
     * nesting it (Java {@code list.add} semantics). Only a stored JSON array
     * is extended; anything else restarts a fresh single-element list.
     * {@code RETURNING (xmax = 0)} reports create vs update like UPSERT_PG.
     */
    private static final String APPEND_PG =
        "INSERT INTO variables (id, process_instance_id, scope_id, name, type, text_value) " +
        "VALUES (?, ?, NULL, ?, 'JSON', '[' || ? || ']') " +
        "ON CONFLICT (process_instance_id, name, scope_id) DO UPDATE " +
        "SET type = 'JSON', " +
        "    text_value = (CASE WHEN variables.type = 'JSON' AND variables.text_value ~ '^\\s*\\[' " +
        "THEN (variables.text_value::jsonb || jsonb_build_array(?::jsonb))::text " +
        "ELSE '[' || ? || ']' END) " +
        "RETURNING (xmax = 0)";

    /**
     * WO-REL-41 (B-8, п.1), H2-путь (тесты): та же семантика текстовой
     * хирургией — jsonb в H2 нет. TRIM + LIKE проверяют массив, SUBSTRING
     * срезает закрывающую скобку; формат исходного текста сохраняется.
     * Отдельная ветка под вырожденный `'[]'` (verifier WO-REL-41: иначе
     * SUBSTRING дал бы `'[,"a"]'` — невалидный JSON).
     */
    private static final String APPEND_H2_UPDATE =
        "UPDATE variables SET type = 'JSON', text_value = " +
        "(CASE WHEN TRIM(text_value) = '[]' THEN '[' || ? || ']' " +
        "WHEN TRIM(text_value) LIKE '[%]' " +
        "THEN SUBSTRING(TRIM(text_value), 1, LENGTH(TRIM(text_value)) - 1) || ',' || ? || ']' " +
        "ELSE '[' || ? || ']' END) " +
        "WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL";

    /**
     * WO-DIFF-3 (#4): позиционная запись в JSON-массив одним стейтментом.
     * Проверено живьём на PG16: {@code jsonb_set} с {@code create_if_missing}
     * НЕ паддингует гэпы (пишет элемент в слот 0 — данные на неверной
     * позиции), поэтому сборка списка явная: {@code FRESH_PADDED_LIST}
     * ({@code null} × index + элемент через {@code generate_series}).
     * Ветки CASE: отсутствие строки → свежий паддингом список; хранимый
     * JSON-массив в границах индекса → {@code jsonb_set} на месте; массив
     * короче индекса → {@code ||} хвоста-паддинга; всё остальное
     * (скаляр/объект/битый текст) → свежий список (тот же fail-open, что
     * APPEND_PG-ELSE). Индекс отрицательным быть не может — проверено в
     * Java до стейтмента; повтор {@code ?} под индекс — один и тот же слот
     * во всех ветках.
     */
    private static final String FRESH_PADDED_LIST =
        "(SELECT jsonb_agg(CASE WHEN g.i < ? THEN 'null'::jsonb ELSE ?::jsonb END ORDER BY g.i) " +
        "FROM generate_series(0, ?) AS g(i))";

    private static final String SET_AT_PG =
        "INSERT INTO variables (id, process_instance_id, scope_id, name, type, text_value) " +
        "VALUES (?, ?, NULL, ?, 'JSON', " + FRESH_PADDED_LIST + "::text) " +
        "ON CONFLICT (process_instance_id, name, scope_id) DO UPDATE " +
        "SET type = 'JSON', " +
        "    text_value = (CASE WHEN variables.type = 'JSON' AND variables.text_value ~ '^\\s*\\[' " +
        "THEN (CASE WHEN jsonb_array_length(variables.text_value::jsonb) > ? " +
        "  THEN jsonb_set(variables.text_value::jsonb, ARRAY[?::text], ?::jsonb, true)::text " +
        "  ELSE (variables.text_value::jsonb || (SELECT jsonb_agg(CASE WHEN g.i < (? - jsonb_array_length(variables.text_value::jsonb)) THEN 'null'::jsonb ELSE ?::jsonb END) " +
        "    FROM generate_series(0, (? - jsonb_array_length(variables.text_value::jsonb))) AS g(i)))::text END) " +
        "ELSE (" + FRESH_PADDED_LIST + ")::text END) " +
        "RETURNING (xmax = 0)";

    /** WO-DIFF-3 (#4), H2: перезапись целиком (текст собран в {@link #mergeAtIndex}). */
    private static final String SET_AT_H2_UPDATE =
        "UPDATE variables SET type = 'JSON', text_value = ? " +
        "WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL";

    private final VariableRepository variableRepository;
    private final com.zorrodev.bpm.engine.repository.VariableHistoryRepository historyRepository;
    private final ExecutionContext executionContext;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final EntityManager entityManager;
    /** WO-ENG-16: audit-след изменений (узкий бин, не метод этого класса — другой домен). */
    private final VariableHistoryWriter historyWriter;

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
        // WO-REL-44 (CRITICAL прод-инцидент): канонический порядок апсертов по
        // имени ПЕРЕД циклом. Две конкурентные транзакции одного process
        // instance пишут пересекающиеся имена каждая в своём порядке
        // (порядок ioMapping в BPMN конкретного элемента) — без сортировки это
        // классический ABBA lock-order deadlock: TxA держит X и ждёт Y, TxB
        // держит Y и ждёт X (3 реальных deadlock'а в прод-логах за 48ч, все —
        // одна и та же пара UPSERT-стейтментов на variables). Сортировка
        // гарантирует, что ЛЮБЫЕ две такие транзакции берут построчные локи в
        // ОДНОМ порядке — класс гонки устранён целиком, не патч на случай.
        // Имена внутри одного вызова уникальны (ключ включает name), так что
        // порядок тотален без tiebreak. Копия списка — входной list вызывающих
        // не мутирует (у части вызывающих — List.of/неизменяемые).
        List<ProcessVariable> ordered = new ArrayList<>(variables);
        ordered.sort(java.util.Comparator.comparing(ProcessVariable::getName,
            java.util.Comparator.nullsFirst(String::compareTo)));
        for (ProcessVariable variable : ordered) {
            String kind = postgres
                ? upsertPostgres(processInstanceId, scopeId, variable)
                : upsertGuarded(processInstanceId, scopeId, variable);
            // WO-ENG-16: история пишется при КАЖДОМ изменении (create и update) —
            // единая точка, а не размазанный INSERT по вызывающим.
            historyWriter.record(processInstanceId, scopeId, variable,
                "create".equals(kind) ? VariableHistoryWriter.SOURCE_CREATE : VariableHistoryWriter.SOURCE_UPDATE);
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
    public List<ProcessVariable> getVariablesByNames(@NonNull UUID processInstanceId,
            java.util.Collection<String> names) {
        // WO-PERF-9: no names → no query (IN () is invalid SQL on some dialects,
        // and a literal-cardinality MI needs nothing from the DB at all).
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNullAndNameIn(processInstanceId, names)
            .stream()
            .map(this::toProcessVariable)
            .toList();
    }

    @Override
    public List<ProcessVariable> getScopedVariablesByNames(@NonNull UUID processInstanceId, UUID scopeId,
            java.util.Collection<String> names) {
        // WO-PERF-9: same scoped-wins merge as getVariables(pi, scopeId), but
        // over the named rows only — one indexed SELECT instead of the merge
        // over the whole root+scope.
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        Map<String, ProcessVariable> merged = new LinkedHashMap<>();
        for (ProcessVariableEntity e : variableRepository.findRootAndScopedByNames(processInstanceId, scopeId, names)) {
            merged.put(e.getName(), toProcessVariable(e));
        }
        return new ArrayList<>(merged.values());
    }

    @Override
    public Optional<String> getVariableTextValue(@NonNull UUID processInstanceId, String name) {
        return variableRepository.findByProcessInstanceIdAndNameAndScopeIdIsNull(processInstanceId, name)
            .map(ProcessVariableEntity::getTextValue);
    }

    @Override
    public void appendJsonElement(@NonNull UUID processInstanceId, String name, String jsonElement) {
        boolean postgres = "PostgreSQL".equals(getDatabaseProduct());
        String kind = postgres
            ? appendJsonElementPostgres(processInstanceId, name, jsonElement)
            : appendJsonElementGuarded(processInstanceId, name, jsonElement);
        if ("update".equals(kind)) {
            // Same stale-managed-copy reason as setVariables above: the write
            // bypasses the persistence context via JDBC.
            evictVariable(processInstanceId, null, name);
        }
        // WO-ENG-16: история фиксирует РЕЗУЛЬТИРУЮЩЕЕ значение (не дельту) —
        // строго ПОСЛЕ evict выше, иначе pinpoint-read вернул бы stale
        // managed-копию вместо слитого стейтментом массива.
        ProcessVariable snapshot = new ProcessVariable();
        snapshot.setName(name);
        snapshot.setType(com.zorrodev.bpm.contract.model.ProcessVariableType.JSON);
        snapshot.setValue(getVariableTextValue(processInstanceId, name).orElse("[" + jsonElement + "]"));
        historyWriter.record(processInstanceId, null, snapshot, VariableHistoryWriter.SOURCE_APPEND);
        // WO-C8-29: same conditional tracking as a variable write.
        executionContext.recordVariableChange(name, kind);
    }

    /**
     * WO-DIFF-3 (#4), прод-путь: один стейтмент, атомарно (см. SET_AT_PG —
     * ветви и паддинг-семантика там; живьём проверено на PG16, что
     * {@code jsonb_set} гэпы НЕ паддингует, поэтому короткий хвост
     * достраивается явным {@code ||}-паддингом).
     * {@code RETURNING (xmax = 0)} отличает вставку от обновления как в
     * UPSERT_PG/APPEND_PG.
     *
     * @return "create" если строка вставлена, "update" если расширена/перезаписана.
     */
    private String setJsonElementAtPostgres(UUID processInstanceId, String name, int index, String jsonElement) {
        // Порядок строго по SET_AT_PG (15 плейсхолдеров после id/pi/name):
        // INSERT-ветка (idx, elem, idx); UPDATE-ветка — len-check idx;
        // jsonb_set idx-as-text, elem; pad-хвост (idx-delta, elem, idx-delta);
        // ELSE-ветка (idx, elem, idx).
        Boolean inserted = jdbcTemplate.queryForObject(SET_AT_PG, Boolean.class,
            UUID.randomUUID(), processInstanceId, name,
            index, jsonElement, index,
            index, index, jsonElement,
            index, jsonElement, index,
            index, jsonElement, index);
        return Boolean.TRUE.equals(inserted) ? "create" : "update";
    }

    /**
     * WO-REL-41 (B-8, п.1), H2-путь (тесты): UPDATE-хирургия, затем INSERT
     * (INSERT_H2 с NULL-скоупом — корень); проигранная гонка на вставке
     * сходится повторным UPDATE, как upsertGuarded.
     *
     * @return "create" если строка вставлена, "update" если расширена/перезаписана.
     */
    /**
     * WO-REL-41 (B-8, п.1), прод-путь: один стейтмент, атомарно, безопасно
     * внутри чужой транзакции (та же причина, что UPSERT_PG в CR-1).
     *
     * @return "create" если строка вставлена, "update" если обновлена.
     */
    private String appendJsonElementPostgres(UUID processInstanceId, String name, String jsonElement) {
        Boolean inserted = jdbcTemplate.queryForObject(APPEND_PG, Boolean.class,
            UUID.randomUUID(), processInstanceId, name, jsonElement, jsonElement, jsonElement);
        return Boolean.TRUE.equals(inserted) ? "create" : "update";
    }

    private String appendJsonElementGuarded(UUID processInstanceId, String name, String jsonElement) {
        if (jdbcTemplate.update(APPEND_H2_UPDATE, jsonElement, jsonElement, jsonElement, processInstanceId, name) == 1) {
            return "update";
        }
        try {
            jdbcTemplate.update(INSERT_H2, UUID.randomUUID(), processInstanceId, null,
                name, "JSON", "[" + jsonElement + "]");
            return "create";
        } catch (DuplicateKeyException insertRaceLost) {
            jdbcTemplate.update(APPEND_H2_UPDATE, jsonElement, jsonElement, jsonElement, processInstanceId, name);
            return "update";
        }
    }

    @Override
    public void setJsonElementAt(@NonNull UUID processInstanceId, String name, int index, String jsonElement) {
        if (index < 0) {
            // WO-DIFF-3 (#4): fail-closed — отрицательный слот всегда баг
            // вызывающего (loopCounter ≥ 1), молчаливый append спрятал бы его.
            throw new IllegalArgumentException("JSON-list index must be non-negative: " + index);
        }
        boolean postgres = "PostgreSQL".equals(getDatabaseProduct());
        String kind = postgres
            ? setJsonElementAtPostgres(processInstanceId, name, index, jsonElement)
            : setJsonElementAtGuarded(processInstanceId, name, index, jsonElement);
        if ("update".equals(kind)) {
            // Same stale-managed-copy reason as appendJsonElement above.
            evictVariable(processInstanceId, null, name);
        }
        // Same post-write contract as appendJsonElement above: history records
        // the RESULTING value (strictly after the evict), conditional tracking
        // follows the reported kind.
        ProcessVariable snapshot = new ProcessVariable();
        snapshot.setName(name);
        snapshot.setType(com.zorrodev.bpm.contract.model.ProcessVariableType.JSON);
        snapshot.setValue(getVariableTextValue(processInstanceId, name).orElse("[" + jsonElement + "]"));
        historyWriter.record(processInstanceId, null, snapshot, VariableHistoryWriter.SOURCE_APPEND);
        executionContext.recordVariableChange(name, kind);
    }

    /**
     * WO-DIFF-3 (#4), H2-путь (тесты): та же слот-семантика текстовой
     * хирургией — jsonb в H2 нет. Строка разбирается в Java (Jackson уже
     * читает JSON в этом классе через history-снапшот выше — формат единый),
     * затем один UPDATE; отсутствующая строка → INSERT свежего паддингом
     * списка; проигранная гонка на вставке сходится повторным UPDATE, как
     * appendJsonElementGuarded. Не-массив/битый текст → свежий список
     * (тот же fail-open, что APPEND_H2_UPDATE-ELSE).
     *
     * @return "create" если строка вставлена, "update" если расширена/перезаписана.
     */
    private String setJsonElementAtGuarded(UUID processInstanceId, String name, int index, String jsonElement) {
        String current = null;
        try {
            current = jdbcTemplate.queryForObject(
                "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL",
                String.class, processInstanceId, name);
        } catch (org.springframework.dao.EmptyResultDataAccessException absent) {
            current = null;
        }
        String merged = mergeAtIndex(current, index, jsonElement);
        if (current != null
            && jdbcTemplate.update(SET_AT_H2_UPDATE, merged, processInstanceId, name) == 1) {
            return "update";
        }
        try {
            jdbcTemplate.update(INSERT_H2, UUID.randomUUID(), processInstanceId, null,
                name, "JSON", merged);
            return "create";
        } catch (DuplicateKeyException insertRaceLost) {
            jdbcTemplate.update(SET_AT_H2_UPDATE, merged, processInstanceId, name);
            return "update";
        }
    }

    /**
     * WO-DIFF-3 (#4): чистая слот-функция (диалект-независимая, тестируема без
     * БД): разбирает текущий текст, ставит элемент в слот {@code index}
     * (паддинг {@code null} при коротком/отсутствующем списке), собирает
     * обратно. Не-массив/битый текст/отсутствие → свежий паддингом список.
     */
    static String mergeAtIndex(String current, int index, String jsonElement) {
        java.util.List<String> slots = new java.util.ArrayList<>();
        if (current != null) {
            String trimmed = current.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                String inner = trimmed.substring(1, trimmed.length() - 1).trim();
                if (!inner.isEmpty()) {
                    // Элементы уже сериализованы в JSON (append-путь пишет их
                    // через запятую без внешнего экранирования) — режем по
                    // верхнеуровневым запятым, а не наивным split(",").
                    slots.addAll(splitTopLevel(inner));
                }
            }
        }
        while (slots.size() <= index) {
            slots.add("null");
        }
        slots.set(index, jsonElement);
        return "[" + String.join(",", slots) + "]";
    }

    /**
     * WO-DIFF-3 (#4): разрез JSON-списка по верхнеуровневым запятым (вложенные
     * массивы/объекты/строки с запятыми и экранированием не рвутся).
     */
    static List<String> splitTopLevel(String inner) {
        List<String> parts = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (inString) {
                cur.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    cur.append(c);
                }
                case '[', '{' -> {
                    depth++;
                    cur.append(c);
                }
                case ']', '}' -> {
                    depth--;
                    cur.append(c);
                }
                case ',' -> {
                    if (depth == 0) {
                        parts.add(cur.toString().trim());
                        cur.setLength(0);
                    } else {
                        cur.append(c);
                    }
                }
                default -> cur.append(c);
            }
        }
        parts.add(cur.toString().trim());
        return parts;
    }

    @Override
    public void deleteVariables(@NonNull UUID processInstanceId, UUID scopeId) {
        variableRepository.deleteByProcessInstanceIdAndScopeId(processInstanceId, scopeId);
        // WO-ENG-16: снос скоупа историю НЕ пишет — это lifecycle-мусор
        // (временные ioMapping-входы), не бизнес-изменение значения.
    }

    @Override
    public List<VariableHistoryEntry> getVariableHistory(@NonNull UUID processInstanceId) {
        return historyRepository.findByProcessInstanceIdOrderByChangedAtAscIdAsc(processInstanceId)
            .stream().map(VariableDbOperationsImpl::toEntry).toList();
    }

    @Override
    public List<VariableHistoryEntry> getVariableHistory(@NonNull UUID processInstanceId, String name) {
        return historyRepository.findByProcessInstanceIdAndNameOrderByChangedAtAscIdAsc(processInstanceId, name)
            .stream().map(VariableDbOperationsImpl::toEntry).toList();
    }

    private static VariableHistoryEntry toEntry(com.zorrodev.bpm.engine.entity.VariableHistoryEntity e) {
        return new VariableHistoryEntry(e.getId(), e.getProcessInstanceId(), e.getName(),
            e.getTextValue(), e.getType(), e.getScopeId(), e.getSource(), e.getChangedAt());
    }
}
