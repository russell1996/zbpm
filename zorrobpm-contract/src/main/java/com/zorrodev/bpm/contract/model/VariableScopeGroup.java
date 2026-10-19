package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * WO-C8-51 (часть A): группа переменных одного скоупа инстанса процесса —
 * ответ {@code GET /process-instances/{id}/variables-by-scope}.
 *
 * <p>Первая группа ответа — всегда root-скоуп инстанса ({@code scope == null});
 * дальше — по группе на каждый существующий скоуп (в порядке создания
 * activity-строки, детерминированно по tie-break). Внутри группы переменные
 * отсортированы по (имя, значение).
 */
@Getter
@Setter
public class VariableScopeGroup {
    /** {@code null} = root-скоуп инстанса; иначе описание скоупа-активности. */
    private VariableScopeInfo scope;
    /** Переменные ЭТОГО скоупа (без слияния с root — в отличие от видимого контекста FEEL). */
    private List<ProcessVariable> variables;
}
