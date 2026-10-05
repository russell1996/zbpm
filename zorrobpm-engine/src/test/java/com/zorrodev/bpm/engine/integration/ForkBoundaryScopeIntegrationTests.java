package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-34 (CR-05): an interrupting boundary on a host inside a parallel fork
 * cancels ONLY the host branch — the sibling on the same token survives.
 * Before the fix {@code fireBoundary} cancelled the whole token, so the
 * sibling went CREATED→CANCELLED (external-review repro).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ForkBoundaryScopeIntegrationTests {

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

    private UUID deployAndStart(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        return startResult.getId();
    }

    private UserTask parkedTask(UUID processInstanceId, String code) {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        return queryService.findUserTasks(query, null).getData().stream()
            .filter(t -> code.equals(t.getCode()))
            .findFirst().orElseThrow();
    }

    private ActivityStatus activityStatus(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getStatus();
    }

    @Transactional
    @Test
    void interruptingBoundaryOnForkHost_siblingSurvives() throws Exception {
        UUID pi = deployAndStart("test-c834-fork-boundary.bpmn");

        // both fork branches parked
        UserTask host = parkedTask(pi, "hostTask");
        UserTask sibling = parkedTask(pi, "siblingTask");

        // fire the interrupting boundary on the host (same call the timer path makes)
        activityService.fireBoundaryTimer(host.getId(), "hostTimeout");

        // host branch cancelled, boundary continuation ran to its end
        assertThat(activityStatus(host.getId())).isEqualTo(ActivityStatus.CANCELLED);
        List<ActivityEntity> rows = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
        assertThat(rows).anyMatch(a -> "endTimeout".equals(a.getBpmnElementId())
            && a.getStatus() == ActivityStatus.COMPLETED);

        // the SIBLING on the same fork token is untouched — still parked
        assertThat(activityStatus(sibling.getId())).isEqualTo(ActivityStatus.CREATED);
    }

    @Transactional
    @Test
    void interruptingBoundaryOnMiHost_cancelsSiblingInstances() throws Exception {
        // WO-ENG-3 regression guard: MI instances genuinely share one token, so
        // token-wide cancel is CORRECT here — firing on one instance must cancel
        // the sibling instance too (the fork test above must NOT be read as
        // "never cancel for the token").
        UUID pi = deployAndStart("test-mi-boundary.bpmn");

        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(pi);
        List<UserTask> parked = queryService.findUserTasks(query, null).getData();
        assertThat(parked).hasSize(2);

        activityService.fireBoundaryTimer(parked.get(0).getId(), "boundaryTimer");

        List<ActivityEntity> rows = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> "miTask".equals(a.getBpmnElementId()))
            .toList();
        assertThat(rows).allMatch(a -> a.getStatus() == ActivityStatus.CANCELLED);
    }

    /**
     * WO-C8-34 red-team M2 (major): the same CR-05 defect with a MULTI-INSTANCE host.
     * A fork runs all branches on ONE token, so MI instances and the sibling branch
     * share it — the token-wide cancel that is CORRECT for a lone MI host (WO-ENG-3)
     * killed the unrelated branch here. Expected: the MI siblings OF THIS ELEMENT go
     * CANCELLED (WO-ENG-3 preserved), the other fork branch stays CREATED.
     */
    @Transactional
    @Test
    void interruptingBoundaryOnMiHostInsideFork_cancelsMiSiblingsButNotForkBranch() throws Exception {
        UUID pi = deployAndStart("test-c834-mi-fork-boundary.bpmn");

        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(pi);
        List<UserTask> parked = queryService.findUserTasks(query, null).getData();
        UserTask miInstance = parked.stream()
            .filter(t -> "miTask".equals(t.getName()))
            .findFirst().orElseThrow(() -> new AssertionError("MI task not parked: " + parked));
        UserTask otherBranch = parked.stream()
            .filter(t -> "otherTask".equals(t.getName()))
            .findFirst().orElseThrow(() -> new AssertionError("sibling branch not parked: " + parked));

        activityService.fireBoundaryTimer(miInstance.getId(), "miTimeout");

        List<ActivityEntity> miRows = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> "miTask".equals(a.getBpmnElementId()))
            .toList();
        assertThat(miRows).as("both MI instances of the element").hasSize(2);
        assertThat(miRows)
            .as("MI siblings of the SAME element still cancelled (WO-ENG-3)")
            .allMatch(a -> a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activityRepository.findById(otherBranch.getId()).orElseThrow().getStatus())
            .as("the OTHER fork branch must survive an MI host's boundary")
            .isEqualTo(ActivityStatus.CREATED);
    }
}
