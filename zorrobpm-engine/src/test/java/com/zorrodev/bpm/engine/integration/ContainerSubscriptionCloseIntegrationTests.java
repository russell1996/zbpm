package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-37 (C37-3): a finished wait-state host's message subscriptions close
 * with it (consumed=true at complete/cancel). A later message for the same name
 * must reach the NEXT live subscription — the dead row must not eat it first
 * (consumeMessageSubscription is CAS-first, so the older row would otherwise
 * win the correlation race by id order and the live catch would starve).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ContainerSubscriptionCloseIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private MessageSubscriptionRepository messageSubscriptionRepository;

    private UUID start(String fixture) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fixture));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> rows(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .toList();
    }

    private List<MessageSubscriptionEntity> subs(UUID pi) {
        return messageSubscriptionRepository.findAll().stream()
            .filter(s -> pi.equals(s.getProcessInstanceId()))
            .toList();
    }

    private boolean instanceDone(UUID pi) {
        return queryService.getProcessInstance(pi).getCompletedAt() != null;
    }

    @Test
    void finishedCatchSubscription_closed_messageReachesNextLiveCatch() throws Exception {
        UUID pi = start("test-c837-container-subscription-close.bpmn");
        assertThat(rows(pi, "waitA")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);

        activityService.correlateMessage("holdSignal", pi, List.of());
        assertThat(rows(pi, "waitA")).anyMatch(a -> a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(rows(pi, "waitB")).anyMatch(a -> a.getStatus() == ActivityStatus.CREATED);

        activityService.correlateMessage("holdSignal", pi, List.of());
        assertThat(rows(pi, "waitB")).anyMatch(a -> a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(instanceDone(pi)).isTrue();
    }

    @Test
    void boundarySubscriptionOfCompletedHost_closedWithoutAnyCorrelate() throws Exception {
        // POF-mutation test for the fix: the host completes with NO message ever
        // correlated (direct user-task completion) — no CAS consume ever ran, so
        // consumed=true on the dead host's row can ONLY come from completeActivity.
        // Roll back the fix (stash ActivityDbOperationsImpl) and this goes RED.
        UUID pi = start("test-c837-timeout-then-message.bpmn");
        UUID work = rows(pi, "work").stream()
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow().getId();
        assertThat(subs(pi).stream().filter(s -> work.equals(s.getActivityId())).toList())
            .hasSize(1)
            .allMatch(s -> !s.isConsumed());

        runtimeService.completeUserTask(work, List.of());

        assertThat(subs(pi).stream().filter(s -> work.equals(s.getActivityId())).toList())
            .hasSize(1)
            .allMatch(MessageSubscriptionEntity::isConsumed);
        assertThat(instanceDone(pi)).isTrue();
    }

    @Test
    void finishedCatchSubscription_rowIsConsumed_notDeleted() throws Exception {
        UUID pi = start("test-c837-container-subscription-close.bpmn");
        UUID waitA = rows(pi, "waitA").stream()
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow().getId();

        activityService.correlateMessage("holdSignal", pi, List.of());

        List<MessageSubscriptionEntity> dead = subs(pi).stream()
            .filter(s -> waitA.equals(s.getActivityId()))
            .toList();
        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).isConsumed()).isTrue();
    }
}
