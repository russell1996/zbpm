package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-40, критерий 7: параллельное завершение N=10 инстансов MI-подпроцесса —
 * ровно один исходящий поток, outputCollection полна и упорядочена по слотам,
 * потерянных обновлений нет.
 *
 * <p>Гонка гарантируется КОНСТРУКЦИЕЙ: 10 реальных потоков стартуют по общему
 * барьеру ({@code CountDownLatch}), каждый завершает СВОЮ задачу через прод-путь
 * {@code RuntimeService.completeUserTask} (своя транзакция на поток — Spring
 * проксирует сервис, join сериализуется {@code lockInstanceFirst} + arrived/
 * expected под instance-lock, как task-хвосты WO-REL-59/63). Без instance-lock
 * в MI-хвосте подпроцесса два потока читали бы один arrived и оба/ни один не
 * доводили бы join — тест ловит это пустым или неполным results.
 *
 * <p>POF-мутация: убрать {@code lockInstanceFirst} из MI-хвоста подпроцесса
 * ({@code FlowNavigator.finishMiSubProcessInstance}) → этот тест RED
 * (results неполон / исходящий поток не один).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiSubProcessRaceIntegrationTests {

    /** N инстансов — не меньше 10 по WO (гонка видна только при настоящей параллельности). */
    private static final int BRANCHES = 10;

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    private UUID deploy() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c840-mi-sub-race.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private static ProcessVariable jsonVar(String name, String json) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.JSON);
        v.setValue(json);
        return v;
    }

    @Test
    void parallelCompletionOfTenInstances_singleJoin_fullOrderedOutput() throws Exception {
        UUID pdId = deploy();
        String items = "[" + IntStream.range(0, BRANCHES)
            .mapToObj(i -> "\"t" + i + "\"").collect(Collectors.joining(",")) + "]";
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of(jsonVar("items", items)));
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        List<ActivityEntity> tasks = new TransactionTemplate(txManager).execute(s ->
            activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .filter(a -> "utProbe".equals(a.getBpmnElementId()) && a.getStatus() == ActivityStatus.CREATED)
                .toList());
        assertThat(tasks).as("10 parallel MI-subprocess instances parked").hasSize(BRANCHES);

        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(BRANCHES);
        List<UUID> taskIds = tasks.stream().map(ActivityEntity::getId).toList();
        for (UUID taskId : taskIds) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    runtimeService.completeUserTask(taskId, List.of());
                } catch (Throwable th) {
                    errors.add(th);
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).as("all 10 completions finished").isTrue();
        assertThat(errors).as("no completion failed: %s", errors).isEmpty();

        List<ActivityEntity> after = new TransactionTemplate(txManager).execute(s ->
            activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .toList());
        assertThat(after.stream()
            .filter(a -> "endRepro".equals(a.getBpmnElementId()) && a.getStatus() == ActivityStatus.COMPLETED)
            .count()).as("exactly one outgoing flow (single endRepro completion)").isEqualTo(1);

        ProcessVariableEntity results = new TransactionTemplate(txManager).execute(s ->
            variableRepository.findByNameAndProcessInstanceId("results", pi).orElseThrow());
        List<String> values = new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        List<String> expected = IntStream.range(0, BRANCHES).mapToObj(i -> "t" + i).toList();
        assertThat(values).as("outputCollection full and ordered by slot").containsExactlyElementsOf(expected);

        long incidents = new TransactionTemplate(txManager).execute(s -> {
            var activityIds = activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .map(ActivityEntity::getId)
                .collect(Collectors.toSet());
            return incidentRepository.findAll().stream()
                .filter(i -> activityIds.contains(i.getActivityId()))
                .count();
        });
        assertThat(incidents).as("no incidents").isZero();
        java.time.Instant completedAt = new TransactionTemplate(txManager).execute(s ->
            queryService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAt).as("instance completed after the race").isNotNull();

        // Стабильность: гонка обязана сходиться не разово — второй прогон тем же
        // барьером (новый инстанс, те же 10 потоков).
        StartProcessInstanceDTO dto2 = new StartProcessInstanceDTO();
        dto2.setProcessDefinitionId(pdId);
        dto2.setVariables(List.of(jsonVar("items", items)));
        UUID pi2 = runtimeService.startProcessInstance(dto2).getId();
        List<ActivityEntity> tasks2 = new TransactionTemplate(txManager).execute(s ->
            activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi2))
                .filter(a -> "utProbe".equals(a.getBpmnElementId()) && a.getStatus() == ActivityStatus.CREATED)
                .toList());
        assertThat(tasks2).hasSize(BRANCHES);
        CopyOnWriteArrayList<Throwable> errors2 = new CopyOnWriteArrayList<>();
        CountDownLatch start2 = new CountDownLatch(1);
        CountDownLatch done2 = new CountDownLatch(BRANCHES);
        List<UUID> taskIds2 = new ArrayList<>(tasks2.stream().map(ActivityEntity::getId).toList());
        for (UUID taskId : taskIds2) {
            Thread t = new Thread(() -> {
                try {
                    start2.await();
                    runtimeService.completeUserTask(taskId, List.of());
                } catch (Throwable th) {
                    errors2.add(th);
                } finally {
                    done2.countDown();
                }
            });
            t.start();
        }
        start2.countDown();
        assertThat(done2.await(120, TimeUnit.SECONDS)).as("second race: all finished").isTrue();
        assertThat(errors2).as("second race: no failure: %s", errors2).isEmpty();
        ProcessVariableEntity results2 = new TransactionTemplate(txManager).execute(s ->
            variableRepository.findByNameAndProcessInstanceId("results", pi2).orElseThrow());
        List<String> values2 = new ObjectMapper().readValue(results2.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values2).as("second race: output full and ordered").containsExactlyElementsOf(expected);
    }
}
