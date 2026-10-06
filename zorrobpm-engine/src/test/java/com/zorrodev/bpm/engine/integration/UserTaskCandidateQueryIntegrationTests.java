package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.entity.UserGroupEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateKind;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.CandidateGroups;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-IN-3: нормализованная таблица кандидатов — {@code ?candidateUser=} начинает работать,
 * а {@code candidateGroup}/{@code relatesTo} читают её вместо колонки.
 *
 * <p>ВСЕ фикстуры here идут через ЖИВОЙ путь: инстанс реально стартует, обработчик user task
 * реально пишет строки-кандидаты, запрос идёт через {@code QueryService}. Ни одной строки
 * кандидатов не вставляется вручную — иначе тест доказал бы не путь создания задачи, а собственную
 * фикстуру (P-11: «поле в entity ≠ оно заполняется»).
 *
 * <p>Кандидаты в BPMN — ПЕРЕМЕННЫЕ ({@code ${candGroup}} / {@code ${candUser}}), а не литералы:
 * один процесс даёт два РАЗНЫХ набора кандидатов в двух инстансах, и «нашла своё, не нашла чужое»
 * становится настоящей разностью, а не совпадением всего подряд.
 *
 * <p>Ассерты двусторонние (P-67): «найдено ровно это И не найдено то» — иначе тест прошёл бы и с
 * пустым результатом, и с «всё подряд».
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UserTaskCandidateQueryIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserTaskCandidateRepository userTaskCandidateRepository;
    @Autowired private UiUserRepository uiUserRepository;
    @Autowired private UserGroupRepository userGroupRepository;
    @Autowired private PasswordHasher passwordHasher;

    private UUID pdId;

    @BeforeEach
    void deploy() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-in3-candidates.bpmn"));
        pdId = processDefinitionService.addProcessDefinition(bpmn).getId();
    }

    // ==================== candidateUser: фильтр, который наконец работает ====================

    /**
     * RED на коде до WO-IN-3: {@code candidateUser} отклонялся 400
     * ({@code UNSUPPORTED_CANDIDATE_USER_FILTER}) — хранить значение фильтра было негде.
     * GREEN: задача находится по кандидату-пользователю, ЧУЖАЯ — нет.
     */
    @Transactional
    @Test
    void candidateUserFilter_returnsOnlyTheTasksWhereThatUserIsACandidateUser() throws Exception {
        UUID aliceTask = startTaskWithCandidates("sales", "alice");
        UUID bobTask = startTaskWithCandidates("support", "bob");

        assertThat(idsByCandidateUser("alice")).containsExactly(aliceTask);
        assertThat(idsByCandidateUser("bob")).containsExactly(bobTask);
    }

    /** Человек, не кандидат нигде, получает ПУСТОЙ список, а не «все задачи». */
    @Transactional
    @Test
    void candidateUserFilter_findsNothingForAUserWhoIsNotACandidate() throws Exception {
        startTaskWithCandidates("sales", "alice");
        startTaskWithCandidates("support", "bob");

        assertThat(idsByCandidateUser("carol")).isEmpty();
    }

    /** Правило проекта «absent or blank = no filter» должно и дальше работать. */
    @Transactional
    @Test
    void candidateUserAbsentOrBlank_isNoFilter_notAnError() throws Exception {
        UUID a = startTaskWithCandidates("sales", "alice");
        UUID b = startTaskWithCandidates("support", "bob");

        UserTaskQuery absent = new UserTaskQuery();
        absent.setPageSize(50);
        UserTaskQuery blank = new UserTaskQuery();
        blank.setPageSize(50);
        blank.setCandidateUser("   ");

        assertThat(ids(absent)).containsExactlyInAnyOrder(a, b);
        assertThat(ids(blank)).containsExactlyInAnyOrder(a, b);
    }

    /**
     * Кандидат-ГРУППА и кандидат-ПОЛЬЗОВАТЕЛЬ — разные роли: фильтр по пользователю не должен
     * цеплять группу с тем же именем, иначе «кандидат alice» означал бы и «в группе alice».
     */
    @Transactional
    @Test
    void candidateUserFilter_doesNotMatchAGroupThatHappensToHaveTheSameName() throws Exception {
        UUID groupOnly = startTaskWithCandidates("dana", null);

        assertThat(idsByCandidateUser("dana")).isEmpty();
        assertThat(idsByCandidateGroup("dana")).containsExactly(groupOnly);
    }

    // ==================== candidateGroup / relatesTo: семантика не поехала ====================

    /**
     * Группы остаются токен-точными и после перехода на таблицу: у трёх РАЗНЫХ названий каждый
     * запрос возвращает ровно свою задачу — «sales» не цепляет «sales-east», а «sa les» не
     * схлопывается в «sales». Та же семантика, что у {@code AuthorizationService}
     * (см. {@code CandidateGroups}): пробел ВНУТРИ имени — часть имени, а не мусор.
     *
     * <p>Регресс-guard, а не POF: на коде до WO-IN-3 этот тест тоже зелёный (старый LIKE уже был
     * токен-точным после WO-IN-2 LOW-5). Он защищает от того, чтобы перенос на таблицу СЛОМАЛ
     * выравнивание с авторизацией.
     */
    @Transactional
    @Test
    void candidateGroupFilter_staysTokenExactAfterTheSwitchToTheTable() throws Exception {
        UUID sales = startTaskWithCandidates("sales", null);
        UUID salesEast = startTaskWithCandidates("sales-east", null);
        UUID spaced = startTaskWithCandidates("sa les", null);

        assertThat(idsByCandidateGroup("sales")).containsExactly(sales);
        assertThat(idsByCandidateGroup("sales-east")).containsExactly(salesEast);
        assertThat(idsByCandidateGroup("sa les")).containsExactly(spaced);
    }

    /** relatesTo: человек видит задачи своей группы. Регресс-guard на смену читателя. */
    @Transactional
    @Test
    void relatesTo_matchesThePersonsGroupsThroughTheCandidatesTable() throws Exception {
        UUID salesTask = startTaskWithCandidates("sales", "alice");
        startTaskWithCandidates("support", "bob");

        UiUserEntity member = createUser();
        addToGroup(member.getId(), "sales");

        assertThat(idsByRelatesTo(member.getId())).containsExactly(salesTask);
    }

    /** Ни username, ни групп — fail-closed (пусто), а не «see all». */
    @Transactional
    @Test
    void relatesTo_personWithNeitherUsernameNorGroups_staysFailClosed() throws Exception {
        startTaskWithCandidates("sales", "alice");

        UiUserEntity ghost = createUser();

        assertThat(idsByRelatesTo(ghost.getId())).isEmpty();
    }

    // ==================== писатель на живом пути: что реально лежит в таблице ====================

    /**
     * Прямое доказательство, что писатель стоит на ЖИВОМ пути, а роли не перепутаны: обе роли
     * пишутся, обе нормализуются. Без строк с ролью USER тесты фильтра выше были бы зелёными на
     * пустом результате, поэтому этот ассерт — тот самый, который ломается, если убрать вызов
     * писателя из {@code UserTaskDbOperationsImpl.createUserTask}.
     */
    @Transactional
    @Test
    void livePath_persistsGroupAndUserCandidatesWithTheRightRoles() {
        UUID task = startTaskWithCandidates("sales,east", "alice,bob");

        assertThat(candidateLabels(task))
            .containsExactlyInAnyOrder("GROUP:sales", "GROUP:east", "USER:alice", "USER:bob");
    }

    /**
     * Нормализация на записи — та же, что у авторизации: пробелы у запятых не часть имени.
     * «sales , east» это ДВЕ группы, а не одна с пробелами (иначе «кто кандидат?» и «что за
     * группа?» отвечали бы разными вопросами — ровно тот дрейф, ради которого живёт
     * {@code CandidateGroups}).
     */
    @Transactional
    @Test
    void livePath_trimsGroupsAndUsersLikeTheAuthorizationSide() {
        UUID task = startTaskWithCandidates(" sales , east ", " alice , bob ");

        assertThat(candidateLabels(task))
            .containsExactlyInAnyOrder("GROUP:sales", "GROUP:east", "USER:alice", "USER:bob");
    }

    /** Мусор в списке не превращается в кандидата: пустые элементы выбрасываются разбором. */
    @Transactional
    @Test
    void livePath_dropsBlankEntriesFromTheCandidateLists() {
        UUID task = startTaskWithCandidates("sales,,  ,east", "alice,");

        assertThat(candidateLabels(task))
            .containsExactlyInAnyOrder("GROUP:sales", "GROUP:east", "USER:alice");
    }

    // ==================== согласованность колонка ↔ таблица (критерий 8) ====================

    /**
     * ГЛАВНАЯ проверка согласованности, и единственная, которая ловит будущее расхождение: для
     * каждой задачи, созданной ЖИВЫМ обработчиком, разобранная колонка и GROUP-строки таблицы
     * обязаны называть ОДИН И ТОТ ЖЕ набор.
     *
     * <p>Почему именно эта форма. Два теста рядом ({@code …followsTheTableNotTheLegacyColumn},
     * {@code …findsTheTableRowEvenWhenTheLegacyColumnIsEmpty}) намеренно СОЗДАЮТ расхождение и
     * проверяют, что читатель его игнорирует — то есть доказывают «миграция прошла и писатель
     * пишет», но по определению не могут поймать будущую расхождность (они закрепляют философию
     * «таблица — истина»). Бэкфилл-PgIT проверяет согласованность один раз, на момент применения
     * changeset'а. Ловит расхождение только этот тест: если писатель забудет группу, или разбор
     * разойдётся с разбором авторизации, или колонка перестанет совпадать с таблицей — здесь
     * упадёт сравнение множеств, а не «где-то в проде кто-то не увидит своей задачи».
     *
     * <p>Наборы подобраны так, чтобы проверить границы: обычный список, список с пробелами у
     * запятых (именно там разбор мог бы разойтись) и пустой список (там最容易 «забыть» запись).
     */
    @Transactional
    @Test
    void livePath_legacyColumnAndCandidateTableNameTheSameGroups() {
        UUID plain = startTaskWithCandidates("sales", "alice");
        UUID spaced = startTaskWithCandidates(" sales , east ", "bob");
        UUID none = startTaskWithCandidates("", null);

        for (UUID taskId : List.of(plain, spaced, none)) {
            assertThat(groupCandidateNames(taskId))
                .as("задача %s: колонка «%s» разбирается в %s — таблица обязана содержать ровно это",
                    taskId, legacyGroups(taskId), CandidateGroups.parse(legacyGroups(taskId)))
                .containsExactlyInAnyOrderElementsOf(CandidateGroups.parse(legacyGroups(taskId)));
        }
    }

    // ==================== читатель читает ТАБЛИЦУ, а не колонку ====================

    /**
     * Расхождение «колонка говорит одно, таблица — другое», и проверяется Обе стороны.
     *
     * <p>Утверждение, которое должно упасть при мутации «вернуть чтение колонки»: строки
     * кандидатов удалены (как будто писатель не отработал или бэкфилла не было), а колонка
     продолжает называть группу — и фильтр всё равно НЕ находит задачу. Считать по колонке
     * здесь нельзя: тогда «миграция прошла, писатель молча перестал писать» выглядел бы как
     * полностью рабочая фича.
     */
    @Transactional
    @Test
    void candidateGroupFilter_followsTheTableNotTheLegacyColumn() {
        UUID task = startTaskWithCandidates("sales", null);
        assertThat(idsByCandidateGroup("sales")).containsExactly(task);

        userTaskCandidateRepository.deleteAll(userTaskCandidateRepository.findByUserTaskId(task));

        assertThat(legacyGroups(task))
            .as("легаси-колонка продолжает называть группу — стерта только таблица")
            .isEqualTo("sales");
        assertThat(idsByCandidateGroup("sales")).isEmpty();
    }

    /** Обратная половина: строка в ТАБЛИЦЕ находится, даже когда колонка пуста. */
    @Transactional
    @Test
    void candidateGroupFilter_findsTheTableRowEvenWhenTheLegacyColumnIsEmpty() {
        UUID task = startTaskWithCandidates("sales", null);

        UserTaskEntity row = userTaskRepository.findById(task).orElseThrow();
        row.setCandidateGroups(null);
        userTaskRepository.save(row);

        assertThat(legacyGroups(task)).isNull();
        assertThat(idsByCandidateGroup("sales")).containsExactly(task);
    }

    // ==================== helpers ====================

    /** ЖИВОЙ путь: старт инстанса с кандидатами в переменных — строки пишет обработчик. */
    private UUID startTaskWithCandidates(String group, String user) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.getVariables().add(variable("candGroup", group));
        dto.getVariables().add(variable("candUser", user == null ? "" : user));
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        return userTaskRepository.findByProcessInstanceId(piId).stream()
            .filter(t -> t.getCompletedAt() == null)
            .findFirst().orElseThrow().getId();
    }

    private ProcessVariable variable(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(ProcessVariableType.STRING);
        return v;
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

    private List<UUID> idsByRelatesTo(UUID userId) {
        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setRelatesTo(userId);
        return ids(q);
    }

    private List<UUID> ids(UserTaskQuery q) {
        return queryService.findUserTasks(q, List.of(pdId)).getData().stream()
            .map(UserTask::getId).toList();
    }

    /** «РОЛЬ:имя» по строкам-кандидатам задачи — читается напрямую из таблицы. */
    private List<String> candidateLabels(UUID taskId) {
        return userTaskCandidateRepository.findByUserTaskId(taskId).stream()
            .map(row -> row.getKind() + ":" + row.getCandidate())
            .toList();
    }

    private String legacyGroups(UUID taskId) {
        return userTaskRepository.findById(taskId).orElseThrow().getCandidateGroups();
    }

    /** Только GROUP-строки таблицы для задачи — то, с чем сравнивается разобранная колонка. */
    private List<String> groupCandidateNames(UUID taskId) {
        return userTaskCandidateRepository.findByUserTaskId(taskId).stream()
            .filter(row -> row.getKind() == UserTaskCandidateKind.GROUP)
            .map(UserTaskCandidateEntity::getCandidate)
            .toList();
    }

    private UiUserEntity createUser() {
        String username = "in3-u-" + UUID.randomUUID();
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

    private void addToGroup(UUID userId, String group) {
        UserGroupEntity ug = new UserGroupEntity();
        ug.setUserId(userId);
        ug.setGroupName(group);
        userGroupRepository.save(ug);
    }
}
