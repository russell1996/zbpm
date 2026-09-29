package com.zorrodev.bpm.engine.entity;

import com.zorrodev.bpm.contract.model.ProcessVariableType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-ENG-16 (WB-003): одна строка на каждое изменение переменной процесса.
 *
 * <p>Append-only: строки только вставляются ({@code VariableHistoryWriter}),
 * никогда не обновляются и не удаляются приложением (кроме retention-чистки
 * всего инстанса). Текущее значение живёт в {@code variables}
 * ({@link ProcessVariableEntity}) и здесь НЕ дублируется как состояние —
 * каждая строка фиксирует значение НА МОМЕНТ изменения.
 *
 * <p>FK на {@code process_instances} нет сознательно: audit-след не должен
 * блокировать удаление инстанса retention'ом; чистка — явным
 * {@code DELETE FROM variable_history WHERE process_instance_id IN (...)}
 * в {@code RetentionBatchProcessor.deleteInstances}.
 */
@Getter
@Setter
@Entity
@Table(name = "variable_history")
public class VariableHistoryEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    private String name;
    private String textValue;
    @Enumerated(EnumType.STRING)
    private ProcessVariableType type;
    /**
     * Скоуп переменной: {@code null} — корень инстанса; иначе id активности.
     * Зеркало {@code ProcessVariableEntity.scopeId} — без него scoped-записи
     * смешались бы с root-историей одноимённой переменной.
     */
    private UUID scopeId;
    /**
     * Кто изменил: {@code INIT} (стартовые переменные инстанса),
     * {@code CREATE} (первая запись), {@code UPDATE} (перезапись),
     * {@code APPEND} (добавление JSON-элемента).
     */
    private String source;
    private Instant changedAt;
}
