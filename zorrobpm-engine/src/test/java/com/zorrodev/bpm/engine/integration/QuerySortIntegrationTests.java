package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.SortDirection;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.exception.InvalidQueryException;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the sort parameters shared by every query that extends BaseQuery.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
class QuerySortIntegrationTests {

    @Autowired private QueryService queryService;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserTaskCandidateRepository candidateRepository;

    private UserTaskQueryFixture fixture;
    private UUID oldest;
    private UUID middle;
    private UUID newest;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new UserTaskQueryFixture(processDefinitionService, processInstanceRepository,
            userTaskRepository, candidateRepository);

        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        oldest = fixture.task(null, base, List.of(), List.of());
        middle = fixture.task(null, base.plus(1, ChronoUnit.HOURS), List.of(), List.of());
        newest = fixture.task(null, base.plus(2, ChronoUnit.HOURS), List.of(), List.of());
    }

    @Test
    void sortAscending_ordersFromEarliestToLatest() {
        assertThat(ids(q -> {
            q.setSort("createdAt");
            q.setDirection(SortDirection.ASC);
        })).containsExactly(oldest, middle, newest);
    }

    @Test
    void sortDescending_ordersFromLatestToEarliest() {
        assertThat(ids(q -> {
            q.setSort("createdAt");
            q.setDirection(SortDirection.DESC);
        })).containsExactly(newest, middle, oldest);
    }

    @Test
    void directionDefaultsToAscending() {
        assertThat(ids(q -> q.setSort("createdAt"))).containsExactly(oldest, middle, newest);
    }

    @Test
    void completedAtIsSortable() {
        assertThat(ids(q -> q.setSort("completedAt"))).hasSize(3);
    }

    @Test
    void fieldOutsideTheListIsRejectedAndNamed() {
        assertThatThrownBy(() -> find(q -> q.setSort("assignee")))
            .isInstanceOf(InvalidQueryException.class)
            .hasMessageContaining("assignee");
    }

    @Test
    void arbitraryStringIsRejected() {
        assertThatThrownBy(() -> find(q -> q.setSort("'; drop table user_tasks--")))
            .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void queryTypeWithoutDeclaredFieldsRejectsAnySort() {
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        query.setSort("startedAt");

        assertThatThrownBy(() -> queryService.findProcessInstances(query))
            .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void withoutSortTheQueryStillWorks() {
        assertThat(ids(q -> { })).containsExactlyInAnyOrder(oldest, middle, newest);
    }

    @Test
    void directionWithoutSortIsIgnored() {
        assertThat(ids(q -> q.setDirection(SortDirection.DESC)))
            .containsExactlyInAnyOrder(oldest, middle, newest);
    }

    @Test
    void pagingOverEqualSortValuesLosesNothingAndRepeatsNothing() {
        Instant sameMoment = Instant.parse("2026-02-02T12:00:00Z");
        List<UUID> tied = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tied.add(fixture.task(null, sameMoment, List.of(), List.of()));
        }

        List<UUID> walked = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            int pageIndex = page;
            walked.addAll(ids(q -> {
                q.setSort("createdAt");
                q.setPageIndex(pageIndex);
                q.setPageSize(2);
            }));
        }

        assertThat(walked).doesNotHaveDuplicates()
            .containsAll(tied)
            .containsAll(List.of(oldest, middle, newest));
    }

    @Test
    void sameRequestTwiceReturnsTheSamePage() {
        Instant sameMoment = Instant.parse("2026-03-03T12:00:00Z");
        for (int i = 0; i < 5; i++) {
            fixture.task(null, sameMoment, List.of(), List.of());
        }

        Consumer<UserTaskQuery> secondPage = q -> {
            q.setSort("createdAt");
            q.setPageIndex(1);
            q.setPageSize(3);
        };
        assertThat(ids(secondPage)).containsExactlyElementsOf(ids(secondPage));
    }

    private List<UUID> ids(Consumer<UserTaskQuery> customizer) {
        return find(customizer).getData().stream().map(UserTask::getId).toList();
    }

    private PagedDataDTO<UserTask> find(Consumer<UserTaskQuery> customizer) {
        UserTaskQuery query = new UserTaskQuery();
        query.setPageSize(500);
        query.setProcessInstanceId(fixture.processInstanceId());
        customizer.accept(query);
        return queryService.findUserTasks(query);
    }
}
