package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * WO-IN-3: одна строка — один кандидат user task. Нормализованная замена списка «через запятую»
 * в {@code user_tasks.candidate_groups}, из-за которого фильтр по кандидату был полным сканом
 * таблицы, а фильтр по кандидату-пользователю невозможен вовсе (E-IN2-1).
 *
 * <p>Составной PK {@code (userTaskId, kind, candidate)} — не украшение: он делает повторную
 * запись того же кандидата физически невозможной, поэтому писателю не нужен отдельный
 * «а уже есть?» и дубль не создаст вторую строку, которую потом пришлось бы чистить.
 *
 * <p>Колонка называется {@code candidate}, а не {@code value} (так в sketch'е WO): VALUE —
 * зарезервированное слово H2, и Liquibase генерирует DDL без кавычек, поэтому changeset с
 * {@code value} уронил бы старт контекста на всех H2-тестах (проверено на H2 2.3.232/2.4.240).
 *
 * <p>Легаси-колонка {@code candidate_groups} продолжает писаться параллельно (двухфазная
 * миграция): её читает авторизация ({@code AuthorizationService}). Этот класс её НЕ читает.
 */
@Getter
@Setter
@Entity
@IdClass(UserTaskCandidateEntity.UserTaskCandidateId.class)
@Table(name = "user_task_candidates")
public class UserTaskCandidateEntity {

    @Id
    @Column(name = "user_task_id")
    private UUID userTaskId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private UserTaskCandidateKind kind;

    @Id
    @Column(name = "candidate")
    private String candidate;

    public record UserTaskCandidateId(UUID userTaskId, UserTaskCandidateKind kind, String candidate)
        implements Serializable {
    }
}
