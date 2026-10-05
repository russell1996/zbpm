package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
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
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired
    private com.zorrodev.bpm.engine.handler.CancelingPhaseService cancelingPhaseService;
    @Autowired
    private BpmnService bpmnService;
    @Autowired
    private BpmnParseService bpmnParseService;

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

    /**
     * WO-C8-36 (red-team 1.3): exact-match доказан РАЗЛИЧАЮЩИМ ассертом для всех
     * пяти user-task фаз, а не только для start/end/real.
     *
     * <p>Почему именно чтение колонки в СВЕЖЕЙ транзакции: внутри
     * {@code @Transactional}-теста чтение отдаёт L1-кэш уровня 1 (артефакт теста,
     * не прод-кода). Ассерт «фаза не сдвинулась» обязан быть прочитан из БД, иначе
     * он одинаков при «чужой индекс проигнорирован» и «чужой индекс фазу закрыл» —
     * ровно та неразличающая пара, на которой red-team поймал неприкрытую ветку
     * ASSIGNING (снятие {@code pending.equals(dispatchIndex)} оставляло тест зелёным).
     *
     * <p>Мутация «снять exact-match в ветке X» валит именно этот тест: фаза
     * закрывается чужим индексом (значение становится {@code null} вместо 0).
     */
    @Test
    void phasedMismatchIgnored_allUserTaskPhases_phaseColumnUnmovedInFreshTransaction() throws Exception {
        // 1) ASSIGNING — фаза открывается активацией задачи.
        UUID utPi = inTransactionReturning(() ->
            startUserTask("test-c8-task-listeners-assigning.bpmn", "c8phassign-"));
        UUID assigningId = activityIn(utPi, "review").getId();
        assertThat(pendingIn(() -> dbService.getPendingAssigningListenerIndex(assigningId)))
            .as("assigning-фаза открыта на индексе 0").isEqualTo(0);
        inTransaction(() -> runtimeService.completeServiceTask(assigningId, List.of(),
            ServiceTaskDispatchPhase.ASSIGNING, 7));
        assertThat(pendingIn(() -> dbService.getPendingAssigningListenerIndex(assigningId)))
            .as("чужой assigning/7 обязан оставить фазу на месте")
            .isEqualTo(0);
        // Легитимный индекс после чужого — всё ещё принимается (делегация, не игнор всего).
        inTransaction(() -> runtimeService.completeServiceTask(assigningId, List.of(),
            ServiceTaskDispatchPhase.ASSIGNING, 0));
        assertThat(pendingIn(() -> dbService.getPendingAssigningListenerIndex(assigningId)))
            .as("легитимный assigning/0 закрывает фазу").isNull();

        // 2) CREATING — фаза открывается на входе в user task.
        UUID creatingPi = inTransactionReturning(() ->
            startUserTask("test-c8-task-listeners-two-creating.bpmn", "c8phcreat-"));
        UUID creatingId = activityIn(creatingPi, "review").getId();
        assertThat(pendingIn(() -> dbService.getPendingCreatingListenerIndex(creatingId)))
            .as("creating-фаза открыта на индексе 0").isEqualTo(0);
        inTransaction(() -> runtimeService.completeServiceTask(creatingId, List.of(),
            ServiceTaskDispatchPhase.CREATING, 7));
        assertThat(pendingIn(() -> dbService.getPendingCreatingListenerIndex(creatingId)))
            .as("чужой creating/7 обязан оставить фазу на месте")
            .isEqualTo(0);

        // 3) COMPLETING — фаза открывается завершением задачи (без переменных).
        UUID completingPi = inTransactionReturning(() ->
            startUserTask("test-c8-task-listeners-completing.bpmn", "c8phcompl-"));
        UUID completingId = activityIn(completingPi, "review").getId();
        inTransaction(() -> runtimeService.completeUserTask(completingId, List.of()));
        assertThat(pendingIn(() -> dbService.getPendingCompletingListenerIndex(completingId)))
            .as("completing-фаза открыта на индексе 0").isEqualTo(0);
        inTransaction(() -> runtimeService.completeServiceTask(completingId, List.of(),
            ServiceTaskDispatchPhase.COMPLETING, 7));
        assertThat(pendingIn(() -> dbService.getPendingCompletingListenerIndex(completingId)))
            .as("чужой completing/7 обязан оставить фазу на месте")
            .isEqualTo(0);

        // 4) UPDATING — фаза открывается завершением задачи С переменными.
        UUID updatingPi = inTransactionReturning(() ->
            startUserTask("test-c8-task-listeners-updating.bpmn", "c8phupd-"));
        UUID updatingId = activityIn(updatingPi, "review").getId();
        com.zorrodev.bpm.contract.model.ProcessVariable note =
            new com.zorrodev.bpm.contract.model.ProcessVariable();
        note.setName("note");
        note.setValue("hello");
        note.setType(com.zorrodev.bpm.contract.model.ProcessVariableType.STRING);
        inTransaction(() -> runtimeService.completeUserTask(updatingId, List.of(note)));
        assertThat(pendingIn(() -> dbService.getPendingUpdatingListenerIndex(updatingId)))
            .as("updating-фаза открыта на индексе 0").isEqualTo(0);
        inTransaction(() -> runtimeService.completeServiceTask(updatingId, List.of(),
            ServiceTaskDispatchPhase.UPDATING, 7));
        assertThat(pendingIn(() -> dbService.getPendingUpdatingListenerIndex(updatingId)))
            .as("чужой updating/7 обязан оставить фазу на месте")
            .isEqualTo(0);

        // 5) CANCELING — фаза открывается отменой задачи.
        UUID cancelingPi = inTransactionReturning(() ->
            startUserTask("test-c8-task-listeners-canceling.bpmn", "c8phcanc-"));
        UUID cancelingId = activityIn(cancelingPi, "review").getId();
        // Отменяем задачу ( canceling-фаза открывается только для CANCELED-активности,
        // см. CancelingPhaseService.openForActivity), затем открываем саму фазу.
        inTransaction(() -> dbService.cancelActivity(cancelingId));
        inTransaction(() -> cancelingPhaseService.openForActivity(cancelingId, null));
        assertThat(pendingIn(() -> dbService.getPendingCancelingListenerIndex(cancelingId)))
            .as("canceling-фаза открыта на индексе 0").isEqualTo(0);
        inTransaction(() -> runtimeService.completeServiceTask(cancelingId, List.of(),
            ServiceTaskDispatchPhase.CANCELING, 7));
        assertThat(pendingIn(() -> dbService.getPendingCancelingListenerIndex(cancelingId)))
            .as("чужой canceling/7 обязан оставить фазу на месте")
            .isEqualTo(0);
    }

    /** Читает фазовую колонку в СВЕЖЕЙ транзакции (внутри теста L1-кэш врёт). */
    private Integer pendingIn(java.util.function.Supplier<Integer> read) {
        org.springframework.transaction.support.TransactionTemplate fresh =
            new org.springframework.transaction.support.TransactionTemplate(txManager);
        return fresh.execute(status -> read.get());
    }

    /** Прод-вызов, который сам открывает транзакцию (@Transactional на прод-классе). */
    private void inTransaction(Runnable prodCall) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
            .executeWithoutResult(status -> prodCall.run());
    }

    private UUID startUserTask(String fixture, String idPrefix) {
        try {
            String xml = Files.readString(Paths.get("src/test/files/" + fixture));
            ProcessDefinition model = processDefinitionService.addProcessDefinition(
                xml.replace("c8-task-listeners", idPrefix + UUID.randomUUID().toString().substring(0, 8)));
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            dto.setVariables(List.of());
            return runtimeService.startProcessInstance(dto).getId();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("fixture " + fixture + " unreadable", e);
        }
    }

    /**
     * WO-C8-36 (red-team 1.3 re-pass): тот же класс доказательств для END и
     * ASSIGNING на ПОВЕДЕНЧЕСКОМ уровне (инстанс/парковка), а не на чтении
     * колонки — этот тест остаётся как зеркало сценария «дубликат не завершает
     * инстанс» (критерий 1 в фазе end).
     */
    @Test
    @Transactional
    void phasedMismatchIgnored_endAndAssigningBehaviour() throws Exception {
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

    /** Прод-вызов, возвращающий значение, в СВОЕЙ транзакции. */
    private <T> T inTransactionReturning(java.util.function.Supplier<T> prodCall) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager)
            .execute(status -> prodCall.get());
    }

    private ActivityEntity activityIn(UUID piId, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .findFirst().orElseThrow();
    }

    /**
     * WO-C8-36 (H-3, red-team): phased-ветка {@code completePhased} ВЫБРАСЫВАЛА
     * возврат {@code handle*Listeners}. Совпадение по фазе есть, а обработчик
     * решил «не моё» и вернул {@code false} — legacy-цепочка на этом месте
     * fall-through'ит в хвост (fail-open, {@code :1113} «completes and moves the
     * token»), а phased-путь просто возвращался: тихая зависшая фаза, ни
     * переменных, ни {@code completeActivity}, ни лога, ни метрики.
     *
     * <p>Воспроизведение — ровно сценарий red-team: <b>модель передеплоена без
     * этого слушателя, пока воркер отвечает</b>. Стартуем инстанс на модели с
     ДВУМЯ start-слушателями, закрываем фазу 0 (открывается 1 — «в полёте»),
     * затем деплоим ту же модель БЕЗ второго слушателя и отвечаем фазой
     * {@code start}/{@code 1}: exact-match выполнен, но {@code handleStartListeners}
     * видит {@code startListeners.size()==1} при {@code pending==1} → out-of-bounds
     * → {@code false}.
     *
     * <p>Ассерт РАЗЛИЧАЮЩИЙ (не «объект не null»): до фикса активность остаётся
     * {@code CREATED} и инстанс незавершённым; после — {@code COMPLETED} с
     * {@code completedAt}, ровно как на legacy-пути с тем же входом.
     */
    @Test
    @Transactional
    void phasedMatchHandlerDeclines_modelRedeployedWithoutListener_fallsThroughLikeLegacy() throws Exception {
        UUID piId = startTwoStartListeners();
        UUID activityId = activity(piId).getId();
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId))
            .as("фаза start/0 открыта на старте").isEqualTo(0);

        // Слушатель 0 отработал — фаза 1 открыта, её job в полёте.
        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.START, 0);
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId))
            .as("после start/0 открыта фаза 1").isEqualTo(1);
        assertThat(activity(piId).getStatus()).isEqualTo(ActivityStatus.CREATED);

        // Модель передеплоена БЕЗ второго start-слушателя, пока воркер в полёте.
        redeploySameKeyWithoutSecondStartListener(piId);

        // Ответ воркера по старему job'у: exact-match (pending==1) выполнен,
        // обработчик возвращает false (индекс вне диапазона новой модели).
        runtimeService.completeServiceTask(activityId, List.of(),
            ServiceTaskDispatchPhase.START, 1);

        assertThat(activity(piId).getStatus())
            .as("matched-но-не-обработанная фаза обязана падать в хвост, как legacy, "
                + "а не висеть молча")
            .isEqualTo(ActivityStatus.COMPLETED);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("инстанс завершён — токен сдвинут")
            .isNotNull();
    }

    /**
     * Старт инстанса на модели с двумя start-слушателями (второй нужен, чтобы
     * фаза 1 вообще могла открыться).
     */
    private UUID startTwoStartListeners() throws Exception {
        String xml = Files.readString(Paths.get("src/test/files/test-c8-execution-listeners-two-starts.bpmn"))
            .replace("c8-exec-listeners-2", "c8dupredeploy-" + UUID.randomUUID().toString().substring(0, 8));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    /**
     * Передеплой ТОЙ ЖЕ модели без второго start-слушателя.
     *
     * <p>Честная оговорка о достижимости: обычный деплой той же модели создаёт
     * НОВУЮ версию с новым id, а инстанс держит свой {@code processDefinitionId}
     * (WO-C8-36 отчёт, §H-3) — поэтому штатный redeploy по сети сам по себе
     * подмену модели в полёте не даёт. Состояние «pending=1 при модели в одном
     * слушателе» nonetheless достижимо иначе: фазовая колонка пишется по
     * модели на момент открытия фазы, а читается по модели на момент ответа.
     * Здесь оно воспроизводится ЧЕРЕЗ ПРОД-ЧТЕНИЕ/ПРОД-ЗАПИСЬ модели того же
     * definition id ({@code BpmnService.addProcessDefinition} — тот же
     * post-commit путь, что и деплой), то есть ровно тем контрактом, которым
     * движок берёт модель в полёте. Проверено мутацией: снятие проброса
     * {@code handle*Listeners} в {@code completePhased} возвращает активность в
     * {@code CREATED} и роняет этот тест.
     */
    private void redeploySameKeyWithoutSecondStartListener(UUID piId) throws Exception {
        UUID definitionId = queryService.getProcessInstance(piId).getProcessDefinitionId();
        String key = processDefinitionService.getProcessDefinitionById(definitionId).orElseThrow().getKey();
        String xml = Files.readString(Paths.get("src/test/files/test-c8-execution-listeners-two-starts.bpmn"))
            .replace("c8-exec-listeners-2", key)
            .replace("          <zeebe:executionListener eventType=\"start\" type=\"listener-job-2\" />\n", "");
        bpmnService.addProcessDefinition(definitionId, bpmnParseService.parse(xml));
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
