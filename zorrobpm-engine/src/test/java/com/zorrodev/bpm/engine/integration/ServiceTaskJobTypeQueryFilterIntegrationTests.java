package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
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
 * WO-IN-1: {@code ServiceTaskQuery.jobType} was declared in the contract DTO and sent by the
 * client resolver, but {@link com.zorrodev.bpm.engine.service.query.ServiceTaskQueryOperationsImpl}
 * never added a specification for it — {@code GET …/service-tasks?jobType=X} silently returned
 * EVERY task of the caller instead of only the requested job type.
 *
 * <p>Full-stack shape (V11/G-N): the BPMN fixture declares two service tasks with DIFFERENT
 * {@code zeebe:taskDefinition} types that are open simultaneously (parallel split), the engine
 * persists the type onto {@code service_tasks.job} on the real creation path, and the assertions
 * go through the real {@link QueryService} → real Specification → real DB. Nothing is seeded by
 * the test itself, so a filter that is not applied cannot produce a passing assertion.
 *
 * <p>POF (G-K, named mutation): removing the {@code jobType} block from
 * {@code ServiceTaskQueryOperationsImpl.findServiceTasks} turns
 * {@link #jobType_returnsOnlyTasksOfThatType()} and
 * {@link #jobTypePlusCompleted_completedTypeOnlyMatchesTheCompletedTask()} RED ("expected size 1
 * to be 2"), because every query without the filter returns both open tasks.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ServiceTaskJobTypeQueryFilterIntegrationTests {

    private static final String ALPHA_JOB = "in1-alpha-job";
    private static final String BETA_JOB = "in1-beta-job";

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;

    private UUID processInstanceId;
    private UUID alphaTaskId;
    private UUID betaTaskId;

    /** Deploys the two-jobType fixture and starts it; both service tasks stay open. */
    private void startTwoJobTypeProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-in1-jobtype-two-tasks.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        processInstanceId = runtimeService.startProcessInstance(dto).getId();
        List<ServiceTask> open = queryService.findServiceTasks(query(), null).getData();
        assertThat(open).as("fixture precondition: both jobs open").hasSize(2);
        alphaTaskId = idOfJob(open, ALPHA_JOB);
        betaTaskId = idOfJob(open, BETA_JOB);
    }

    private ServiceTaskQuery query() {
        ServiceTaskQuery q = new ServiceTaskQuery();
        q.setProcessInstanceId(processInstanceId);
        q.setPageSize(50);
        return q;
    }

    private static UUID idOfJob(List<ServiceTask> tasks, String job) {
        return tasks.stream()
            .filter(t -> job.equals(t.getJob()))
            .map(ServiceTask::getId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no open task with job " + job + " in " + tasks));
    }

    private List<ServiceTask> find(ServiceTaskQuery q) {
        return queryService.findServiceTasks(q, null).getData();
    }

    /** Criterion 1 (the bug): a jobType query must return ONLY tasks of that type. */
    @Transactional
    @Test
    void jobType_returnsOnlyTasksOfThatType() throws Exception {
        startTwoJobTypeProcess();

        ServiceTaskQuery alpha = query();
        alpha.setJobType(ALPHA_JOB);
        assertThat(find(alpha)).extracting(ServiceTask::getId).containsExactly(alphaTaskId);

        ServiceTaskQuery beta = query();
        beta.setJobType(BETA_JOB);
        assertThat(find(beta)).extracting(ServiceTask::getId).containsExactly(betaTaskId);

        ServiceTaskQuery unknown = query();
        unknown.setJobType("no-such-job-type");
        assertThat(find(unknown)).isEmpty();
    }

    /**
     * Golden check: without the parameter the query is unchanged — both tasks of the instance
     * (and, unscoped, tasks of other instances too — see {@link #jobType_absent_returnsEveryTaskOfTheCaller()}).
     */
    @Transactional
    @Test
    void jobType_absent_returnsEveryTaskOfTheCaller() throws Exception {
        startTwoJobTypeProcess();

        List<ServiceTask> all = find(query());
        assertThat(all).extracting(ServiceTask::getJob).containsExactlyInAnyOrder(ALPHA_JOB, BETA_JOB);
    }

    /** Criterion 2: null/blank jobType means "no filter", not "match nothing". */
    @Transactional
    @Test
    void jobType_blank_isTreatedAsAbsent() throws Exception {
        startTwoJobTypeProcess();

        ServiceTaskQuery blank = query();
        blank.setJobType("   ");
        assertThat(find(blank)).extracting(ServiceTask::getJob).containsExactlyInAnyOrder(ALPHA_JOB, BETA_JOB);

        ServiceTaskQuery empty = query();
        empty.setJobType("");
        assertThat(find(empty)).extracting(ServiceTask::getJob).containsExactlyInAnyOrder(ALPHA_JOB, BETA_JOB);
    }

    /** jobType + completed: the combination must narrow on both axes, not swallow one of them. */
    @Transactional
    @Test
    void jobTypePlusCompleted_activeTypeReturnsOnlyItsActiveTask() throws Exception {
        startTwoJobTypeProcess();

        ServiceTaskQuery alphaActive = query();
        alphaActive.setJobType(ALPHA_JOB);
        alphaActive.setCompleted(false);
        assertThat(find(alphaActive)).extracting(ServiceTask::getId).containsExactly(alphaTaskId);

        ServiceTaskQuery betaActive = query();
        betaActive.setJobType(BETA_JOB);
        betaActive.setCompleted(false);
        assertThat(find(betaActive)).extracting(ServiceTask::getId).containsExactly(betaTaskId);

        // nothing is completed yet, so both types are empty on completed=true
        for (String job : List.of(ALPHA_JOB, BETA_JOB)) {
            ServiceTaskQuery done = query();
            done.setJobType(job);
            done.setCompleted(true);
            assertThat(find(done)).as("completed=true for " + job).isEmpty();
        }
    }

    /** After completing alpha: only alpha is visible on completed=true, beta stays active. */
    @Transactional
    @Test
    void jobTypePlusCompleted_completedTypeOnlyMatchesTheCompletedTask() throws Exception {
        startTwoJobTypeProcess();
        runtimeService.completeServiceTask(alphaTaskId, List.of());

        ServiceTaskQuery alphaDone = query();
        alphaDone.setJobType(ALPHA_JOB);
        alphaDone.setCompleted(true);
        assertThat(find(alphaDone)).extracting(ServiceTask::getId).containsExactly(alphaTaskId);

        ServiceTaskQuery betaDone = query();
        betaDone.setJobType(BETA_JOB);
        betaDone.setCompleted(true);
        assertThat(find(betaDone)).isEmpty();

        ServiceTaskQuery betaActive = query();
        betaActive.setJobType(BETA_JOB);
        betaActive.setCompleted(false);
        assertThat(find(betaActive)).extracting(ServiceTask::getId).containsExactly(betaTaskId);

        // and the filter still holds on the un-narrowed axis: completed=true returns alpha only
        ServiceTaskQuery anyDone = query();
        anyDone.setCompleted(true);
        assertThat(find(anyDone)).extracting(ServiceTask::getJob).containsExactly(ALPHA_JOB);
    }

    /**
     * The filter is not a loophole around the tenant grant: a jobType that belongs to a
     * definition the caller may not read still returns nothing.
     */
    @Transactional
    @Test
    void jobType_withForeignAllowedPdIds_returnsNothing() throws Exception {
        startTwoJobTypeProcess();

        ServiceTaskQuery alpha = query();
        alpha.setJobType(ALPHA_JOB);
        assertThat(queryService.findServiceTasks(alpha, List.of(UUID.randomUUID())).getData()).isEmpty();

        ServiceTaskQuery any = query();
        assertThat(queryService.findServiceTasks(any, List.of(UUID.randomUUID())).getData()).isEmpty();
    }
}