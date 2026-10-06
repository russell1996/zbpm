package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UserGroupEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
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
 * WO-IN-2 criteria 2 (sorting) and 3 ({@code bpmnElementId} / {@code formKey} filters).
 *
 * <p>POF (V3): with the DTO fields present but no implementation, every sorting test sees the
 * old fixed {@code createdAt DESC} order and every filter test sees every task.
 *
 * <p>The tie-breaker test is the honest one for "stable secondary key": it stamps THREE rows
 * with the SAME {@code createdAt}, inserted in DESCENDING id order. Without an {@code id}
 * tie-breaker the database returns them in whatever order the plan produces (insertion order
 * here), so the expected ascending-id order fails; with it, the order is defined.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UserTaskSortAndElementFilterIntegrationTests {

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
        // WO-IN-3: кандидаты — ТОТ ЖЕ состав, что даёт живой путь, и пишутся они тем же
        // рабочим кодом (UserTaskCandidateWriter). Сначала сносим то, что написала живая
        // активация (в этой BPMN candidateGroups="sales"), иначе задача получила бы ОБА набора
        // и перестала описывать ту единственную группу, ради которой тест написан.
        UserTaskEntity saved = userTaskRepository.save(task);
        userTaskCandidateRepository.deleteAll(
            userTaskCandidateRepository.findByUserTaskId(saved.getId()));
        userTaskCandidateWriter.writeCandidates(saved.getId(), candidateGroups, null);
        return saved;
    }

    private List<UUID> ids(UserTaskQuery q) {
        return queryService.findUserTasks(q, List.of(pdId)).getData().stream()
            .map(UserTask::getId).toList();
    }

    // ===== criterion 3: bpmnElementId / formKey filters =====

    @Transactional
    @Test
    void bpmnElementIdFilter_returnsOnlyThatElement() throws Exception {
        UserTaskEntity a = taskWith("alice", null, "form-a");
        UserTaskEntity b = taskWith("bob", null, "form-b");
        // the element id of both rows is the same in this one-element model, so the filter must
        // keep BOTH — that is the point: it narrows nothing here instead of being ignored.
        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setBpmnElementId("reviewTask");
        assertThat(ids(q)).containsExactlyInAnyOrder(a.getId(), b.getId());

        UserTaskQuery unknown = new UserTaskQuery();
        unknown.setPageSize(50);
        unknown.setBpmnElementId("noSuchElement");
        assertThat(ids(unknown)).isEmpty();
    }

    @Transactional
    @Test
    void formKeyFilter_returnsOnlyTasksWithThatForm() throws Exception {
        UserTaskEntity a = taskWith("alice", null, "form-a");
        taskWith("bob", null, "form-b");
        taskWith("carol", null, null);

        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setFormKey("form-a");
        assertThat(ids(q)).containsExactly(a.getId());

        UserTaskQuery none = new UserTaskQuery();
        none.setPageSize(50);
        none.setFormKey("form-missing");
        assertThat(ids(none)).isEmpty();
    }

    @Transactional
    @Test
    void elementFilters_combineWithEachOtherAndWithTheOldOnes() throws Exception {
        UserTaskEntity aliceFormA = taskWith("alice", "sales", "form-a");
        taskWith("bob", "sales", "form-b");
        UserTaskEntity aliceAlsoFormA = taskWith("alice", null, "form-a");

        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setAssignee("alice");
        q.setBpmnElementId("reviewTask");
        q.setFormKey("form-a");
        assertThat(ids(q)).containsExactlyInAnyOrder(aliceFormA.getId(), aliceAlsoFormA.getId());
    }

    @Transactional
    @Test
    void elementFilters_blank_isTreatedAsAbsent() throws Exception {
        taskWith("alice", null, "form-a");
        taskWith("bob", null, "form-b");

        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setBpmnElementId("  ");
        q.setFormKey("");
        assertThat(ids(q)).hasSize(2);
    }

    // ===== criterion 2: sorting, white list, stable secondary key =====

    @Transactional
    @Test
    void sortBy_assignee_ordersAscendingAndDescending() throws Exception {
        UserTaskEntity carol = taskWith("carol", null, null);
        UserTaskEntity alice = taskWith("alice", null, null);
        UserTaskEntity bob = taskWith("bob", null, null);

        UserTaskQuery asc = new UserTaskQuery();
        asc.setPageSize(50);
        asc.setSortBy("assignee");
        asc.setSortOrder("asc");
        assertThat(ids(asc)).containsExactly(alice.getId(), bob.getId(), carol.getId());

        UserTaskQuery desc = new UserTaskQuery();
        desc.setPageSize(50);
        desc.setSortBy("assignee");
        desc.setSortOrder("desc");
        assertThat(ids(desc)).containsExactly(carol.getId(), bob.getId(), alice.getId());
    }

    @Transactional
    @Test
    void sortBy_whitelistedFieldsAreAccepted() throws Exception {
        taskWith("alice", null, null);
        for (String field : List.of("createdAt", "completedAt", "priority", "dueDate",
                "assignee", "bpmnElementId", "formKey", "id")) {
            UserTaskQuery q = new UserTaskQuery();
            q.setPageSize(50);
            q.setSortBy(field);
            q.setSortOrder("asc");
            assertThat(ids(q)).hasSize(1);
        }
    }

    @Transactional
    @Test
    void sortOrder_withoutSortBy_appliesToTheDefaultColumn() throws Exception {
        // @verifier round 2, finding 6: the javadoc claimed sortOrder is read ONLY together with
        // sortBy, but resolveSort reads it unconditionally. Decision: honour it — `?sortOrder=asc`
        // alone means "createdAt ascending", which is coherent and matches the default's direction
        // switch. This test pins that decision so the two can never disagree again.
        UserTaskEntity older = taskWith("alice", null, null);
        older.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        userTaskRepository.save(older);
        UserTaskEntity newer = taskWith("bob", null, null);
        newer.setCreatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        userTaskRepository.save(newer);

        UserTaskQuery asc = new UserTaskQuery();
        asc.setPageSize(50);
        asc.setSortOrder("asc");
        assertThat(ids(asc)).as("oldest first").containsExactly(older.getId(), newer.getId());

        // absent sortOrder keeps the historical DESC default
        assertThat(ids(new UserTaskQuery())).as("newest first")
            .containsExactly(newer.getId(), older.getId());
    }

    @Transactional
    @Test
    void sortBy_unknownField_isRejectedNotInterpolated() throws Exception {
        taskWith("alice", null, null);
        UserTaskQuery q = new UserTaskQuery();
        q.setSortBy("candidateGroups; drop table user_tasks --");
        q.setSortOrder("asc");

        assertThatThrownBy(() -> ids(q))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("UNSUPPORTED_SORT_FIELD");
                assertThat(api.getParams()).containsKey("field");
            });
    }

    @Transactional
    @Test
    void sortBy_unknownOrder_isRejected() throws Exception {
        taskWith("alice", null, null);
        UserTaskQuery q = new UserTaskQuery();
        q.setSortBy("assignee");
        q.setSortOrder("sideways");

        assertThatThrownBy(() -> ids(q))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("UNSUPPORTED_SORT_ORDER"));
    }

    /** Three rows with the SAME createdAt, inserted in descending id order. */
    @Transactional
    @Test
    void sort_alwaysEndsWithIdTieBreaker_soPagesDoNotDrift() throws Exception {
        Instant same = Instant.parse("2026-01-01T00:00:00Z");
        List<UserTaskEntity> insertedDesc = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UserTaskEntity t = taskWith(null, null, null);
            t.setCreatedAt(same);
            insertedDesc.add(0, userTaskRepository.save(t));
        }
        List<UUID> idsDesc = insertedDesc.stream().map(UserTaskEntity::getId).toList();

        UserTaskQuery asc = new UserTaskQuery();
        asc.setPageSize(50);
        asc.setSortBy("createdAt");
        asc.setSortOrder("asc");
        assertThat(ids(asc)).containsExactlyElementsOf(sortedIds(idsDesc));

        UserTaskQuery desc = new UserTaskQuery();
        desc.setPageSize(50);
        desc.setSortBy("createdAt");
        desc.setSortOrder("desc");
        List<UUID> expectedDesc = new java.util.ArrayList<>(sortedIds(idsDesc));
        java.util.Collections.reverse(expectedDesc);
        assertThat(ids(desc)).containsExactlyElementsOf(expectedDesc);
    }

    /**
     * Order by the 16 uuid BYTES, not by {@code UUID.compareTo}. The database orders uuid
     * unsigned; {@code UUID.compareTo} compares the bits as SIGNED longs, so a row whose most
     * significant bit is set sorts the other way round in Java than in SQL. Comparing the
     * canonical hex string reproduces the database's unsigned byte order on both H2 and PostgreSQL.
     */
    private static List<UUID> sortedIds(List<UUID> input) {
        return input.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
    }

    // ===== the person side of criterion 1/3: user + group fixtures =====

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

    @Transactional
    @Test
    void relatesTo_returnsTasksWhereTheUserIsAssigneeOrCandidateOfHisGroup() throws Exception {
        UiUserEntity alice = createUser("in2Alice");
        addToGroup(alice.getId(), "sales");

        UserTaskEntity asAssignee = taskWith("in2Alice", null, null);
        UserTaskEntity viaGroup = taskWith("bob", "sales", null);
        UserTaskEntity viaSecondGroup = taskWith("bob", "support,in2GroupX", null);
        addToGroup(alice.getId(), "in2GroupX");
        UserTaskEntity bothRoles = taskWith("in2Alice", "sales", null);
        UserTaskEntity unrelated = taskWith("bob", "legal", null);

        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setRelatesTo(alice.getId());

        List<UUID> found = ids(q);
        assertThat(found).containsExactlyInAnyOrder(
            asAssignee.getId(), viaGroup.getId(), viaSecondGroup.getId(), bothRoles.getId());
        assertThat(found).doesNotContain(unrelated.getId());
    }

    @Transactional
    @Test
    void relatesTo_isPagedInOneQuery_withoutDuplicates() throws Exception {
        UiUserEntity alice = createUser("in2Alice");
        addToGroup(alice.getId(), "sales");
        for (int i = 0; i < 5; i++) {
            taskWith("in2Alice", "sales", null); // BOTH roles on every row
        }
        for (int i = 0; i < 5; i++) {
            taskWith("bob", "sales", null);
        }
        // noise that must NOT be in the merged set — without the filter every row is returned
        UserTaskEntity noise = taskWith("dave", "legal", null);
        taskWith(null, null, null);

        UserTaskQuery page0 = new UserTaskQuery();
        page0.setPageSize(4);
        page0.setPageIndex(0);
        page0.setRelatesTo(alice.getId());
        UserTaskQuery all = new UserTaskQuery();
        all.setPageSize(100);
        all.setRelatesTo(alice.getId());

        List<UUID> firstPage = ids(page0);
        List<UUID> everything = ids(all);
        assertThat(everything).hasSize(10).doesNotHaveDuplicates().doesNotContain(noise.getId());
        assertThat(firstPage).hasSize(4).doesNotHaveDuplicates();
        assertThat(firstPage).isSubsetOf(everything);
    }

    @Transactional
    @Test
    void relatesTo_matchesWholeTokens_only() throws Exception {
        // @verifier finding 3: the relatesTo fixture had no "name is a prefix of another name" pair,
        // so a token-exactness regression inside relatesTo would stay GREEN. Mutation: remove the
        // comma delimiters from the shared matcher → a member of `sales` would receive the tasks
        // of `sales-east`, which they are not a candidate of (and cannot even claim).
        UiUserEntity alice = createUser("in2Alice");
        addToGroup(alice.getId(), "in2Sales");
        UserTaskEntity salesEast = taskWith("bob", "in2Sales-east", null);
        UserTaskEntity salesOnly = taskWith("bob", "in2Sales", null);

        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setRelatesTo(alice.getId());

        List<UUID> found = ids(q);
        assertThat(found).containsExactly(salesOnly.getId());
        assertThat(found).doesNotContain(salesEast.getId());
    }

    /**
     * @verifier finding 4 (untested {@code ", "} branch) and finding 6 (space BEFORE the comma):
     * {@code AuthorizationService.parseCandidateGroups} trims every element, so a hand-written
     * {@code candidateGroups="sales , east"} makes the user a candidate of {@code east} on the
     * authorization side. The query must answer the SAME question, otherwise a person misses a task
     * they may really claim.
     */
    @Transactional
    @Test
    void relatesTo_toleratesSpacesAroundTheCommas() throws Exception {
        UiUserEntity alice = createUser("in2Alice");
        addToGroup(alice.getId(), "east");
        UserTaskEntity spaced = taskWith("bob", "sales , east", null);

        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setRelatesTo(alice.getId());

        assertThat(ids(q)).containsExactly(spaced.getId());
    }

    @Transactional
    @Test
    void candidateGroupFilter_toleratesSpacesAroundTheCommas() throws Exception {
        UserTaskEntity spaced = taskWith("bob", "sales , east", null);
        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setCandidateGroup("east");
        assertThat(ids(q)).containsExactly(spaced.getId());
    }

    @Transactional
    @Test
    void relatesTo_unknownUser_returnsNothing() throws Exception {
        taskWith("alice", "sales", null);

        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setRelatesTo(UUID.randomUUID());

        assertThat(ids(q)).isEmpty();
    }

    @Transactional
    @Test
    void relatesTo_respectsAllowedPdIds_soForeignDefinitionsStayInvisible() throws Exception {
        UiUserEntity alice = createUser("in2Alice");
        UserTaskEntity mine = taskWith("in2Alice", null, null);

        // a SECOND definition the caller has no grant on — the process id is changed as well,
        // because addProcessDefinition de-duplicates by content and would hand back the SAME id
        String bpmn = Files.readString(Paths.get("src/test/files/test-in2-person-query.bpmn"))
            .replace("in2-person-query", "in2-person-query-foreign");
        UUID foreignPd = processDefinitionService.addProcessDefinition(bpmn).getId();
        assertThat(foreignPd).isNotEqualTo(pdId);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(foreignPd);
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        UserTaskEntity foreign = userTaskRepository.findByProcessInstanceId(piId).stream()
            .filter(t -> t.getCompletedAt() == null).findFirst().orElseThrow();
        foreign.setAssignee("in2Alice");
        userTaskRepository.save(foreign);

        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(50);
        q.setRelatesTo(alice.getId());

        List<UUID> visible = queryService.findUserTasks(q, List.of(pdId)).getData().stream()
            .map(UserTask::getId).toList();
        assertThat(visible).containsExactly(mine.getId());
        assertThat(visible).doesNotContain(foreign.getId());
    }
}