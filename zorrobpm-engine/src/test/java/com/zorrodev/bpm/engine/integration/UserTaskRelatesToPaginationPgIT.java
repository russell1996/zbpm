package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.entity.UserGroupEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.db.UserTaskCandidateWriter;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-IN-2 criterion 1 on a REAL PostgreSQL — the pagination of the MERGED person filter.
 *
 * <p>H2 is not enough here on purpose: the whole point is a single ORDER BY + LIMIT/OFFSET over an
 * OR-ed predicate, and the failure mode being ruled out (the pre-WO way of merging several
 * independently paged queries in the JVM) produces duplicates and gaps only when the database
 * really pages. H2's plans differ, PostgreSQL's is the production one (V11, P-17/P-22).
 *
 * <p>Fixture: 25 open tasks in ONE process definition, ALL concerning one person through
 * overlapping roles — 8 by assignee only, 8 by group G1 only, 5 by group G2 only, 4 by BOTH
 * assignee and a group (the duplication trap: a JOIN-based implementation would hand those 4 out
 * twice per page), plus one noise task in a group the person does NOT belong to. Three pages of
 * 10 must cover all 25 exactly once.
 *
 * <p>All 25 rows are stamped with the SAME {@code createdAt}, which is what makes the tie-breaker
 * observable on PostgreSQL instead of theoretical: with a non-unique sort key the planner is free
 * to order the tied rows any way it likes (heap order in practice), so "page 0 == the ten
 * smallest ids" holds only because {@code ORDER BY created_at, id} says so. Without the
 * tie-breaker the expectation below fails.
 *
 * <p>Only TWO of the WO's three roles exist as data in this schema — there is no
 * {@code user_tasks.candidate_users} column at all (escalation E-IN2-1), so the candidate-USER
 * role has nothing to match and the overlaps here are assignee × group and group × group.
 */
public class UserTaskRelatesToPaginationPgIT extends PostgresIT {

    private static final int TASKS = 25;
    private static final int PAGE = 10;
    /** Every row shares this instant — the sort key is deliberately NOT unique. */
    private static final Instant SHARED_CREATED_AT = Instant.parse("2026-02-02T00:00:00Z");

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserTaskCandidateWriter userTaskCandidateWriter;
    @Autowired private UiUserRepository uiUserRepository;
    @Autowired private UserGroupRepository userGroupRepository;
    @Autowired private PasswordHasher passwordHasher;

    private UUID pdId;
    private UUID personId;
    private String username;
    private List<UUID> expected;

    @BeforeEach
    void setUp() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-in2-person-query.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        pdId = model.getId();

        // unique per test method — ui_users.username is UNIQUE and this DB is shared between PG classes
        username = "in2PgPerson-" + UUID.randomUUID().toString().substring(0, 8);
        UiUserEntity person = new UiUserEntity();
        person.setId(UUID.randomUUID());
        person.setUsername(username);
        person.setPasswordHash(passwordHasher.hash("secret"));
        person.setFullName(username);
        person.setRole("USER");
        person.setActive(true);
        person.setCreatedAt(Instant.now());
        person.setUpdatedAt(Instant.now());
        uiUserRepository.save(person);
        personId = person.getId();
        for (String group : List.of(group1(), group2())) {
            UserGroupEntity ug = new UserGroupEntity();
            ug.setUserId(personId);
            ug.setGroupName(group);
            userGroupRepository.save(ug);
        }

        expected = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            expected.add(task(username, null));                       // assignee only
        }
        for (int i = 0; i < 8; i++) {
            expected.add(task("in2PgSomeoneElse", group1()));         // group 1 only
        }
        for (int i = 0; i < 5; i++) {
            expected.add(task("in2PgSomeoneElse", "other," + group2())); // group 2, second in the list
        }
        for (int i = 0; i < 4; i++) {
            expected.add(task(username, group1() + "," + group2()));  // BOTH roles — the JOIN trap
        }
        assertThat(expected).hasSize(TASKS).doesNotHaveDuplicates();

        // noise: must never appear through relatesTo
        for (int n = 0; n < 3; n++) {
            task("in2PgStranger", "in2PgForeign");
        }
        task(null, null);
    }

    private String group1() {
        return "in2PgG1-" + personId.toString().substring(0, 8);
    }

    private String group2() {
        return "in2PgG2-" + personId.toString().substring(0, 8);
    }

    /** Starts an instance, stamps assignee / candidateGroups and a SHARED createdAt on its row. */
    private UUID task(String assignee, String candidateGroups) throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        UserTaskEntity row = userTaskRepository.findByProcessInstanceId(piId).stream()
            .filter(t -> t.getCompletedAt() == null)
            .findFirst().orElseThrow();
        row.setAssignee(assignee);
        row.setCandidateGroups(candidateGroups);
        row.setCreatedAt(SHARED_CREATED_AT);
        UUID taskId = userTaskRepository.save(row).getId();
        // WO-IN-3: relatesTo читает таблицу кандидатов, поэтому фикстура обязана писать её тем же
        // рабочим кодом, что и движок. Стирать нечего: в BPMN этой фикстуры кандидатов нет.
        userTaskCandidateWriter.writeCandidates(taskId, candidateGroups, null);
        return taskId;
    }

    private PagedDataDTO<UserTask> page(int index) {
        UserTaskQuery q = new UserTaskQuery();
        q.setRelatesTo(personId);
        q.setPageIndex(index);
        q.setPageSize(PAGE);
        q.setSortBy("createdAt");
        q.setSortOrder("asc");
        return queryService.findUserTasks(q, List.of(pdId));
    }

    private List<UUID> idsOf(PagedDataDTO<UserTask> dto) {
        return dto.getData().stream().map(UserTask::getId).toList();
    }

    /** PostgreSQL orders uuid UNSIGNED (byte order); UUID.compareTo is signed — compare hex. */
    private static List<UUID> byDatabaseIdOrder(List<UUID> ids) {
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    @Test
    void pagedMergedResult_coversEveryTaskExactlyOnce() {
        PagedDataDTO<UserTask> p0 = page(0);
        PagedDataDTO<UserTask> p1 = page(1);
        PagedDataDTO<UserTask> p2 = page(2);

        assertThat(p0.getData()).hasSize(PAGE);
        assertThat(p1.getData()).hasSize(PAGE);
        assertThat(p2.getData()).hasSize(TASKS - 2 * PAGE);

        assertThat(p0.getTotalElements()).isEqualTo(TASKS);
        assertThat(p1.getTotalElements()).isEqualTo(TASKS);
        assertThat(p2.getTotalElements()).isEqualTo(TASKS);

        List<UUID> paged = new ArrayList<>();
        paged.addAll(idsOf(p0));
        paged.addAll(idsOf(p1));
        paged.addAll(idsOf(p2));

        Set<UUID> unique = new LinkedHashSet<>(paged);
        assertThat(unique).as("no duplicates across pages").hasSize(TASKS);
        assertThat(paged).as("sum of the pages == total").hasSize(TASKS);
        assertThat(unique).as("nothing missed, nothing foreign")
            .containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * Every row shares {@code createdAt}, so ONLY the id tie-breaker can define the order. Without
     * it PostgreSQL answers the tied rows in whatever order the plan produces (heap order here),
     * which is not id order for 25 random uuids.
     */
    @Test
    void tiedSortKey_pagesFollowTheIdTieBreaker() {
        List<UUID> byId = byDatabaseIdOrder(expected);

        assertThat(idsOf(page(0))).as("page 0 == the ten smallest ids")
            .containsExactlyElementsOf(byId.subList(0, PAGE));
        assertThat(idsOf(page(1))).as("page 1 == the next ten")
            .containsExactlyElementsOf(byId.subList(PAGE, 2 * PAGE));
        assertThat(idsOf(page(2))).as("page 2 == the last five")
            .containsExactlyElementsOf(byId.subList(2 * PAGE, TASKS));
    }

    @Test
    void mergedResult_excludesRowsOfOtherPeople() {
        List<UUID> everything = new ArrayList<>(idsOf(page(0)));
        everything.addAll(idsOf(page(1)));
        everything.addAll(idsOf(page(2)));

        Set<UUID> foreign = new LinkedHashSet<>(
            userTaskRepository.findByProcessDefinitionId(pdId).stream()
                .filter(t -> t.getCompletedAt() == null)
                .map(UserTaskEntity::getId)
                .toList());
        foreign.removeAll(expected);
        assertThat(foreign).as("the fixture really has rows the person does not relate to")
            .hasSizeGreaterThanOrEqualTo(4);
        assertThat(everything).doesNotContainAnyElementsOf(foreign);
    }
}