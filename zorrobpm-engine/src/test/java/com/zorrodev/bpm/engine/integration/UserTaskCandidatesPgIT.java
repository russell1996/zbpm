package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.entity.UserGroupEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.retention.RetentionBatchProcessor;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-IN-3 на живой PostgreSQL 16. Три вещи, которые H2 не может показать (V11 / P-17 / P-22):
 * сам артефакт миграции, план запроса и поведение FK при удалении по retention.
 *
 * <p>Всё применяет Liquibase при старте контекста (G-N/G9): тесты НЕ вставляют SQL миграции
 * руками — вычеркни changeset, и они упадут на отсутствии таблицы, а не станут «зелёными».
 *
 * <p>Изоляция: чужой контейнер и чужая БД не трогаются. Всё, что нужно замерить, живёт в своём
 * именованном контейнере {@code postgres:16}, который удаляется после прогона.
 */
public class UserTaskCandidatesPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired QueryService queryService;
    @Autowired UiUserRepository uiUserRepository;
    @Autowired UserGroupRepository userGroupRepository;
    @Autowired PasswordHasher passwordHasher;
    @Autowired RetentionBatchProcessor batchProcessor;

    private UUID pdId;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("TRUNCATE user_task_candidates, user_tasks, activities, tokens, variables, "
            + "timer_jobs, message_subscriptions, signal_subscriptions, incidents, service_tasks, "
            + "process_instances, parallel_gateways, process_definitions RESTART IDENTITY CASCADE");
        String bpmn = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("src/test/files/test-in3-candidates.bpmn")));
        pdId = processDefinitionService.addProcessDefinition(bpmn).getId();
    }

    // ==================== 1. сам артефакт changeset-118 ====================

    /**
     * Миграция доказана ПРИМЕНЕНИЕМ: Liquibase выполняет её на старте контекста, тест только
     * читает состояние. Здесь же — что ограничения именно те, что задуманы, а не «какие-то».
     */
    @Test
    void migration_applied_withPrimaryKeyForeignKeyCheckIndexAndRussianComments() {
        assertThat(tableExists("user_task_candidates")).as("changeset-118 применился").isTrue();

        assertThat(constraintCount("pk_user_task_candidates", "PRIMARY KEY"))
            .as("составной PK (user_task_id, kind, candidate)").isEqualTo(1);

        assertThat(constraintCount("fk_user_task_candidates__user_task_id", "FOREIGN KEY"))
            .as("FK на user_tasks.id").isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT delete_rule FROM information_schema.referential_constraints "
                + "WHERE constraint_name = 'fk_user_task_candidates__user_task_id'", String.class))
            .as("политика проекта — RESTRICT, а не CASCADE (WO-OPS-12 D-1)")
            .isEqualTo("NO ACTION");

        assertThat(constraintCount("ck_user_task_candidates__kind", "CHECK"))
            .as("роль кандидата только USER/GROUP — deny-by-default на уровне БД").isEqualTo(1);

        assertThat(indexColumns("idx_user_task_candidates__kind_candidate"))
            .as("порядок колонок индекса — по нему идёт EXISTS-проба")
            .containsExactly("kind", "candidate", "user_task_id");

        // COMMENT ON по-русски — обязательное требование проекта к новым таблицам.
        assertThat(jdbc.queryForObject(
            "SELECT obj_description('user_task_candidates'::regclass)", String.class))
            .contains("Кандидаты user task");
        assertThat(jdbc.queryForObject(
            "SELECT col_description('user_task_candidates'::regclass, a.attnum) "
                + "FROM pg_attribute a WHERE a.attrelid = 'user_task_candidates'::regclass "
                + "AND a.attname = 'kind'", String.class))
            .as("COMMENT ON COLUMN kind — по-русски")
            .contains("USER");
        assertThat(jdbc.queryForObject(
            "SELECT col_description('user_task_candidates'::regclass, a.attnum) "
                + "FROM pg_attribute a WHERE a.attrelid = 'user_task_candidates'::regclass "
                + "AND a.attname = 'candidate'", String.class))
            .as("COMMENT ON COLUMN candidate — по-русски")
            .contains("Имя кандидата");
    }

    /** CHECK действительно отбивает роль вне USER/GROUP, а не просто записан в каталоге. */
    @Test
    void checkConstraint_rejectsACandidateKindOutsideUserAndGroup() {
        UUID task = startTask("sales", "alice");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM user_tasks WHERE id = ?", Integer.class, task)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO user_task_candidates (user_task_id, kind, candidate) VALUES (?, 'OTHER', 'x')", task))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ==================== 2. индекс действительно обслуживает пробу ====================

    /**
     * План на маленькой таблице всегда будет seq scan — он ничего не говорит о пригодности
     * индекса. Поэтому seq scan выключен, и утверждается ровно одно: планировщик СМОГ построить
     * план на {@code idx_user_task_candidates__kind_candidate}. Это ловит главный риск —
     * перепутанный порядок колонок (например (user_task_id, kind, candidate)), при котором индекс
     * существует, но на пробу «есть ли кандидат с таким именем» не годится.
     */
    @Test
    void candidateIndex_isUsableForTheEqualsProbe() {
        String plan = explainWithSeqScanOff(
            "SELECT user_task_id FROM user_task_candidates "
                + "WHERE kind = 'GROUP' AND candidate IN ('g7', 'g14')");

        assertThat(plan)
            .as("проба кандидата обязана идти по своему индексу")
            .contains("idx_user_task_candidates__kind_candidate");
    }

    // ==================== 3. retention: дети раньше родителя, RESTRICT не мешает ====================

    /**
     * FK — RESTRICT, поэтому retention ОБЯЗАН удалять кандидатов раньше задач. Без этой правки
     * DELETE из user_tasks падал бы на FK-ошибке и retention сломался бы целиком.
     */
    @Test
    void retention_removesCandidatesTogetherWithTheTask() {
        UUID piId = startInstance("sales", "alice");
        UUID taskId = openTaskId(piId);
        assertThat(candidateRows(taskId)).isEqualTo(2);

        // Retention отбирает инстанс, только если у него НЕТ незакрытых activity и user_tasks
        // (RetentionBatchProcessor.selectEligiblePerDefinition) — то есть «инстанс завершён»
        // это ещё не всё, нужно закрыть и саму задачу.
        jdbc.update("UPDATE activities SET completed_at = ? WHERE process_instance_id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(700)), piId);
        jdbc.update("UPDATE user_tasks SET completed_at = ? WHERE process_instance_id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(700)), piId);
        jdbc.update("UPDATE process_instances SET completed_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(600)), piId);

        // Прод-путь целиком: отбор подходящих инстансов + пакетное удаление реальной транзакцией.
        List<UUID> eligible = batchProcessor.findEligibleInstances(Instant.now(), 100);
        assertThat(eligible).contains(piId);
        batchProcessor.deleteInstances(eligible);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM user_tasks WHERE id = ?", Integer.class, taskId)).isZero();
        assertThat(candidateRows(taskId))
            .as("сирот-кандидатов не осталось — FK RESTRINCT не дал бы пройти иначе")
            .isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM process_instances WHERE id = ?", Integer.class, piId)).isZero();
    }

    /** Обход retention (прямой DELETE задачи) обязан падать громко, а не оставлять сирот. */
    @Test
    void deletingATaskWithCandidates_directly_isBlockedByTheForeignKey() {
        UUID piId = startInstance("sales", "alice");
        UUID taskId = openTaskId(piId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_tasks WHERE id = ?", taskId))
            .as("CASCADE снёс бы строки молча — политика проекта запрещает")
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(candidateRows(taskId))
            .as("и ничего не снесено вдогонку").isEqualTo(2);
    }

    // ==================== 4. фильтры на живой PostgreSQL ====================

    /**
     * Тот же вопрос, что и на H2, но на СУБД прод-типа: Hibernate строит EXISTS по диалекту, и
     * отличить «логика спецификации верна» от «диалект собрал не то» на H2 нельзя. Ассерты
     * двусторонние — найдено своё, не найдено чужое.
     */
    @Test
    void candidateFilters_answerTheSameQuestionOnPostgresAsOnH2() {
        UUID aliceTask = openTaskId(startInstance("sales", "alice"));
        UUID bobTask = openTaskId(startInstance("support", "bob"));

        assertThat(idsByCandidateUser("alice")).containsExactly(aliceTask);
        assertThat(idsByCandidateUser("carol")).isEmpty();
        assertThat(idsByCandidateGroup("sales")).containsExactly(aliceTask);
        assertThat(idsByCandidateGroup("sales-east")).isEmpty();

        UiUserEntity member = createUser();
        UserGroupEntity ug = new UserGroupEntity();
        ug.setUserId(member.getId());
        ug.setGroupName("sales");
        userGroupRepository.save(ug);

        UserTaskQuery relatesTo = new UserTaskQuery();
        relatesTo.setPageSize(50);
        relatesTo.setRelatesTo(member.getId());
        assertThat(ids(relatesTo)).containsExactly(aliceTask);
        assertThat(idsByCandidateGroup("support")).containsExactly(bobTask);
    }

    // ==================== helpers ====================

    private UUID startTask(String group, String user) {
        return openTaskId(startInstance(group, user));
    }

    private UUID startInstance(String group, String user) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.getVariables().add(variable("candGroup", group));
        dto.getVariables().add(variable("candUser", user));
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ProcessVariable variable(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(ProcessVariableType.STRING);
        return v;
    }

    private UUID openTaskId(UUID piId) {
        return jdbc.queryForObject(
            "SELECT id FROM user_tasks WHERE process_instance_id = ? AND completed_at IS NULL",
            UUID.class, piId);
    }

    private int candidateRows(UUID taskId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM user_task_candidates WHERE user_task_id = ?", Integer.class, taskId);
    }

    private List<UUID> idsByCandidateUser(String user) {
        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setCandidateUser(user);
        return ids(q);
    }

    private List<UUID> idsByCandidateGroup(String group) {
        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setCandidateGroup(group);
        return ids(q);
    }

    private List<UUID> ids(UserTaskQuery q) {
        return queryService.findUserTasks(q, List.of(pdId)).getData().stream()
            .map(UserTask::getId).toList();
    }

    private UiUserEntity createUser() {
        String username = "in3pg-" + UUID.randomUUID();
        UiUserEntity u = new UiUserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername(username);
        u.setPasswordHash(passwordHasher.hash("secret"));
        u.setFullName(username);
        u.setRole("USER");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        return uiUserRepository.save(u);
    }

    private boolean tableExists(String table) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
            Integer.class, table) > 0;
    }

    private int constraintCount(String name, String type) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints WHERE table_name = 'user_task_candidates' "
                + "AND constraint_name = ? AND constraint_type = ?", Integer.class, name, type);
    }

    private List<String> indexColumns(String index) {
        return jdbc.queryForList(
            "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid "
                + "AND a.attnum = ANY(i.indkey) WHERE i.indexrelid = ?::regclass "
                + "ORDER BY array_position(i.indkey, a.attnum)",
            String.class, index);
    }

    /** EXPLAIN с выключенным seq scan — см. комментарий к тесту: проверяем ПРИГОДНОСТЬ индекса. */
    private String explainWithSeqScanOff(String sql) {
        jdbc.execute("SET enable_seqscan = off");
        try {
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
        } finally {
            jdbc.execute("SET enable_seqscan = on");
        }
    }
}
