package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorResultDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-37: прерывающий event sub-process ПРЕРЫВАЕТ основной поток.
 *
 * <p>Дефект (WO-OPS-28 ФАЗА 3, находка Н-1): прерывающий ESP гасил activity
 * прерванного скоупа, но НЕ гасил поток, который этот скоуп продолжал. Основной
 * поток после броска сигнала/сообщения уходил дальше: в завершённом инстансе
 * оставались живая activity и открытая строка service_task — воркер забирал задачу
 * мёртвого инстанса (живой Zeebe 8.5.0: поток прерван, остатка нет).
 *
 * <p>Что здесь проверяется (по одному тесту на путь прерывания):
 * <ul>
 *   <li><b>сигнал</b> — бросок с основной ветки: поток после броска не продолжается;</li>
 *   <li><b>сообщение</b> — то же для message throw;</li>
 *   <li><b>send task</b> — то же для BPMN-стандартного send task (messageRef);</li>
 *   <li><b>непрерывающий ESP</b> — основной поток ОБЯЗАН продолжиться (регрессия);</li>
 *   <li><b>вложенный ESP</b> — гасится свой родительский scope, ветвь ВНЕ scope жива;</li>
 *   <li><b>ошибка из сервис-задачи</b> — error-ESP заменяет основной поток;</li>
 *   <li><b>таймер ESP</b> — таймерный ESP гасит припаркованный основной поток.</li>
 * </ul>
 *
 * <p>Инвариант утечек (G-P) проверяется глобальным чекером
 * {@code LeakInvariantTestListener} (режим enforce): там, где тест доводит свой
 * инстанс до завершения, «мусор» в завершённом инстансе роняет сборку. Исключение —
 * тест вложенного ESP: там инстанс намеренно остаётся живым (ветвь ВНЕ прерванного
 * scope ещё работает), и инвариант по нему не проверяется — дефект завершения
 * вложенного scope записан в отчёте §6 (WO-OPS-37, Н-1), а не здесь.
 *
 * <p>Каждый тест — в своей транзакции (@Transactional): данные откатываются, общая
 * H2 не засоряется, а коммита нет — поэтому инвариант проверяется по тому, что тест
 * видел в своей транзакции (тот же контур, что у остальных интеграционных тестов).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class InterruptingEventSubProcessStopsMainFlowIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TimerJobRepository timerJobRepository;
    @Autowired private TimerJobExecutor timerJobExecutor;
    @Autowired private DBService dbService;
    @Autowired private PlatformTransactionManager transactionManager;

    /** Окно создания таймер-джобов этого теста (чистка — см. @AfterEach). */
    private Instant testStartedAt;

    @BeforeEach
    void armCleanupWindow() {
        testStartedAt = Instant.now();
    }

    @AfterEach
    void cleanup() {
        // Таймер-джобы общей H2: удаляем окно, созданное этим тестом (иначе они
        // засоряют чужие тесты, считающие DueTimerJobs).
        Instant since = testStartedAt;
        inNewTx(() -> {
            List<TimerJobEntity> mine = timerJobRepository.findAll().stream()
                .filter(e -> e.getCreatedAt() != null && !e.getCreatedAt().isBefore(since))
                .toList();
            timerJobRepository.deleteAll(mine);
        });
    }

    /** Действие в своей транзакции (REQUIRES_NEW) — коммит виден следующему шагу. */
    private void inNewTx(Runnable action) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.execute(status -> {
            action.run();
            return null;
        });
    }

    private UUID start(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO started = runtimeService.startProcessInstance(dto);
        return started.getId();
    }

    private List<ActivityEntity> activities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
    }

    /** Живая (CREATED) activity элемента — последняя созданная. */
    private ActivityEntity live(UUID pi, String elementId) {
        List<ActivityEntity> live = activities(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .toList();
        return live.isEmpty() ? null : live.get(live.size() - 1);
    }

    /** Последняя activity элемента в любом статусе. */
    private ActivityEntity any(UUID pi, String elementId) {
        List<ActivityEntity> all = activities(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .toList();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private long incidentsOf(UUID pi) {
        List<UUID> activityIds = activities(pi).stream().map(ActivityEntity::getId).toList();
        return incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count();
    }

    private void completeUserTask(ActivityEntity activity) {
        runtimeService.completeUserTask(activity.getId(), List.of());
    }

    // ==================================================================
    // Пути прерывания
    // ==================================================================

    @Transactional
    @Test
    void signalThrow_interruptingEsp_stopsTheMainFlow() throws Exception {
        UUID pi = start("test-ops37-esp-signal-interrupting.bpmn");

        completeUserTask(live(pi, "work"));

        // Прерывающий ESP поймал сигнал и заменил основной поток: activity после
        // броска НЕ создана (Zeebe 8.5: поток после броска сигнала не продолжается).
        assertThat(activities(pi))
            .as("the main flow must not continue past the interrupting event sub-process")
            .extracting(ActivityEntity::getBpmnElementId)
            .doesNotContain("after");
        assertThat(any(pi, "throwSig").getStatus())
            .as("the throw event itself executed (Zeebe: completed, not terminated)")
            .isEqualTo(ActivityStatus.COMPLETED);
        assertThat(live(pi, "evWork"))
            .as("the event sub-process body runs")
            .isNotNull();

        completeUserTask(live(pi, "evWork"));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the instance completes by the event sub-process body")
            .isNotNull();
        assertThat(activities(pi)).extracting(ActivityEntity::getBpmnElementId).doesNotContain("after");
        assertThat(activities(pi)).extracting(ActivityEntity::getBpmnElementId).doesNotContain("mainEnd");
        assertThat(incidentsOf(pi)).isZero();
    }

    @Transactional
    @Test
    void messageThrow_interruptingEsp_stopsTheMainFlow() throws Exception {
        UUID pi = start("test-ops37-esp-message-interrupting.bpmn");

        completeUserTask(live(pi, "work"));

        assertThat(activities(pi))
            .as("the main flow must not continue past the interrupting event sub-process")
            .extracting(ActivityEntity::getBpmnElementId)
            .doesNotContain("after");
        assertThat(any(pi, "throwMsg").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(live(pi, "evWork")).isNotNull();

        completeUserTask(live(pi, "evWork"));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(activities(pi)).extracting(ActivityEntity::getBpmnElementId).doesNotContain("after");
        assertThat(activities(pi)).extracting(ActivityEntity::getBpmnElementId).doesNotContain("mainEnd");
        assertThat(incidentsOf(pi)).isZero();
    }

    @Transactional
    @Test
    void sendTask_interruptingEsp_stopsTheMainFlow() throws Exception {
        UUID pi = start("test-ops37-esp-sendtask-interrupting.bpmn");

        completeUserTask(live(pi, "work"));

        assertThat(activities(pi))
            .as("the main flow must not continue past the interrupting event sub-process")
            .extracting(ActivityEntity::getBpmnElementId)
            .doesNotContain("after");
        assertThat(any(pi, "throwSend").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(live(pi, "evWork")).isNotNull();

        completeUserTask(live(pi, "evWork"));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(activities(pi)).extracting(ActivityEntity::getBpmnElementId).doesNotContain("after");
        assertThat(activities(pi)).extracting(ActivityEntity::getBpmnElementId).doesNotContain("mainEnd");
        assertThat(incidentsOf(pi)).isZero();
    }

    @Transactional
    @Test
    void nonInterruptingEsp_theMainFlowContinues() throws Exception {
        // Регрессионная защита: НЕПРЕРЫВАЮЩИЙ ESP не гасит основной поток —
        // правка прерывания не должна задеть и этот случай.
        UUID pi = start("test-ops37-esp-signal-noninterrupting.bpmn");

        completeUserTask(live(pi, "work"));

        assertThat(live(pi, "after"))
            .as("the main flow continues after a non-interrupting event sub-process")
            .isNotNull();
        assertThat(live(pi, "evWork"))
            .as("the non-interrupting event sub-process body runs in parallel")
            .isNotNull();

        completeUserTask(live(pi, "evWork"));
        completeUserTask(live(pi, "after"));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(activities(pi)).anyMatch(a -> a.getBpmnElementId().equals("mainEnd")
            && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(incidentsOf(pi)).isZero();
    }

    @Transactional
    @Test
    void nestedInterruptingEsp_stopsOnlyItsOwnScope() throws Exception {
        // Вложенный ESP гасит свой РОДИТЕЛЬСКИЙ scope (scope-confinement): поток
        // внутри подпроцесса после броска не продолжается, а ветвь ВНЕ подпроцесса
        // живёт дальше.
        UUID pi = start("test-ops37-esp-nested-interrupting.bpmn");

        assertThat(activities(pi))
            .as("the flow inside the interrupted scope must not continue")
            .extracting(ActivityEntity::getBpmnElementId)
            .doesNotContain("after");
        assertThat(any(pi, "throwSig").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(live(pi, "outsideTask"))
            .as("the branch OUTSIDE the interrupted scope survives")
            .isNotNull();
        assertThat(live(pi, "evWork")).isNotNull();
        assertThat(incidentsOf(pi)).isZero();
    }

    @Transactional
    @Test
    void serviceTaskError_interruptingEsp_stopsTheMainFlow() throws Exception {
        UUID pi = start("test-ops37-esp-error-interrupting.bpmn");

        ActivityEntity work = live(pi, "work");
        assertThat(work).as("the service task is parked as a job").isNotNull();
        ThrowErrorDTO dto = new ThrowErrorDTO();
        dto.setErrorCode("E-1");
        ThrowErrorResultDTO thrown = runtimeService.throwServiceTaskError(work.getId(), dto);

        assertThat(thrown.isHandled())
            .as("the error was caught by the event sub-process")
            .isTrue();
        assertThat(activities(pi))
            .as("the main flow must not continue past the interrupting event sub-process")
            .extracting(ActivityEntity::getBpmnElementId)
            .doesNotContain("after");
        assertThat(live(pi, "evWork")).isNotNull();

        completeUserTask(live(pi, "evWork"));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(activities(pi)).extracting(ActivityEntity::getBpmnElementId).doesNotContain("after");
        assertThat(activities(pi)).extracting(ActivityEntity::getBpmnElementId).doesNotContain("mainEnd");
        assertThat(incidentsOf(pi)).isZero();
    }

    @Test
    void espTimer_interruptingEsp_stopsTheMainFlow() throws Exception {
        // Таймерный ESP: основной поток стоит в задаче "work"; когда таймер
        // срабатывает, прерывание гасит и эту задачу, и поток после неё.
        UUID[] pi = new UUID[1];
        inNewTx(() -> {
            try {
                pi[0] = start("test-ops37-esp-timer-interrupting.bpmn");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        inNewTx(this::fireDueTimers);

        assertThat(any(pi[0], "work").getStatus())
            .as("the parked main task dies with the interrupted scope")
            .isEqualTo(ActivityStatus.CANCELLED);
        assertThat(activities(pi[0]))
            .as("the main flow must not continue past the interrupting event sub-process")
            .extracting(ActivityEntity::getBpmnElementId)
            .doesNotContain("after");
        assertThat(live(pi[0], "evWork")).isNotNull();

        inNewTx(() -> completeUserTask(live(pi[0], "evWork")));

        assertThat(queryService.getProcessInstance(pi[0]).getCompletedAt()).isNotNull();
        assertThat(activities(pi[0])).extracting(ActivityEntity::getBpmnElementId).doesNotContain("after");
        assertThat(activities(pi[0])).extracting(ActivityEntity::getBpmnElementId).doesNotContain("mainEnd");
    }

    /** Стрельба по просроченным таймер-джобам, созданным ЭТИМ тестом. */
    private void fireDueTimers() {
        dbService.findDueTimerJobs(Instant.now().plusSeconds(3600)).stream()
            .filter(j -> j.getCreatedAt() != null && !j.getCreatedAt().isBefore(testStartedAt))
            .forEach(j -> timerJobExecutor.fire(j));
    }
}
