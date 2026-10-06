package com.zorrodev.bpm.engine.entity;

/**
 * Роль кандидата user task — ровно две, и третей быть не может (тот же CHECK в БД, что и здесь:
 * {@code ck_user_task_candidates__kind}).
 *
 * <p>Роль хранится строкой, а не «одна колонка на роль», потому что вопрос «кто кандидат?» —
 * это ВСЕГДА вопрос «есть ли у задачи кандидат такого-то имени в такой-то роли?»: один EXISTS по
 * (kind, candidate) отвечает на оба фильтра (candidateGroup и candidateUser) и на relatesTo, и
 * две отдельные колонки с OR между ними раскладывали бы одну и ту же проверку дважды.
 *
 * <p>Имена строк совпадают со значениями CHECK-constraint'а — это единственное место, где они
 * записаны буквами, и changeset-118.
 */
public enum UserTaskCandidateKind {
    /** Кандидат-человек: {@code candidate} — имя пользователя (то, что пишется в assignee). */
    USER,
    /** Кандидат-группа: {@code candidate} — имя группы (то, что было в candidate_groups). */
    GROUP
}
