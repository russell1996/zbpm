package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-57 (F-15) на РЕАЛЬНОМ PostgreSQL: completionCondition на MI-ЗАДАЧЕ
 * терминирует оставшихся сиблингов, их job'ы закрываются, поздний результат
 * воркера по терминированной задаче отвергается, утечек не остаётся.
 *
 * <p>Тот же сценарий, что и в H2-классе
 * {@code MiTaskCompletionConditionSiblingsIntegrationTests}, но на прод-СУБД:
 * H2 ≠ PostgreSQL (класс P-17/P-22), а правка пишет в БД (CANCELLED +
 * closed job-строки), поэтому поведение подтверждается на обеих.
 *
 * <p>Оракул (Zeebe 8.5.0, живая запись) — см. javadoc H2-класса и
 * {@code docs/analysis/c8-oracle/logs/oracle-mi-task-cc-2026-10-20.log}.
 */
public class MiTaskCompletionConditionSiblingsPgIT extends PostgresIT {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private ServiceTaskRepository serviceTaskRepository;
    @Autowired
    private UserTaskRepository userTaskRepository;
    @Autowired
    private VariableRepository variableRepository;
    @Autowired
    private com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    private void ensureTx() {
        if (tx == null) {
            tx = new TransactionTemplate(txManager);
        }
    }

    private UUID deployAndStart(String bpmnFile) {
        ensureTx();
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<ActivityEntity> activities(UUID pi, String elementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(elementId) && a.getStatus() == status)
            .toList();
    }

    private List<ActivityEntity> liveInstances(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .toList();
    }

    private Map<String, ActivityEntity> byItem(UUID pi, List<ActivityEntity> instances) {
        Map<String, ActivityEntity> out = new LinkedHashMap<>();
        for (ActivityEntity a : instances) {
            String item = null;
            for (ProcessVariable v : elementSupport.visibleVariables(pi, a.getId())) {
                if ("item".equals(v.getName())) {
                    item = v.getValue();
                }
            }
            out.put(item == null ? "?" : item, a);
        }
        return out;
    }

    private long incidents(UUID pi) {
        var activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        return incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count();
    }

    private long openServiceTasks(UUID pi) {
        return serviceTaskRepository.findAll().stream()
            .filter(t -> pi.equals(t.getProcessInstanceId()) && t.getCompletedAt() == null)
            .count();
    }

    private long openUserTasks(UUID pi) {
        return userTaskRepository.findAll().stream()
            .filter(t -> pi.equals(t.getProcessInstanceId()) && t.getCompletedAt() == null)
            .count();
    }

    private void assertNoLeaks(UUID pi) {
        assertThat(liveInstances(pi, "miTask"))
            .as("LEAK-INVARIANT: no live MI instances left").isEmpty();
        assertThat(liveInstances(pi, "dump"))
            .as("LEAK-INVARIANT: no live downstream activity").isEmpty();
        assertThat(openServiceTasks(pi))
            .as("LEAK-INVARIANT: no open service task (job) left").isZero();
        assertThat(openUserTasks(pi))
            .as("LEAK-INVARIANT: no open user task left").isZero();
        assertThat(incidents(pi)).as("LEAK-INVARIANT: no incidents").isZero();
    }

    private List<String> resultsOf(UUID pi) throws Exception {
        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        return new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
    }

    @Test
    void parallelSiblings_terminatedByCompletionCondition_onPostgres() throws Exception {
        UUID pi = deployAndStart("test-c857-mi-task-cc-parallel.bpmn");

        List<ActivityEntity> instances = liveInstances(pi, "miTask");
        assertThat(instances).as("3 MI instances at entry").hasSize(3);
        Map<String, ActivityEntity> byItem = byItem(pi, instances);
        assertThat(byItem.keySet()).containsExactlyInAnyOrder("a", "b", "c");

        ensureTx();
        tx.execute(s -> {
            runtimeService.completeServiceTask(byItem.get("b").getId(), List.of());
            return null;
        });

        assertThat(liveInstances(pi, "miTask"))
            .as("LEAK-INVARIANT: remaining MI instances terminated by the completionCondition").isEmpty();
        assertThat(activities(pi, "miTask", ActivityStatus.CANCELLED)).hasSize(2);
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED))
            .as("MI body closed early — dump task created").hasSize(1);

        List<String> results = resultsOf(pi);
        assertThat(results).hasSize(3);
        assertThat(results.stream().filter(v -> v != null).count()).isEqualTo(1);
        assertThat(results.get(1)).as("the middle slot holds its item").isEqualTo("b");

        tx.execute(s -> {
            runtimeService.completeServiceTask(
                activityRepository.findAll().stream()
                    .filter(a -> a.getProcessInstanceId().equals(pi))
                    .filter(a -> "dump".equals(a.getBpmnElementId()))
                    .findFirst().orElseThrow().getId(), List.of());
            return null;
        });

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(pi);
    }

    @Test
    void lateWorkerCompletionOfTerminatedSibling_refused_onPostgres() throws Exception {
        UUID pi = deployAndStart("test-c857-mi-task-cc-parallel.bpmn");

        List<ActivityEntity> instances = liveInstances(pi, "miTask");
        Map<String, ActivityEntity> byItem = byItem(pi, instances);
        ensureTx();
        tx.execute(s -> {
            runtimeService.completeServiceTask(byItem.get("a").getId(), List.of());
            return null;
        });

        UUID terminatedId = byItem.get("c").getId();
        assertThat(activityRepository.findById(terminatedId).orElseThrow().getStatus())
            .as("sibling c is CANCELLED").isEqualTo(ActivityStatus.CANCELLED);

        // Поздний результат воркера по ТЕРМИНИРОВАННОЙ задаче — отказ без сдвига потока.
        Boolean alreadyCompleted = tx.execute(s -> runtimeService.completeServiceTask(
            terminatedId, List.of()).getAlreadyCompleted());
        assertThat(alreadyCompleted).as("late completion absorbed as replay").isTrue();

        // Поздний FAILED от воркера по той же задаче — тоже отказ (без ретрая/инцидента).
        tx.execute(s -> {
            runtimeService.failServiceTask(terminatedId, "late failure", null, null, null, null);
            return null;
        });

        assertThat(activities(pi, "dump", ActivityStatus.CREATED)).as("dump still pending").hasSize(1);
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(activities(pi, "miTask", ActivityStatus.CANCELLED)).hasSize(2);
        assertThat(incidents(pi)).as("no incident from the late reports").isZero();

        tx.execute(s -> {
            runtimeService.completeServiceTask(
                activityRepository.findAll().stream()
                    .filter(a -> a.getProcessInstanceId().equals(pi))
                    .filter(a -> "dump".equals(a.getBpmnElementId()))
                    .findFirst().orElseThrow().getId(), List.of());
            return null;
        });
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(pi);
    }

    /**
     * G-P на PostgreSQL: порог 2 из 3 (частичная конвергенция) — после первого
     * завершения тело ещё живо, после второго третий инстанс терминируется.
     */
    @Test
    void twoOfThree_thresholdAfterSecond_terminated_onPostgres() throws Exception {
        UUID pi = deployAndStart("test-c857-mi-task-cc-parallel-threshold2.bpmn");

        List<ActivityEntity> instances = liveInstances(pi, "miTask");
        assertThat(instances).as("3 MI instances at entry").hasSize(3);
        Map<String, ActivityEntity> byItem = byItem(pi, instances);

        ensureTx();
        tx.execute(s -> {
            runtimeService.completeServiceTask(byItem.get("a").getId(), List.of());
            return null;
        });

        assertThat(liveInstances(pi, "miTask"))
            .as("condition not met after the first completion — body still waits").hasSize(2);
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED))
            .as("downstream not created while the condition is not met").isEmpty();

        ensureTx();
        tx.execute(s -> {
            runtimeService.completeServiceTask(byItem.get("b").getId(), List.of());
            return null;
        });

        assertThat(liveInstances(pi, "miTask"))
            .as("LEAK-INVARIANT: third instance terminated by the completionCondition").isEmpty();
        assertThat(activities(pi, "miTask", ActivityStatus.CANCELLED)).hasSize(1);
        assertThat(activities(pi, "miTask", ActivityStatus.COMPLETED)).hasSize(2);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED)).as("MI body closed").hasSize(1);

        List<String> results = resultsOf(pi);
        assertThat(results).hasSize(3);
        assertThat(results.get(0)).isEqualTo("a");
        assertThat(results.get(1)).isEqualTo("b");
        assertThat(results.get(2)).as("terminated slot stays null").isNull();

        tx.execute(s -> {
            runtimeService.completeServiceTask(
                activityRepository.findAll().stream()
                    .filter(a -> a.getProcessInstanceId().equals(pi))
                    .filter(a -> "dump".equals(a.getBpmnElementId()))
                    .findFirst().orElseThrow().getId(), List.of());
            return null;
        });
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(pi);
    }
}
