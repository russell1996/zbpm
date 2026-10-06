package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-IN-2 DoD: «без параметров поведение побайтно прежнее» — a golden test of the EXISTING list.
 *
 * <p>The point is not that the numbers are right, it is that this exact page is the one the
 * endpoint answered with BEFORE this WO. It is therefore asserted as a literal: five tasks with
 * distinct {@code createdAt}, the whole page, its order and its totals.
 *
 * <p>That claim was checked, not asserted: the same test was run against master's production code
 * (the branch's {@code UserTaskQueryOperationsImpl} reverted) and came out GREEN — see the report,
 * §"golden на master". The ONE documented difference the WO itself asks for is the {@code id}
 * tie-breaker appended to the default order: it can only reorder rows whose {@code createdAt} are
 * exactly equal, and this fixture has none, so the golden page is identical either way.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UserTaskQueryNoParamsGoldenTest {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private UserTaskRepository userTaskRepository;

    private UUID pdId;
    private List<UUID> byAge;

    @BeforeEach
    void deploy() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-in2-person-query.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        pdId = model.getId();

        Instant base = Instant.parse("2026-03-03T10:00:00Z");
        byAge = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(pdId);
            UUID piId = runtimeService.startProcessInstance(dto).getId();
            UserTaskEntity row = userTaskRepository.findByProcessInstanceId(piId).stream()
                .filter(t -> t.getCompletedAt() == null)
                .findFirst().orElseThrow();
            row.setCreatedAt(base.plusSeconds(i * 60));
            byAge.add(userTaskRepository.save(row).getId());
        }
    }

    /** The five ids, newest first — the order the endpoint has always answered with. */
    private List<UUID> newestFirst() {
        List<UUID> ids = new ArrayList<>(byAge);
        java.util.Collections.reverse(ids);
        return ids;
    }

    @Transactional
    @Test
    void noParameters_returnsTheSameWholePageAsBefore() {
        PagedDataDTO<UserTask> page = queryService.findUserTasks(new UserTaskQuery(), List.of(pdId));

        assertThat(page.getTotalElements()).isEqualTo(5L);
        assertThat(page.getPageIndex()).isZero();
        assertThat(page.getPageSize()).isEqualTo(10);
        assertThat(page.getData().stream().map(UserTask::getId).toList())
            .as("createdAt DESC — unchanged default order")
            .isEqualTo(newestFirst());
    }

    @Transactional
    @Test
    void noParameters_pagedWindowIsUnchanged() {
        UserTaskQuery q = new UserTaskQuery();
        q.setPageSize(3);

        PagedDataDTO<UserTask> first = queryService.findUserTasks(q, List.of(pdId));
        assertThat(first.getTotalElements()).isEqualTo(5L);
        assertThat(first.getData().stream().map(UserTask::getId).toList())
            .isEqualTo(newestFirst().subList(0, 3));

        q.setPageIndex(1);
        PagedDataDTO<UserTask> second = queryService.findUserTasks(q, List.of(pdId));
        assertThat(second.getTotalElements()).isEqualTo(5L);
        assertThat(second.getData().stream().map(UserTask::getId).toList())
            .isEqualTo(newestFirst().subList(3, 5));
    }

    @Transactional
    @Test
    void noParameters_stillMapsTheSameFields() {
        UserTaskQuery q = new UserTaskQuery();
        UserTask task = queryService.findUserTasks(q, List.of(pdId)).getData().get(0);

        assertThat(task.getCode()).isEqualTo("reviewTask");
        assertThat(task.getName()).isEqualTo("Review Task");
        assertThat(task.getFormKey()).isEqualTo("form-in2-sales");
        assertThat(task.getProcessDefinitionId()).isEqualTo(pdId);
        assertThat(task.getPriority()).isEqualTo(50);
        assertThat(task.getCompletedAt()).isNull();
    }

    /** The old filters keep their meaning with no new parameter present (regression guard). */
    @Transactional
    @Test
    void oldFiltersWithoutNewParameters_behaveAsBefore() {
        // the model assigns every task to "alice", so all five rows are assigned
        UserTaskQuery assigned = new UserTaskQuery();
        assigned.setAssigned(true);
        assertThat(queryService.findUserTasks(assigned, List.of(pdId)).getTotalElements()).isEqualTo(5L);

        UserTaskQuery unassigned = new UserTaskQuery();
        unassigned.setAssigned(false);
        assertThat(queryService.findUserTasks(unassigned, List.of(pdId)).getData()).isEmpty();

        UserTaskQuery active = new UserTaskQuery();
        active.setCompleted(false);
        assertThat(queryService.findUserTasks(active, List.of(pdId)).getTotalElements()).isEqualTo(5L);

        UserTaskQuery done = new UserTaskQuery();
        done.setCompleted(true);
        assertThat(queryService.findUserTasks(done, List.of(pdId)).getData()).isEmpty();

        UserTaskQuery byAssignee = new UserTaskQuery();
        byAssignee.setAssignee("alice");
        assertThat(queryService.findUserTasks(byAssignee, List.of(pdId)).getTotalElements()).isEqualTo(5L);

        UserTaskQuery byOther = new UserTaskQuery();
        byOther.setAssignee("bob");
        assertThat(queryService.findUserTasks(byOther, List.of(pdId)).getData()).isEmpty();

        // tenant scope: no grant → deny, unrelated definition → invisible
        assertThat(queryService.findUserTasks(new UserTaskQuery(), List.of()).getData()).isEmpty();
        assertThat(queryService.findUserTasks(new UserTaskQuery(), List.of(UUID.randomUUID())).getData()).isEmpty();
    }
}