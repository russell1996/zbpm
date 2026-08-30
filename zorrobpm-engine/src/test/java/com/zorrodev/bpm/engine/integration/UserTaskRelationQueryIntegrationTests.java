package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskRelation;
import com.zorrodev.bpm.contract.exception.InvalidQueryException;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the "task relates to this person" block of {@link UserTaskQuery}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
class UserTaskRelationQueryIntegrationTests {

    private static final String U1 = "U1";
    private static final String U2 = "U2";
    private static final String G1 = "G1";
    private static final String G2 = "G2";
    private static final String G3 = "G3";

    @Autowired private QueryService queryService;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserTaskCandidateRepository candidateRepository;

    private UserTaskQueryFixture fixture;

    /** assignee=U1, no candidates */
    private UUID assignedToU1;
    /** unassigned, candidate user U1 */
    private UUID candidateUserU1;
    /** unassigned, candidate group G1 */
    private UUID candidateGroupG1;
    /** unassigned, candidate group G2 */
    private UUID candidateGroupG2;
    /** unassigned, candidate group G3 - belongs to nobody in these tests */
    private UUID candidateGroupG3;
    /** assignee=U2, candidate group G1 */
    private UUID assignedToU2InG1;
    /** assignee=U1 and candidate user U1 - matches two conditions at once */
    private UUID assignedAndCandidateU1;
    /** unassigned, candidate groups G1 and G2 - matches two groups at once */
    private UUID candidateGroupsG1G2;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new UserTaskQueryFixture(processDefinitionService, processInstanceRepository,
            userTaskRepository, candidateRepository);

        assignedToU1 = fixture.task(U1, List.of(), List.of());
        candidateUserU1 = fixture.task(null, List.of(U1), List.of());
        candidateGroupG1 = fixture.task(null, List.of(), List.of(G1));
        candidateGroupG2 = fixture.task(null, List.of(), List.of(G2));
        candidateGroupG3 = fixture.task(null, List.of(), List.of(G3));
        assignedToU2InG1 = fixture.task(U2, List.of(), List.of(G1));
        assignedAndCandidateU1 = fixture.task(U1, List.of(U1), List.of());
        candidateGroupsG1G2 = fixture.task(null, List.of(), List.of(G1, G2));
    }

    @Test
    void relationAssignee_returnsOnlyTasksAssignedToThePerson() {
        assertThat(ids(q -> {
            q.setRelatedToUser(U1);
            q.setRelation(UserTaskRelation.ASSIGNEE);
        })).containsExactlyInAnyOrder(assignedToU1, assignedAndCandidateU1)
            .doesNotContain(candidateUserU1);
    }

    @Test
    void relationCandidate_returnsTasksAvailableThroughUserOrGroups() {
        assertThat(ids(q -> {
            q.setRelatedToUser(U1);
            q.setRelatedToGroups(List.of(G1, G2));
            q.setRelation(UserTaskRelation.CANDIDATE);
        })).containsExactlyInAnyOrder(
            candidateUserU1, candidateGroupG1, candidateGroupG2,
            assignedToU2InG1, assignedAndCandidateU1, candidateGroupsG1G2)
            .doesNotContain(assignedToU1, candidateGroupG3);
    }

    @Test
    void relationAny_unionsAssigneeAndCandidate() {
        assertThat(ids(q -> {
            q.setRelatedToUser(U1);
            q.setRelatedToGroups(List.of(G1));
            q.setRelation(UserTaskRelation.ANY);
        })).containsExactlyInAnyOrder(
            assignedToU1, candidateUserU1, candidateGroupG1,
            assignedToU2InG1, assignedAndCandidateU1, candidateGroupsG1G2)
            .doesNotContain(candidateGroupG2, candidateGroupG3);
    }

    @Test
    void missingRelation_behavesAsAny() {
        List<UUID> withoutRelation = ids(q -> {
            q.setRelatedToUser(U1);
            q.setRelatedToGroups(List.of(G1));
        });
        List<UUID> withAny = ids(q -> {
            q.setRelatedToUser(U1);
            q.setRelatedToGroups(List.of(G1));
            q.setRelation(UserTaskRelation.ANY);
        });
        assertThat(withoutRelation).containsExactlyInAnyOrderElementsOf(withAny);
    }

    @Test
    void blockIsCombinedWithOtherFiltersUsingAnd() {
        assertThat(ids(q -> {
            q.setRelatedToUser(U1);
            q.setRelatedToGroups(List.of(G1));
            q.setRelation(UserTaskRelation.CANDIDATE);
            q.setAssigned(false);
        })).containsExactlyInAnyOrder(candidateUserU1, candidateGroupG1, candidateGroupsG1G2)
            .doesNotContain(assignedToU2InG1, assignedAndCandidateU1);
    }

    @Test
    void groupsWithoutUser_matchCandidateGroupsOnly() {
        assertThat(ids(q -> {
            q.setRelatedToGroups(List.of(G1));
            q.setRelation(UserTaskRelation.CANDIDATE);
        })).containsExactlyInAnyOrder(candidateGroupG1, assignedToU2InG1, candidateGroupsG1G2);
    }

    @Test
    void taskMatchingSeveralGroups_isReturnedOnceAndCountedOnce() {
        PagedDataDTO<UserTask> page = find(q -> {
            q.setRelatedToGroups(List.of(G1, G2));
            q.setRelation(UserTaskRelation.CANDIDATE);
        });
        List<UUID> ids = page.getData().stream().map(UserTask::getId).toList();

        assertThat(ids).filteredOn(candidateGroupsG1G2::equals).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(ids.size());
    }

    @Test
    void taskMatchingAssigneeAndCandidate_isReturnedOnce() {
        PagedDataDTO<UserTask> page = find(q -> {
            q.setRelatedToUser(U1);
            q.setRelation(UserTaskRelation.ANY);
        });
        List<UUID> ids = page.getData().stream().map(UserTask::getId).toList();

        assertThat(ids).filteredOn(assignedAndCandidateU1::equals).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(ids.size());
    }

    @Test
    void relationWithoutSubject_isRejected() {
        assertThatThrownBy(() -> find(q -> q.setRelation(UserTaskRelation.ANY)))
            .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void emptyGroups_meanNoGroupsRatherThanAnyGroup() {
        assertThat(ids(q -> {
            q.setRelatedToUser(U1);
            q.setRelatedToGroups(List.of());
            q.setRelation(UserTaskRelation.CANDIDATE);
        })).containsExactlyInAnyOrder(candidateUserU1, assignedAndCandidateU1)
            .doesNotContain(candidateGroupG1, candidateGroupG2, candidateGroupsG1G2);
    }

    @Test
    void queryWithoutTheBlock_isUnaffected() {
        assertThat(ids(q -> { })).containsExactlyInAnyOrder(
            assignedToU1, candidateUserU1, candidateGroupG1, candidateGroupG2,
            candidateGroupG3, assignedToU2InG1, assignedAndCandidateU1, candidateGroupsG1G2);
    }

    @Test
    void legacySingleGroupFilter_stillWorks() {
        assertThat(ids(q -> q.setCandidateGroup(G1)))
            .containsExactlyInAnyOrder(candidateGroupG1, assignedToU2InG1, candidateGroupsG1G2);
    }

    @Test
    void legacyAndNewFiltersCombine() {
        assertThat(ids(q -> {
            q.setAssignee(U2);
            q.setRelatedToUser(U1);
            q.setRelatedToGroups(List.of(G1));
            q.setRelation(UserTaskRelation.CANDIDATE);
        })).containsExactly(assignedToU2InG1);
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
