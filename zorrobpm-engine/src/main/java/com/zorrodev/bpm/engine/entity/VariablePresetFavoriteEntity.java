package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-VT-1 п.6-бис: избранный шаблон. Составной PK {@code (user_id, preset_id)} —
 * повторная отметка невозможна на уровне БД (гонку закрывает PK, не pre-check).
 * Для service-ключей {@code user_id} = ownerUserId принципала.
 */
@Getter
@Setter
@Entity
@IdClass(VariablePresetFavoriteId.class)
@Table(name = "variable_preset_favorites")
public class VariablePresetFavoriteEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "preset_id", nullable = false)
    private UUID presetId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
