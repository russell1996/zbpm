package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-28: guard against duplicate service task after manual completion.
 *
 * Each step runs in its OWN transaction via TransactionTemplate — mirroring
 * the real per-HTTP-request transaction so committed state is visible across
 * steps. Assertions are scoped to this test's own process instance.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class IncidentResolveAfterManualCompleteIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private DBService dbService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private long countByTokenAndElement(UUID token, String elementId) {
        return tx.execute(s -> (long) activityRepository.findByTokenAndBpmnElementId(token, elementId).size());
    }

    @Test
    void resolveAfterManualComplete_doesNotDuplicateServiceTask() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn"));
        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });

        ActivityEntity svc = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals("svc") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow());
        UUID svcId = svc.getId();
        UUID token = svc.getToken();

        // Watchdog-style incident on the still-CREATED activity
        UUID incidentId = tx.execute(s -> dbService.createIncident(svcId, "SERVICE_TASK_DISPATCH_TIMEOUT: test"));

        // Operator manually completes the service task (admin override path)
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svcId, List.of()));

        // Verify manual completion left exactly one activity for (token,svc) and it is COMPLETED
        long afterComplete = countByTokenAndElement(token, "svc");
        assertThat(afterComplete).as("exactly one activity after manual complete").isEqualTo(1);
        ActivityStatus statusAfter = tx.execute(s -> activityRepository.findById(svcId).orElseThrow().getStatus());
        assertThat(statusAfter).isEqualTo(ActivityStatus.COMPLETED);
        IncidentEntity beforeResolve = tx.execute(s -> incidentRepository.findById(incidentId).orElseThrow());
        assertThat(beforeResolve.getCompletedAt()).as("incident still open before resolve").isNull();

        // Resolve the incident — with the fix this must NOT create a duplicate activity
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));

        long afterResolve = countByTokenAndElement(token, "svc");
        assertThat(afterResolve).as("must remain exactly one activity after resolve (no duplicate)").isEqualTo(1);
        IncidentEntity afterResolveInc = tx.execute(s -> incidentRepository.findById(incidentId).orElseThrow());
        assertThat(afterResolveInc.getCompletedAt()).as("incident must be closed without re-execution").isNotNull();
        // No new active activity was created
        long activeAfter = tx.execute(s -> activityRepository.findByTokenAndBpmnElementIdAndStatusIn(token, "svc", List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)).size());
        assertThat(activeAfter).isEqualTo(0);
    }

    @Test
    void normalIncidentResolve_reExecutesWhenNotCompleted() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn"));
        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });

        ActivityEntity svc = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals("svc") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow());
        UUID svcId = svc.getId();
        UUID token = svc.getToken();

        // Fail fatally -> incident on ERROR activity
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svcId, "boom", 0));
        UUID incidentId = tx.execute(s -> incidentRepository.findByActivityIdInAndCompletedAtIsNull(List.of(svcId)).get(0).getId());

        // Resolve — worker has recovered, should re-execute (create new active)
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));

        long activeAfter = tx.execute(s -> activityRepository.findByTokenAndBpmnElementIdAndStatusIn(token, "svc", List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)).size());
        assertThat(activeAfter).as("normal resolve must create one active activity").isEqualTo(1);
        IncidentEntity after = tx.execute(s -> incidentRepository.findById(incidentId).orElseThrow());
        assertThat(after.getCompletedAt()).isNotNull();
    }
}
