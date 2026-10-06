package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.entity.UserGroupEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.db.UserTaskCandidateWriter;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-IN-2, red-team round 1 (sha 22af7705) — the guard-rails the review demanded, one class so
 * that each of them has its own named test and its own visible mutation.
 *
 * <p>The pre-existing classes cover what the query DOES; this one covers what it REFUSES and what
 * it refuses to answer wrongly:
 * <ul>
 *   <li><b>C0.2 / E-IN2-1</b> — {@code candidateUser} был объявлен и не читался, так что запрос
 *       отвечал БОЛЬШЕ, чем спрашивали. Решение CTO 2026-10-06 (вариант B) запрещало фильтр с
 *       явным 400, потому что таблицы кандидатов ещё не было; <b>WO-IN-3 создал её</b>, и фильтр
 *       переехал в {@code UserTaskCandidateQueryIntegrationTests} — теперь он фильтрует.</li>
 *   <li><b>MEDIUM-2</b> — {@code pageIndex=2147483647} overflowed {@code pageIndex*pageSize} and
 *       escaped as a 500 out of Spring Data's {@code PageableUtils}; the endpoint must answer an
 *       empty page (200), never a 500, on caller input.</li>
 *   <li><b>MEDIUM-3</b> — one LIKE per group with no cap made the cost linear in the person's
 *       group count (red-team measurement: 1 group 77 ms, 100 groups 3982 ms on 1M rows). Cap at
 *       20 groups, refused with 400 instead of being executed.</li>
 *   <li><b>LOW-5</b> — the filter answered a WIDER question than the authorization for a group
 *       name containing a space ({@code sa les} collapsed to {@code sales}), and a comma inside a
 *       name made one LIKE pattern match two different groups. Normalize exactly like
 *       {@code AuthorizationService} does and refuse a comma.</li>
 * </ul>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UserTaskQueryGuardrailsIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserTaskCandidateWriter userTaskCandidateWriter;
    @Autowired private UserTaskCandidateRepository userTaskCandidateRepository;
    @Autowired private UiUserRepository uiUserRepository;
    @Autowired private UserGroupRepository userGroupRepository;
    @Autowired private PasswordHasher passwordHasher;

    private UUID pdId;

    @BeforeEach
    void deploy() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-in2-person-query.bpmn"));
        pdId = processDefinitionService.addProcessDefinition(bpmn).getId();
    }

    // ===== C0.2 / E-IN2-1 — candidateUser: ПЕРЕЕХАЛ В WO-IN-3 =====

    /*
     * Отсюда ушли два теста, и это НЕ потеря покрытия, а смена решения CTO:
     *   • candidateUserFilter_isRefusedWith400_notSilentlyIgnored — удалён НАВСЕГДА. 400 был
     *     вариантом (B) из эскалации E-IN2-1 («пока таблицы кандидатов нет — откажи явно»);
     *     WO-IN-3 — это ровно тот вариант (A), и фильтр теперь РАБОТАЕТ. Отказывать в том,
     *     что уже поддержано, — тот же молчаливый дефект, только в другую сторону.
     *   • candidateUserAbsentOrBlank_isNoFilter_notAnError — ПЕРЕЕХАЛ в
     *     UserTaskCandidateQueryIntegrationTests (имя сохранено), потому что на этой фикстуре
     *     (колонка пишется напрямую в user_tasks) кандидата-пользователя уже не существует.
     *     Правило «absent/blank = без фильтра» живо и там, на живом пути создания задачи.
     */

    // ===== MEDIUM-2 — pageIndex must not escape as a 500 =====

    /**
     * RED on the pre-fix code: {@code clampedPage} clamped the SIZE but not the index, so
     * 2147483647×10 overflowed and Spring Data JPA threw
     * {@code InvalidDataAccessApiUsageException: Page offset exceeds Integer.MAX_VALUE}.
     */
    @Transactional
    @Test
    void pageIndexBeyondTheIntOffset_isAnEmptyPage_notAServerError() throws Exception {
        UserTaskEntity a = taskWith("alice", "sales");

        UserTaskQuery q = new UserTaskQuery();
        q.setPageIndex(Integer.MAX_VALUE);
        q.setPageSize(50);

        var page = queryService.findUserTasks(q, List.of(pdId));

        assertThat(page.getData()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1L);
        assertThat(a.getId()).isNotNull();
    }

    // ===== LOW-5 — one group name, one answer, exactly as authorization answers it =====

    /**
     * RED on the pre-fix code: the query deleted every space, so {@code ?candidateGroup=sales}
     * matched a task restricted to the DIFFERENT group {@code sa les} (over-inclusion), while
     * {@code ?candidateGroup=sa les} matched nothing (under-inclusion).
     */
    @Transactional
    @Test
    void candidateGroup_doesNotCollapseSpacesInsideAGroupName() throws Exception {
        UserTaskEntity spaced = taskWith("alice", "sa les");

        assertThat(idsByCandidateGroup("sales")).isEmpty();
        assertThat(idsByCandidateGroup("sa les")).containsExactly(spaced.getId());
    }

    @Transactional
    @Test
    void relatesTo_doesNotCollapseSpacesInsideAGroupName() throws Exception {
        UiUserEntity spacedUser = createUser(uniqueName("spaced"));
        addToGroup(spacedUser.getId(), "sa les");
        UiUserEntity plainUser = createUser(uniqueName("plain"));
        addToGroup(plainUser.getId(), "sales");

        UserTaskEntity spacedTask = taskWith("someone-else", "sa les", "spaced-form");
        UserTaskEntity plainTask = taskWith("someone-else", "sales", "plain-form");

        assertThat(idsByRelatesTo(spacedUser.getId())).containsExactly(spacedTask.getId());
        assertThat(idsByRelatesTo(plainUser.getId())).containsExactly(plainTask.getId());
    }

    /**
     * RED on the pre-fix code: a name carrying the LIST delimiter cannot be told apart from two
     * names, so the pattern {@code ,a,b,} matched the task holding {@code x,a,b,y} — a group the
     * request never named, and one {@code AuthorizationService} does not grant either.
     */
    @Transactional
    @Test
    void candidateGroup_withADelimiterInsideTheName_isRefusedWith400() throws Exception {
        taskWith("alice", "x,a,b,y");

        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setCandidateGroup("a,b");

        assertThatThrownBy(() -> queryService.findUserTasks(q, List.of(pdId)))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("UNSUPPORTED_GROUP_NAME");
            });
    }

    /** The same rule for the group names that come out of {@code user_group}. */
    @Transactional
    @Test
    void relatesTo_personInAGroupWhoseNameCarriesTheDelimiter_isRefusedWith400() throws Exception {
        UiUserEntity user = createUser(uniqueName("comma"));
        addToGroup(user.getId(), "a,b");
        taskWith("alice", "x,a,b,y", "comma-form");

        assertThatThrownBy(() -> queryService.findUserTasks(
            relatesToQuery(user.getId()), List.of(pdId)))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("UNSUPPORTED_GROUP_NAME");
            });
    }

    // ===== MEDIUM-3 — the person's group count is bounded before it reaches SQL =====

    @Transactional
    @Test
    void relatesTo_personInExactly20Groups_isExecuted() throws Exception {
        UiUserEntity user = createUser(uniqueName("twenty"));
        names(20).forEach(g -> addToGroup(user.getId(), g));
        UserTaskEntity task = taskWith("someone-else", "in2g19", "twenty-form");

        assertThat(idsByRelatesTo(user.getId())).containsExactly(task.getId());
    }

    /**
     * RED on the pre-fix code: 21 groups were OR-ed into 21 un-indexed {@code LIKE}s over the
     * whole {@code user_tasks} table — the red-team measured 3982 ms at 100 groups on 1M rows.
     */
    @Transactional
    @Test
    void relatesTo_personInMoreThan20Groups_isRefusedWith400() throws Exception {
        UiUserEntity user = createUser(uniqueName("twentyone"));
        names(21).forEach(g -> addToGroup(user.getId(), g));
        taskWith("alice", "in2g20", "twentyone-form");

        assertThatThrownBy(() -> queryService.findUserTasks(
            relatesToQuery(user.getId()), List.of(pdId)))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("TOO_MANY_PERSON_GROUPS");
                assertThat(api.getParams()).containsEntry("groups", 21);
            });
    }

    // ==================== helpers ====================

    private static List<String> names(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> "in2g" + i).toList();
    }

    private static String uniqueName(String prefix) {
        return "in2-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UserTaskQuery relatesToQuery(UUID userId) {
        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setRelatesTo(userId);
        return q;
    }

    private List<UUID> idsByCandidateGroup(String group) {
        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setCandidateGroup(group);
        return ids(q);
    }

    private List<UUID> idsByRelatesTo(UUID userId) {
        return ids(relatesToQuery(userId));
    }

    private List<UUID> ids(UserTaskQuery q) {
        return queryService.findUserTasks(q, List.of(pdId)).getData().stream()
            .map(UserTask::getId).toList();
    }

    private UserTaskEntity taskWith(String assignee, String candidateGroups) throws Exception {
        return taskWith(assignee, candidateGroups, null);
    }

    /**
     * WO-IN-3: фикстура пишет кандидатов ТЕМ ЖЕ рабочим кодом, что и живой путь
     * ({@code UserTaskCandidateWriter}), а не только в колонку. Иначе фикстура описывала бы
     * состояние, которого движок создать не может: строка без строк-кандидатов — это ровно то,
     * что получается, когда писатель молча сломался.
     */
    private UserTaskEntity taskWith(String assignee, String candidateGroups, String formKey) throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        UserTaskEntity task = userTaskRepository.findByProcessInstanceId(piId).stream()
            .filter(t -> t.getCompletedAt() == null)
            .findFirst().orElseThrow();
        task.setAssignee(assignee);
        task.setCandidateGroups(candidateGroups);
        task.setFormKey(formKey);
        UserTaskEntity saved = userTaskRepository.save(task);
        // СНАЧАЛА снести то, что написал живой путь (в BPMN этой фикстуры candidateGroups="sales"),
        // и только потом записать набор фикстуры — иначе задача получила бы ОБЕ группы и
        // перестала быть «задачей, у которой единственная группа sa les».
        userTaskCandidateRepository.deleteAll(
            userTaskCandidateRepository.findByUserTaskId(saved.getId()));
        userTaskCandidateWriter.writeCandidates(saved.getId(), candidateGroups, null);
        return saved;
    }

    private UiUserEntity createUser(String username) {
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
