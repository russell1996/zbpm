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
    void duplicateFailureOpenPhase_sameCompletionId_consumesBudgetOnce() throws Exception {
        // WO-C8-36 (red-team HOLD-1): confirm-loss переотправка ТОЙ ЖЕ отправки
        // (тот же completionId — воркер переигрывает объект из resultCache) НЕ
        // должна дважды расходовать бюджет одного логического сбоя.
        UUID piId = start();
        UUID activityId = activity(piId).getId();

        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.START, 0);
        int budgetBefore = retries(activityId);

        // Первая FAILED отправки cid-1: бюджет −1, фаза остаётся открытой (редispatch).
        runtimeService.failServiceTask(activityId, "boom", null,
            ServiceTaskDispatchPhase.REAL, null, "cid-1");
        assertThat(retries(activityId)).isEqualTo(budgetBefore - 1);
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isNull();

        // Дубликат ТОЙ ЖЕ отправки (confirm потерян, брокер переотдал): игнор.
        runtimeService.failServiceTask(activityId, "boom", null,
            ServiceTaskDispatchPhase.REAL, null, "cid-1");
        assertThat(retries(activityId))
            .as("дубликат открытой фазы с тем же completionId не расходует бюджет повторно")
            .isEqualTo(budgetBefore - 1);

        // НОВАЯ отправка (редispatch вернулся с новым completionId): бюджет −1.
        runtimeService.failServiceTask(activityId, "boom again", null,
            ServiceTaskDispatchPhase.REAL, null, "cid-2");
        assertThat(retries(activityId))
            .as("новая отправка consumes budget — цикл ретраев не застревает")
            .isEqualTo(budgetBefore - 2);
    }

    @Test
    @Transactional
    void phasedMismatchIgnored_endAndAssigningBranches() throws Exception {
        // WO-C8-36 (red-team HOLD-5, re-pass: имя честное — покрыты end+assigning;
        // start/real — соседние тесты выше; creating/completing/updating/canceling
        // mismatch идут ТОЙ ЖЕ строкой exact-match (failUserTaskPhase) и покрыты
        // делегацией через общий метод — остаток зафиксирован, не overclaim).
        // Каждая phased-ветка обязана игнорить чужой вызов БЕЗ fall-through в
        // хвост. Матрица: end/assigning фазы открыты, дубликат с чужим индексом —
        // фаза не двигается, токен стоит.
        // 1) end-фаза: открываем (start done → real done → end/0),
        // дубликат end/7 — игнор.
        UUID piId = startEndPhase();
        UUID activityId = activity(piId).getId();
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isEqualTo(0);
        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.START, 0);
        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.REAL, null);
        // Фаза end/0 открыта (лог "Real job done, opening end-listener phase").
        // Поведенческое доказательство (без чтения колонки — в общей тестовой
        // транзакции L1-кэш отдаёт stale null, артефакт @Transactional-теста, не
        // прод-кода): чужой end/7 НЕ закрывает фазу — легитимный end/0 после
        // него всё ещё работает и завершает инстанс. Упади end/7 в хвост (дефект
        // CR-01) — инстанс завершился бы уже на нём, а end/0 пришёл бы в done.
        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.END, 7);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("чужой end-индекс не завершает инстанс (фаза не закрыта)")
            .isNull();
        assertThat(activity(piId).getStatus()).isEqualTo(ActivityStatus.CREATED);
        // Легитимный end/0 — закрывает фазу штатно (делегация, не игнор всего).
        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.END, 0);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();

        // 2) user-task assigning-фаза: открывается АКТИВАЦИЕЙ задачи (парковка
        // alice, см. соседа C8-28); дубликат чужого индекса — игнор.
        UUID utPi = startUserTaskAssigning();
        UUID utId = activityIn(utPi, "review").getId();
        assertThat(dbService.getPendingAssigningListenerIndex(utId)).isEqualTo(0);
        runtimeService.completeServiceTask(utId, List.of(),
            ServiceTaskDispatchPhase.ASSIGNING, 7);
        // Поведенческое доказательство (та же L1-артефактность чтения колонки):
        // чужой assigning/7 НЕ закрывает фазу — легитимный assigning/0 после
        // него всё ещё работает (alice применяется, фаза закрыта).
        assertThat(activityIn(utPi, "review").getStatus()).isEqualTo(ActivityStatus.CREATED);
        runtimeService.completeServiceTask(utId, List.of(),
            ServiceTaskDispatchPhase.ASSIGNING, 0);
        assertThat(dbService.getPendingAssignee(utId)).isNull();
    }

    private UUID startEndPhase() throws Exception {
        String xml = Files.readString(Paths.get("src/test/files/test-c8-execution-listeners-start-end.bpmn"));
        xml = xml.replace("c8-exec-listeners-start-end",
            "c8dupend-" + UUID.randomUUID().toString().substring(0, 8));
        // Фаза start/0 открывается при входе (энтри-поинт start listener'а),
        // real — после её закрытия. Вырезать ничего не надо: фикстура уже
        // start+end, именно эта связка и нужна.
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private UUID startUserTaskAssigning() throws Exception {
        // WO-C8-28 путь: старт инстанса АКТИВИРУЕТ user task (парковка alice,
        // assigning/0 открыт, assigning-job в outbox). Повторяет соседа C8-28.
        String xml = Files.readString(Paths.get("src/test/files/test-c8-task-listeners-assigning.bpmn"));
        xml = xml.replace("c8-task-listeners-assigning",
            "c8duput-" + UUID.randomUUID().toString().substring(0, 8));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of());
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        assertThat(activityIn(piId, "review").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(dbService.getPendingAssignee(activityIn(piId, "review").getId())).isEqualTo("alice");
        return piId;
    }

    private ActivityEntity activityIn(UUID piId, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .findFirst().orElseThrow();
    }

    @Test
    @Transactional
    void legacyFlatDuplicate_stillCompletesFailOpen_characterization() throws Exception {
        // WO-C8-36 (red-team HOLD-2 — осознанный остаточный риск, см. отчёт):
        // плоский вызов без фазы (старый воркер/REST) идёт legacy-путём
        // fail-open — дубликат завершает активность, как до WO. Защита CR-01
        // действует только на phased-сообщения нового воркера. Менять семантику
        // REST (29 существующих тестов + публичный контракт) в этом WO нельзя.
        UUID piId = start();
        UUID activityId = activity(piId).getId();

        runtimeService.completeServiceTask(activityId, List.of());
        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(activity(piId).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
    }
}
