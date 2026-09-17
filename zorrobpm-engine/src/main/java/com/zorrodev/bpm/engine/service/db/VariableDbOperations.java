package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен Variables.
 */
public interface VariableDbOperations {

    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId);

    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId, UUID scopeId);

    void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables);

    void setVariables(@NonNull UUID processInstanceId, UUID scopeId, List<ProcessVariable> variables);

    void deleteVariables(@NonNull UUID processInstanceId, UUID scopeId);

    /**
     * WO-REL-41 (B-8, п.2): pinpoint read of ONE root variable's text value
     * (the MI batch-UUID lookup). Root scope only — a scoped variable with
     * the same name must never leak in. Empty when the row is absent.
     */
    Optional<String> getVariableTextValue(@NonNull UUID processInstanceId, String name);

    /**
     * WO-REL-41 (B-8, п.1): atomically appends one JSON element to a root
     * JSON-list variable, creating it when absent. The whole read-modify-write
     * happens in ONE statement (PostgreSQL: {@code ::jsonb ||} merge; H2: text
     * surgery), so concurrent MI completions cannot lose each other's
     * elements — unlike the old Java-side read-then-write. Semantics mirror
     * it exactly: only a stored JSON <i>array</i> is extended, anything else
     * (absent row, scalar, object, corrupt text) starts a fresh single-element
     * list; the appended value is always ONE element even when it is itself
     * an array. Reports the write kind for conditional tracking like
     * {@link #setVariables(UUID, List)} does.
     *
     * @param jsonElement the element already serialised to JSON (no further quoting).
     */
    void appendJsonElement(@NonNull UUID processInstanceId, String name, String jsonElement);

    /**
     * WO-ENG-16 (WB-003): минимальный способ прочитать историю — хронология
     * всех изменений переменных инстанса (старые первые). REST-эндпоинт
     * сознательно НЕ добавлен (стоп-список G-C, WO допускает service/SQL
     * уровень); прямой SQL для диагностики — в отчёте WO-ENG-16.
     */
    List<VariableHistoryEntry> getVariableHistory(@NonNull UUID processInstanceId);

    /** То же для одной переменной (спор о значении в момент решения gateway'ем). */
    List<VariableHistoryEntry> getVariableHistory(@NonNull UUID processInstanceId, String name);
}
