package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-VT-1: шаблон переменных — именованный набор {@code ProcessVariable[]},
 * привязанный к месту применения {@code (processDefinitionKey, targetKind, targetRef)}.
 *
 * <p>Фронт берёт шаблон, даёт отредактировать и отправляет через уже существующие
 * эндпоинты (старт, complete user/service task, …) — движок НЕ меняется, нового пути
 * выполнения нет.
 *
 * <p>Хранение: {@code variables} — JSON-массив {@code {name,type,value}} одной строкой
 * (jsonb на PG, text на H2 — см. changeset 20261007-120; оба варианта маппятся сюда
 * через {@code @JdbcTypeCode(JSON)}, прецедент — {@code DomainEventEntity.data}).
 * Парсинг/валидация содержимого — в {@code VariablePresetValidator}, здесь только
 * строка.
 *
 * <p>Пустая строка вместо NULL в {@code targetRef}: UNIQUE-индекс по ТЗ —
 * {@code (owner, key, kind, coalesce(ref,''), name)}, но expression-индекс в H2 не
 * портируется без расхождения семантики (P-17), поэтому сервис нормализует NULL→''
 * на записи (START), а чтение отдаёт null обратно. Индекс — обычный UNIQUE по пяти
 * колонкам, одинаковый на обеих СУБД.
 *
 * <p>Оптимистичная блокировка — вручную через {@code version} (PUT требует совпадения,
 * конфликт — 409 PRESET_CONFLICT), а не {@code @Version}: версия приходит из API
 * явным полем, и stale-запись должна дать машинный код, а не
 * {@code OptimisticLockException}.
 */
@Getter
@Setter
@Entity
@Table(name = "variable_presets")
public class VariablePresetEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "process_definition_key", nullable = false, length = 255)
    private String processDefinitionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, length = 32)
    private VariablePresetTargetKind targetKind;

    @Column(name = "target_ref", length = 255)
    private String targetRef;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false)
    private String variables;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    private VariablePresetVisibility visibility;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "version", nullable = false)
    private int version;
}
