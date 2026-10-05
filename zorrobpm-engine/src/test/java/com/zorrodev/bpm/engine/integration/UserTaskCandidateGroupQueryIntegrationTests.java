package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-IN-2 criterion C0: {@code UserTaskQuery.candidateGroup} was DECLARED in the contract
 * (and in the frontend type) but never applied by {@code UserTaskQueryOperationsImpl} — the
 * only conditions there were completed/assigned/assignee, so {@code ?candidateGroup=X} silently
 * answered with EVERY task of the caller.
 *
 * <p>POF (V3, this class is the RED): before the fix, {@code candidateGroupFilter_...} returns
 * all three rows instead of one, and the token-exactness test sees {@code sales-east} matched
 * by a plain substring.
 *
 * <p>Storage reality on disk (V1): there is NO candidate table and no
 * {@code user_tasks.candidate_users} column — only {@code candidate_groups varchar(512)},
 * comma-separated, written by the live path {@code UserTaskHandler.createTaskRow} from
 * {@code zeebe:assignmentDefinition/@candidateGroups}. So the filter is token-exact matching
 * against that column, NOT an EXISTS over a candidates table (escalation E-IN2-1/E-IN2-3).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UserTaskCandidateGroupQueryIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private UserTaskRepository userTaskRepository;

    private UUID pdId;

    @BeforeEach
    void deploy() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-in2-person-query.bpmn"));
        pdId = processDefinitionService.addProcessDefinition(bpmn).getId();
    }

    /** Starts one instance and stamps the requested assignee/candidateGroups onto its task row. */
    private UserTaskEntity taskWith(String assignee, String candidateGroups) throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        UserTaskEntity task = userTaskRepository.findByProcessInstanceId(piId).stream()
            .filter(t -> t.getCompletedAt() == null)
            .findFirst().orElseThrow();
        task.setAssignee(assignee);
        task.setCandidateGroups(candidateGroups);
        return userTaskRepository.save(task);
    }

    private List<UUID> idsByCandidateGroup(String group) {
        UserTaskQuery q = new UserTaskQuery();
        q.setCandidateGroup(group);
        q.setProcessInstanceId(null);
        q.setPageSize(50);
        return queryService.findUserTasks(q, List.of(pdId)).getData().stream()
            .map(UserTask::getId).toList();
    }

    // ===== C0 criterion 1: the declared filter actually filters =====

    @Transactional
    @Test
    void candidateGroups_reachTheTaskRow_fromTheModel() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        UserTaskEntity row = userTaskRepository.findByProcessInstanceId(piId).stream()
            .filter(t -> t.getCompletedAt() == null)
            .findFirst().orElseThrow();

        // the live write path really produces the value the filter is supposed to match on
        assertThat(row.getCandidateGroups()).isEqualTo("sales");
        assertThat(row.getFormKey()).isEqualTo("form-in2-sales");
    }

    @Transactional
    @Test
    void candidateGroupFilter_returnsOnlyTasksOfThatGroup() throws Exception {
        UserTaskEntity sales = taskWith("alice", "sales");
        taskWith("bob", "support,eu-support");

        assertThat(idsByCandidateGroup("sales")).containsExactly(sales.getId());
    }

    /** A comma-separated column makes substring matching a real hazard: `sales` vs `sales-east`. */
    @Transactional
    @Test
    void candidateGroupFilter_matchesWholeTokens_only() throws Exception {
        UserTaskEntity sales = taskWith("alice", "sales");
        UserTaskEntity salesEast = taskWith("bob", "sales-east");

        List<UUID> matched = idsByCandidateGroup("sales");

        assertThat(matched).containsExactly(sales.getId());
        assertThat(matched).doesNotContain(salesEast.getId());
    }

    @Transactional
    @Test
    void candidateGroupFilter_matchesAnyElementOfTheGroupList() throws Exception {
        taskWith("alice", "sales");
        UserTaskEntity support = taskWith("bob", "support,eu-support");

        assertThat(idsByCandidateGroup("eu-support")).containsExactly(support.getId());
        assertThat(idsByCandidateGroup("support")).containsExactly(support.getId());
    }

    @Transactional
    @Test
    void candidateGroupFilter_unknownGroup_returnsNothing() throws Exception {
        taskWith("alice", "sales");

        assertThat(idsByCandidateGroup("legal")).isEmpty();
    }

    /** A `%` in the filter value must stay a literal, never a LIKE wildcard. */
    @Transactional
    @Test
    void candidateGroupFilter_likeMetacharactersAreNotWildcards() throws Exception {
        UserTaskEntity sales = taskWith("alice", "sales");
        taskWith("bob", "sales-east");

        assertThat(idsByCandidateGroup("sales%")).isEmpty();
        assertThat(idsByCandidateGroup("_ales")).isEmpty();
        assertThat(idsByCandidateGroup("sales")).containsExactly(sales.getId());
    }

    // ===== C0 criterion "absent parameter changes nothing" =====

    @Transactional
    @Test
    void candidateGroupAbsent_orBlank_returnsEveryTask() throws Exception {
        taskWith("alice", "sales");
        taskWith("bob", "support,eu-support");
        taskWith("carol", null);

        UserTaskQuery absent = new UserTaskQuery();
        absent.setPageSize(50);
        assertThat(queryService.findUserTasks(absent, List.of(pdId)).getData()).hasSize(3);

        UserTaskQuery blank = new UserTaskQuery();
        blank.setPageSize(50);
        blank.setCandidateGroup("   ");
        assertThat(queryService.findUserTasks(blank, List.of(pdId)).getData()).hasSize(3);
    }
}