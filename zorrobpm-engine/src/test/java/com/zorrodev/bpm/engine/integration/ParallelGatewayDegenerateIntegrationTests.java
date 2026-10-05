package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
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
 * WO-C8-34 (crit 7, V7-finding): degenerate 1-in/1-out parallel gateway is an
 * explicit pass-through on the SAME token (mirrors InclusiveGatewayHandler's
 * else) — NOT a fork. Before the fix the single branch forked a child token
 * with a pendingBranches=1 counter for no reason: harmless today (the branch
 * runs, the join side completes on the child token, the stale counter row
 * lingers), but a semantic lie that the next reader — or the next join
 * change — trips over. The test pins the shape: same token in, same token
 * out, no child token, no pending counter.
 *
 * <p>POF-мутация: убрать pass-through ветку — тест RED (появляется child
 * token с pendingBranches=1).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ParallelGatewayDegenerateIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private com.zorrodev.bpm.engine.repository.TokenRepository tokenRepository;

    @Autowired
    private com.zorrodev.bpm.engine.repository.ActivityRepository activityRepository;

    @Transactional
    @Test
    void degenerateOneInOneOut_staysOnSameTokenWithoutCounter() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c834-pgw11.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        // the gateway row carries the arriving (root) token …
        com.zorrodev.bpm.engine.entity.ActivityEntity gw = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> "gw".equals(a.getBpmnElementId()))
            .findFirst().orElseThrow();
        final java.util.UUID gwToken = gw.getToken();
        // … and no child token with a pending counter was forked for one branch
        assertThat(tokenRepository.findAll().stream()
            .filter(t -> t.getParentId() != null && t.getParentId().equals(gwToken))
            .filter(t -> Integer.valueOf(1).equals(t.getPendingBranches()))
            .toList()).as("no 1-branch fork token with a counter").isEmpty();

        // the flow still reaches the downstream task and the instance finishes
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(pi);
        assertThat(queryService.findUserTasks(query, null).getData())
            .as("flow passes through the degenerate gateway to afterTask")
            .anyMatch(t -> "afterTask".equals(t.getCode()));

        UUID taskId = queryService.findUserTasks(query, null).getData().stream()
            .filter(t -> "afterTask".equals(t.getCode()))
            .findFirst().orElseThrow().getId();
        runtimeService.completeUserTask(taskId, List.of());

        ProcessInstance done = queryService.getProcessInstance(pi);
        assertThat(done.getCompletedAt()).as("instance completes through the pass-through").isNotNull();
    }
}
