package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.model.BpmnElementStatistics;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateKind;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserTaskRepository extends JpaRepository<UserTaskEntity, UUID>, JpaSpecificationExecutor<UserTaskEntity> {

    /**
     * WO-IN-4: paged reads fetch the activity (lifecycle status) as a to-one JOIN in the
     * SAME statement — one page query, not page + per-row/batched activity lookup.
     * To-one fetch joins stay pagination-safe (unlike collection fetches, which force
     * ids+load = two statements). Single-row {@code findById} intentionally keeps NO
     * graph — {@code UserTaskMapper.toDTO} loads its activity explicitly.
     */
    @Override
    @EntityGraph(attributePaths = "activity")
    Page<UserTaskEntity> findAll(Specification<UserTaskEntity> spec, Pageable pageable);

    static Specification<UserTaskEntity> byProcessDefinitionId(UUID processDefinitionId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("processDefinitionId"), processDefinitionId);
    }

    static Specification<UserTaskEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<UserTaskEntity> byId(UUID id) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("id"), id);
    }

    /**
     * Filters by the authoritative activity lifecycle (task id == activity id):
     * completed == true -> activity COMPLETED; false -> active (CREATED/IN_PROGRESS).
     * CANCELLED/ERROR tasks are excluded from both.
     */
    static Specification<UserTaskEntity> byCompleted(boolean completed) {
        return (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var act = sub.from(ActivityEntity.class);
            sub.select(act.get("id")).where(act.get("status").in(completed
                ? List.of(ActivityStatus.COMPLETED)
                : List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)));
            return root.get("id").in(sub);
        };
    }

    /** assigned == true -> assignee is set; false -> unassigned (claimable). */
    static Specification<UserTaskEntity> byAssigned(boolean assigned) {
        return (root, query, cb) -> assigned ? cb.isNotNull(root.get("assignee")) : cb.isNull(root.get("assignee"));
    }

    static Specification<UserTaskEntity> byAssignee(String assignee) {
        return (root, query, cb) -> cb.equal(root.get("assignee"), assignee);
    }

    /**
     * WO-IN-3: «у задачи есть кандидат-группа с таким именем» — ОДИН EXISTS по
     * {@code user_task_candidates}, а не {@code LIKE} по колонке-списку.
     *
     * <p>Почему EXISTS, а не JOIN (это прямое требование WO, и оно же единственно верное):
     * JOIN размножил бы строку задачи по числу её кандидатов, и пагинация по страницам начала бы
     * отдавать дубли и пропуски — ровно тот класс дефекта, который {@code relatesTo} уже чинил
     * в WO-IN-2. Почему не LIKE: у колонки нет индекса, шаблон начинается с {@code %}, и цена
     * запроса линейна и по числу строк, и по числу групп (замер красной команды на 1M строк:
     * 1 группа 77 мс, 100 групп 3982 мс).
     *
     * <p>Сравнение значений — ТОЧНОЕ равенство, а не LIKE, и это меняет только то, что и было
     * источником расхождений: «sales» больше не может попасть в «sales-east», а «sa les» больше
     * не схлопывается в «sales». Нормализацию (trim краёв элемента) теперь делает писатель
     * ({@code CandidateGroups.parse}) — ровно так же, как и авторизация.
     */
    static Specification<UserTaskEntity> byCandidateGroup(String group) {
        return (root, query, cb) -> hasCandidate(query, cb, root, UserTaskCandidateKind.GROUP, List.of(group));
    }

    /**
     * WO-IN-3: то же для кандидата-ПОЛЬЗОВАТЕЛЯ. До этого значения не существовало ни в одной
     * строке (E-IN2-1), поэтому параметр пришлось отклонять 400; теперь роль USER хранится рядом
     * с GROUP и отвечает тем же EXISTS.
     */
    static Specification<UserTaskEntity> byCandidateUser(String user) {
        return (root, query, cb) -> hasCandidate(query, cb, root, UserTaskCandidateKind.USER, List.of(user));
    }

    static Specification<UserTaskEntity> byBpmnElementId(String bpmnElementId) {
        return (root, query, cb) -> cb.equal(root.get("bpmnElementId"), bpmnElementId);
    }

    static Specification<UserTaskEntity> byFormKey(String formKey) {
        return (root, query, cb) -> cb.equal(root.get("formKey"), formKey);
    }

    /**
     * WO-IN-2 criterion 1, переписан WO-IN-3: "всё, что касается этого человека" — ОДИН запрос:
     * исполнитель задачи этот человек ИЛИ у задачи есть кандидат-группа из групп человека.
     *
     * <p>Одна спецификация, а не несколько пагинируемых запросов, склеенных в JVM: это и было
     * причиной дублей и пропусков, и JOIN здесь невозможен (размножил бы строки). Роли
     * OR-ятся внутри одной спецификации (E-IN2-3).
     *
     * <p>Все группы человека проверяются ОДНИМ EXISTS с {@code IN (...)} на индекс
     * {@code (kind, candidate, user_task_id)} — не K EXISTS по одной. Разница не в
     * оформлении: K отдельных EXISTS — это K индексных проб, и на person's groups ~сотня это
     * снова линейная по K цена, ради снятия которой этот WO и затевался.
     *
     * <p>Fail-closed: без username и без единой группы подходящих строк нет, и спецификация
     * обязана вернуть «ничего», а не «see all».
     */
    static Specification<UserTaskEntity> relatesTo(String username, Collection<String> groups) {
        return (root, query, cb) -> {
            List<Predicate> roles = new ArrayList<>();
            if (username != null && !username.isBlank()) {
                roles.add(cb.equal(root.get("assignee"), username));
            }
            List<String> groupNames = groups == null ? List.of() : List.copyOf(groups);
            if (!groupNames.isEmpty()) {
                roles.add(hasCandidate(query, cb, root, UserTaskCandidateKind.GROUP, groupNames));
            }
            if (roles.isEmpty()) {
                // никогда `disjunction()` здесь — пустой OR это TRUE, то есть ответ «всё»
                // на фильтр по человеку, который не совпал ни с чем.
                return cb.isNull(root.get("id"));
            }
            return cb.or(roles.toArray(Predicate[]::new));
        };
    }

    /**
     * ЕДИН кандидат-матчер, общий для {@link #byCandidateGroup}, {@link #byCandidateUser} и
     * {@link #relatesTo} (P-24: общий guard должен быть общим — копия со временем сохраняет
     * именно тот баг, который писался, чтобы убрать).
     *
     * <p>Пустой список имён — это «совпасть не с чем», и он обязан давать ПУСТУЮ выборку
     * ({@code isNull(id)} — заведомо ложное условие), а не {@code disjunction()}.
     */
    private static Predicate hasCandidate(CriteriaQuery<?> query, CriteriaBuilder cb,
                                          Root<UserTaskEntity> root,
                                          UserTaskCandidateKind kind,
                                          Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return cb.isNull(root.get("id"));
        }
        Subquery<UUID> sub = query.subquery(UUID.class);
        Root<UserTaskCandidateEntity> candidate = sub.from(UserTaskCandidateEntity.class);
        sub.select(candidate.get("userTaskId"))
            .where(
                cb.equal(candidate.get("userTaskId"), root.get("id")),
                cb.equal(candidate.get("kind"), kind),
                candidate.get("candidate").in(names));
        return cb.exists(sub);
    }

    @Modifying
    @Query("UPDATE UserTaskEntity e SET e.completedAt = :completedAt WHERE e.id = :taskId")
    void setCompletedAt(UUID taskId, Instant completedAt);

    @Modifying
    @Query("UPDATE UserTaskEntity e SET e.assignee = :assignee WHERE e.id = :taskId")
    void setAssignee(UUID taskId, String assignee);

    /**
     * Atomic claim: assigns only if currently unassigned. Returns the number of rows updated
     * (1 = claimed, 0 = already claimed by someone else). Closes the read-check-write race:
     * two concurrent claims cannot both succeed, the second sees 0 rows.
     */
    @Modifying
    @Query("UPDATE UserTaskEntity e SET e.assignee = :assignee WHERE e.id = :taskId AND e.assignee IS NULL")
    int claimAssignee(UUID taskId, String assignee);

    List<UserTaskEntity> findByProcessInstanceId(UUID processInstanceId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processDefinitionId = :processDefinitionId AND e.completedAt IS NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findActiveStatsByProcessDefinitionId(UUID processDefinitionId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processDefinitionId = :processDefinitionId AND e.completedAt IS NOT NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findCompletedStatsByProcessDefinitionId(UUID processDefinitionId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM UserTaskEntity e WHERE e.processInstanceId = :processInstanceId AND e.completedAt IS NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findStatsByProcessInstanceId(UUID processInstanceId);

    @Query("SELECT MIN(e.createdAt) FROM UserTaskEntity e WHERE e.completedAt IS NULL")
    Instant findOldestOpenCreatedAt();

    List<UserTaskEntity> findByProcessDefinitionId(UUID processDefinitionId);

    List<UserTaskEntity> findByProcessDefinitionIdAndProcessInstanceId(UUID processDefinitionId, UUID processInstanceId);
}
