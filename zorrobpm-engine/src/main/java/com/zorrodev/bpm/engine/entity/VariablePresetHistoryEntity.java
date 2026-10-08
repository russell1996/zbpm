package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-VT-1 п.6-бис: запись истории правок шаблона — CREATE/UPDATE со снапшотами
 * переменных до/после (только значения шаблона, без токенов/секретов — их в
 * шаблонах нет по «Не делать» WO). DELETE здесь нет сознательно: строка истории
 * каскадно умирает вместе с шаблоном, удаление фиксирует аудит-лог
 * ({@code PRESET_DELETE} пишет сервис) — см. комментарий changeset 20261007-121.
 *
 * <p>Снапшоты — JSON-массив {@code {name,type,value,allowEmptyString?}} той же
 * канонической формы, что колонка {@code variables} шаблона (пишет и читает
 * {@code VariablePresetValidator}), поэтому round-trip истории — побайтовое
 * равенство строк.
 */
@Getter
@Setter
@Entity
@Table(name = "variable_preset_history")
public class VariablePresetHistoryEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "preset_id", nullable = false)
    private UUID presetId;

    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "at", nullable = false)
    private Instant at;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables_before")
    private String variablesBefore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables_after")
    private String variablesAfter;
}
