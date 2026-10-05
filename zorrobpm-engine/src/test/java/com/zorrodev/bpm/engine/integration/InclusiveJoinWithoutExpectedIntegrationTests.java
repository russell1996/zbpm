package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
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
 * WO-C8-35 (CR-09): an inclusive JOIN whose {@code expected} counter nobody wrote.
 *
 * <p>The counter is set by exactly three branchers (inclusive split, multi-instance,
 * ad-hoc). Reached from anything else — an XOR gateway, a parallel fork, an implicit
 * AND-fork, a subprocess/call-activity exit — it stayed {@code null}, and the handler's
 * {@code expected != null && arrived.size() >= expected} was false on every single
 * arrival: the join logged "not ready" and waited FOREVER, with no incident. These
 * tests drive the three shapes through the real engine.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class InclusiveJoinWithoutExpectedIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private UUID start(String fixture, List<ProcessVariable> variables) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fixture));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(variables);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private UserTask userTask(UUID pi, String name) {
        UserTaskQuery q = new UserTaskQuery();
        q.setProcessInstanceId(pi);
        PagedDataDTO<UserTask> page = queryService.findUserTasks(q, null);
        return page.getData().stream().filter(t -> name.equals(t.getName())).findFirst().orElse(null);
    }

    private static ProcessVariable var(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    private ActivityStatus statusOf(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .map(a -> a.getStatus())
            .findFirst().orElse(null);
    }

    /**
     * Criterion 1, shape 1: the inclusive join behind an XOR split. The XOR takes
     * branch A only (condition true), so flowB is never taken and nothing can ever
     * arrive on it. Waiting for a branch that CANNOT come is the bug: before the fix
     * the instance sat RUNNING forever.
     */
    @Transactional
    @Test
    void inclusiveJoin_afterExclusiveSplit_completesInsteadOfWaitingForever() throws Exception {
        UUID pi = start("test-c835-xor-incl-join.bpmn", List.of(var("go", "A")));

        UserTask taskA = userTask(pi, "taskA");
        assertThat(taskA).as("XOR took branch A").isNotNull();

        runtimeService.completeUserTask(taskA.getId(), List.of());

        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt())
            .as("join fired: no live execution could still deliver flowB")
            .isNotNull();
        assertThat(statusOf(pi, "join")).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(statusOf(pi, "endEvent")).isEqualTo(ActivityStatus.COMPLETED);
    }

    /**
     * Criterion 1, shape 2 — the half that forbids the lazy fix. Here the second
     * branch IS still live (a parallel fork, no `expected` written), so the join must
     * keep waiting after the FIRST arrival. Substituting {@code expected = 1} would
     * complete the process while taskB is still open, and this assertion is what says
     * no.
     */
    @Transactional
    @Test
    void inclusiveJoin_afterParallelFork_waitsWhileAnotherBranchIsStillLive() throws Exception {
        UUID pi = start("test-c835-fork-incl-join.bpmn", List.of());

        UserTask taskA = userTask(pi, "taskA");
        UserTask taskB = userTask(pi, "taskB");
        assertThat(taskA).as("fork branch A parked").isNotNull();
        assertThat(taskB).as("fork branch B parked").isNotNull();

        runtimeService.completeUserTask(taskA.getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("branch B is still live, so the join must NOT fire yet")
            .isNull();
        assertThat(statusOf(pi, "join")).as("join has not run").isNull();

        runtimeService.completeUserTask(taskB.getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("both branches delivered — the join fires now")
            .isNotNull();
        assertThat(statusOf(pi, "join")).isEqualTo(ActivityStatus.COMPLETED);
    }

    /**
     * Criterion 2: {@code findInclusiveJoin}'s old first-match BFS wrote the counter onto
     * a DECOY join lying on one branch's path. The topology is built so that misplacing
     * the counter is fatal rather than cosmetic: the decoy's own second incoming belongs
     * to a task that never runs (it hangs off an XOR that picked the other branch), so a
     * counter of 2 on the decoy makes it wait for a delivery that cannot happen — d1
     * never reaches the real join, the real join never gets its counter, and the process
     * hangs. With the convergent-join rule the counter lands on the join both split
     * branches actually reach, and the tail completes.
     */
    @Transactional
    @Test
    void inclusiveSplit_counterGoesToTheConvergingJoin_notToADecoyOnOneBranchPath() throws Exception {
        UUID pi = start("test-c835-decoy-incl-join.bpmn", List.of(var("pick", "B")));

        UserTask taskA = userTask(pi, "taskA");
        UserTask taskB = userTask(pi, "taskB");
        assertThat(taskA).as("split branch A parked").isNotNull();
        assertThat(taskB).as("XOR on branch B took taskB").isNotNull();
        assertThat(userTask(pi, "taskC"))
            .as("the decoy's other incoming (taskC) must NOT run, else the topology proves nothing")
            .isNull();

        runtimeService.completeUserTask(taskA.getId(), List.of());
        assertThat(statusOf(pi, "decoyJoin"))
            .as("decoy has no counter and nobody can still reach it -> it fires")
            .isEqualTo(ActivityStatus.COMPLETED);

        runtimeService.completeUserTask(taskB.getId(), List.of());

        assertThat(statusOf(pi, "join"))
            .as("the REAL convergent join fired: expected=2 was written to it, not to the decoy")
            .isEqualTo(ActivityStatus.COMPLETED);
        assertThat(statusOf(pi, "endEvent")).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
    }
}
