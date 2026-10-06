package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateKind;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.security.CandidateGroups;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WO-IN-3: единственный писатель строк-кандидатов. Живёт отдельным узким компонентом, а не
 * ещё одним методом в {@code UserTaskDbOperationsImpl}, потому что его вызывают два разных
 * места (прямая активация user task и multi-instance), и разъезжаться им нельзя — а тестам
 * нужно вызывать РОВНО этот код, чтобы фикстура доказывала живой путь, а не обход (P-11, G-N).
 *
 * <p>Разбор обоих списков — через {@link CandidateGroups#parse}, то есть тем же trim-правилом,
 * что и у авторизации: «sales , east» это две группы sales и east с обеих сторон границы.
 * Своя копия разбора здесь была бы ровно тем дрейфом, ради которого класс и выделен.
 */
@Component
@RequiredArgsConstructor
public class UserTaskCandidateWriter {

    private final UserTaskCandidateRepository userTaskCandidateRepository;

    /**
     * Пишет кандидатов задачи. Вызывается из {@code UserTaskDbOperationsImpl.createUserTask} —
     * в том же вызове и в той же транзакции, что и сама строка задачи, поэтому откат транзакции
     * убирает и её, и кандидатов: «задача есть, кандидатов нет» невозможно даже на секунду.
     *
     * <p>Пустые/пробельные значения не пишут ничего (ноль строк — и {@code saveAll} не вызывается
     * вовсе, чтобы не открывать транзакцию ради пустого списка).
     */
    public void writeCandidates(UUID userTaskId, String commaSeparatedGroups, String commaSeparatedUsers) {
        List<UserTaskCandidateEntity> rows = new ArrayList<>();
        for (String group : CandidateGroups.parse(commaSeparatedGroups)) {
            rows.add(row(userTaskId, UserTaskCandidateKind.GROUP, group));
        }
        for (String user : CandidateGroups.parse(commaSeparatedUsers)) {
            rows.add(row(userTaskId, UserTaskCandidateKind.USER, user));
        }
        if (!rows.isEmpty()) {
            // saveAll, а не save поштучно: составной PK не даёт Spring Data понять, что строка
            // новая, поэтому каждый save ушёл бы в merge (SELECT + INSERT). Одним merge на
            // пачку — один SELECT на пачку вместо одного на строку.
            userTaskCandidateRepository.saveAll(rows);
        }
    }

    private UserTaskCandidateEntity row(UUID userTaskId, UserTaskCandidateKind kind, String name) {
        UserTaskCandidateEntity entity = new UserTaskCandidateEntity();
        entity.setUserTaskId(userTaskId);
        entity.setKind(kind);
        entity.setCandidate(name);
        return entity;
    }
}
