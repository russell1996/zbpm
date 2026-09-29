package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.MessagePublishResult;
import com.zorrodev.bpm.engine.dto.ThrowServiceTaskErrorResult;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-DIFF-5: public message/error-throw API at the engine-service level.
 *
 * <ul>
 *   <li>throw: error boundary attached DIRECTLY to the throwing service task (S-046-style
 *       implicit fork: {@code work} || {@code guarded}+boundary) — without the
 *       {@code ErrorEscalationThrower} self-check the throw escapes unhandled;</li>
 *   <li>publish: {@code publishMessage} reports counted correlation ({@code correlated}) and
 *       message-start launches ({@code started}) instead of a bare silent call.</li>
 * </ul>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ThrowServiceTaskErrorIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private DBService dbService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    private ProcessVariable pv(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(ProcessVariableType.STRING);
        return v;
    }

    private UUID deploy(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private UUID start(UUID processDefinitionId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ActivityEntity activeTask(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId)
                && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow(() -> new AssertionError("active task not found: " + bpmnElementId));
    }

    private List<ActivityEntity> activitiesOf(UUID processInstanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
    }

    @Transactional
    @Test
    void throwErrorFromGuardedTask_matchesOwnBoundary_escapeBranchFires() throws Exception {
        // S-046-style fork: work || guarded(+boundary E-THROW -> escapeEnd).
        // Throwing from guarded must catch on guarded's OWN boundary (self-check), cancel only
        // the guarded task (the sibling keeps its branch), carry thrownVar to the escape path.
        UUID pdId = deploy("test-throw-error-direct-boundary.bpmn");
        UUID pi = start(pdId);
        UUID forkId = activeTask(pi, "forkTask").getId();
        runtimeService.completeServiceTask(forkId, List.of());

        UUID guardedId = activeTask(pi, "guarded").getId();
        UUID workId = activeTask(pi, "work").getId();

        ThrowServiceTaskErrorResult result =
            activityService.throwServiceTaskError(guardedId, "E-THROW", List.of(pv("thrownVar", "thrownValue")));

        assertThat(result.isHandled()).as("boundary on the throwing task itself must catch").isTrue();
        assertThat(result.getIncidentId()).isNull();

        List<ActivityEntity> activities = activitiesOf(pi);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("guarded")
            && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("escapeEnd")
            && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("endGuarded"));
        // sibling branch untouched: work still parked, instance still running
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("work")
            && a.getId().equals(workId) && a.getStatus() == ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();

        // thrown variables were applied before the throw and are visible on the instance
        assertThat(dbService.getVariables(pi)).anyMatch(v -> v.getName().equals("thrownVar")
            && "thrownValue".equals(v.getValue()));

        // sibling can still finish its own branch afterwards
        runtimeService.completeServiceTask(workId, List.of());
        assertThat(activitiesOf(pi)).anyMatch(a -> a.getBpmnElementId().equals("endWork")
            && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void throwError_unmatchedCode_raisesIncidentAndMarksError() throws Exception {
        // Mirror of the automatic ErrorEndEvent path: a manual throw nobody catches is never
        // quieter — the activity is marked ERROR and an "Unhandled BPMN error" incident is raised.
        UUID pdId = deploy("test-throw-error-direct-boundary.bpmn");
        UUID pi = start(pdId);
        UUID forkId = activeTask(pi, "forkTask").getId();
        runtimeService.completeServiceTask(forkId, List.of());

        UUID guardedId = activeTask(pi, "guarded").getId();

        ThrowServiceTaskErrorResult result =
            activityService.throwServiceTaskError(guardedId, "E-NOPE", List.of());

        assertThat(result.isHandled()).isFalse();
        assertThat(result.getIncidentId()).isNotNull();
        IncidentEntity incident = incidentRepository.findById(result.getIncidentId()).orElseThrow();
        assertThat(incident.getMessage()).isEqualTo("Unhandled BPMN error 'E-NOPE'");
        assertThat(activitiesOf(pi)).anyMatch(a -> a.getId().equals(guardedId)
            && a.getStatus() == ActivityStatus.ERROR);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
    }

    @Transactional
    @Test
    void throwError_staleTask_409ThrowErrorStale() throws Exception {
        // Throwing from a task that already finished its branch is a 409, never a silent no-op.
        UUID pdId = deploy("test-throw-error-direct-boundary.bpmn");
        UUID pi = start(pdId);
        UUID forkId = activeTask(pi, "forkTask").getId();
        runtimeService.completeServiceTask(forkId, List.of());

        UUID guardedId = activeTask(pi, "guarded").getId();
        runtimeService.completeServiceTask(guardedId, List.of());

        assertThatThrownBy(() -> activityService.throwServiceTaskError(guardedId, "E-THROW", List.of()))
            .isInstanceOf(ApiException.class)
            .matches(e -> ((ApiException) e).getStatus() == HttpStatus.CONFLICT
                && "THROW_ERROR_STALE".equals(((ApiException) e).getCode()));
    }

    @Transactional
    @Test
    void publishMessage_correlatesWaitingSubscription_andCounts() throws Exception {
        // test-message-boundary: userTask1 parked with message boundary "cancel-order" -> boundaryEnd.
        // publishMessage must wake it (correlated=1, started=0) and drive the boundary path.
        UUID pdId = deploy("test-message-boundary.bpmn");
        UUID pi = start(pdId);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();

        MessagePublishResult result = activityService.publishMessage("cancel-order", null, pi, List.of());

        assertThat(result.getCorrelated()).as("one waiting subscription must be woken").isEqualTo(1);
        assertThat(result.getStarted()).isZero();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        List<ActivityEntity> activities = activitiesOf(pi);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("userTask1")
            && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("boundaryEnd")
            && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void publishMessage_nameOnly_startsInstanceViaMessageStart_andCounts() throws Exception {
        // test-message-start: no plain start, only a message start "order-received".
        // A global publish (no instance, no key) must start it (correlated=0, started=1).
        UUID pdId = deploy("test-message-start.bpmn");
        long before = processInstanceRepository.findAll().stream()
            .filter(p -> p.getProcessDefinitionId().equals(pdId)).count();

        MessagePublishResult result = activityService.publishMessage("order-received", null, null, List.of());

        assertThat(result.getStarted()).as("message start must launch one instance").isEqualTo(1);
        assertThat(result.getCorrelated()).isZero();
        List<ProcessInstanceEntity> instances = processInstanceRepository.findAll().stream()
            .filter(p -> p.getProcessDefinitionId().equals(pdId)).toList();
        assertThat(instances).hasSize((int) before + 1);
        assertThat(instances).allMatch(p -> p.getCompletedAt() != null);
    }
}
