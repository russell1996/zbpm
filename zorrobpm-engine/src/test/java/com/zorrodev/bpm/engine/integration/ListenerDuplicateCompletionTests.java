package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.ServiceTaskDispatchPhase;
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
 * WO-C8-36 (CR-01): дубликат completion-сообщения слушателя НЕ завершает активность
 * без результата реального задания; устаревшая FAILED не расходует бюджет нового вызова.
 *
 * <p>Сценарий — дословно из внешнего ревью: start → service task с одним
 * start-слушателем → end. Слушатель шлёт SUCCESS один раз (реальное задание уходит
 * на исполнение, активность CREATED), затем ДО результата реального задания ТО ЖЕ
 * событие приходит повторно.
 *
 * <p>POF-история: RED-версия шла через плоский путь без идентификатора вызова и
 * падала дословно как в ревью (COMPLETED вместо CREATED; бюджет 3→2). GREEN —
 * через phased-путь (phase/index — то, что несёт починенный воркер).
 *
 * <p>Legacy-плоский путь осознанно остаётся fail-open (обратная совместимость со
 * старыми воркерами; см. отчёт) — фиксируется характеризующим тестом ниже, а не
 * молчаливым поведением.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ListenerDuplicateCompletionTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private DBService dbService;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private ServiceTaskRepository serviceTaskRepository;

    private static String bpmn() throws Exception {
        // Та же фикстура, что у соседа (C8-11), минус end-listener — изолируем START-фазу.
        String xml = Files.readString(Paths.get("src/test/files/test-c8-execution-listeners.bpmn"));
        return xml.replace("          <zeebe:executionListener eventType=\"end\" type=\"listener-job\" />\n", "");
    }

    private UUID start() throws Exception {
        String xml = bpmn().replace("c8-exec-listeners",
            "c8dup-" + UUID.randomUUID().toString().substring(0, 8));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ActivityEntity activity(UUID piId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("svc"))
            .findFirst().orElseThrow();
    }

    private int retries(UUID activityId) {
        return serviceTaskRepository.findById(activityId).orElseThrow().getRetriesRemaining();
    }

    @Test
    @Transactional
    void duplicateListenerSuccess_doesNotCompleteActivityWithoutRealJobResult() throws Exception {
        UUID piId = start();
        UUID activityId = activity(piId).getId();

        assertThat(activity(piId).getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isEqualTo(0);

        // Первый SUCCESS слушателя (фаза start/0 — штамп движка, эхо воркера):
        // реальное задание уходит, активность CREATED.
        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.START, 0);
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isNull();
        assertThat(activity(piId).getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();

        // Дубликат ТОГО ЖЕ вызова (start/0) — до результата реального задания.
        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.START, 0);

        // CR-01: активность обязана остаться CREATED, инстанс — незавершённым.
        assertThat(activity(piId).getStatus())
            .as("повторный SUCCESS слушателя не завершает активность без результата реального задания")
            .isEqualTo(ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("корневой инстанс не завершается дубликатом слушателя")
            .isNull();

        // А результат РЕАЛЬНОГО задания (фаза real) — завершает штатно.
        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.REAL, null);
        assertThat(activity(piId).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
    }

    @Test
    @Transactional
    void staleListenerFailure_doesNotConsumeRealJobRetryBudget() throws Exception {
        UUID piId = start();
        UUID activityId = activity(piId).getId();

        // Слушатель отработал, в полёте реальное задание со своим бюджетом.
        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.START, 0);
        int budgetBefore = retries(activityId);
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isNull();

        // Устаревшая FAILED слушателя (start/0 — фаза уже закрыта).
        runtimeService.failServiceTask(activityId, "stale listener boom", null,
            ServiceTaskDispatchPhase.START, 0);

        // CR-01/крит.2: бюджет нового (реального) вызова не тронут, фаза не сдвинута.
        assertThat(retries(activityId))
            .as("устаревшая FAILED слушателя не расходует лимит ретраев нового вызова")
            .isEqualTo(budgetBefore);
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isNull();
        assertThat(activity(piId).getStatus()).isEqualTo(ActivityStatus.CREATED);

        // А FAILED реального задания — декрементит штатно (делегация, не игнор всего).
        runtimeService.failServiceTask(activityId, "real boom", null,
            ServiceTaskDispatchPhase.REAL, null);
        assertThat(retries(activityId)).isEqualTo(budgetBefore - 1);
    }

    @Test
    @Transactional
    void legacyFlatDuplicate_stillCompletesFailOpen_characterization() throws Exception {
        // WO-C8-36, осознанное решение: плоский вызов без фазы (старый воркер/REST)
        // идёт legacy-путём fail-open — дубликат завершает активность, как до WO.
        // Защита CR-01 действует только на phased-сообщения нового воркера.
        UUID piId = start();
        UUID activityId = activity(piId).getId();

        runtimeService.completeServiceTask(activityId, List.of());
        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(activity(piId).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
    }
}
