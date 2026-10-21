package com.zorrodev.bpm.engine.trust;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ElementListenerPhaseEntity;
import com.zorrodev.bpm.engine.entity.ListenerPhase;
import com.zorrodev.bpm.engine.listener.ServiceTaskCompletionProcessor;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ElementListenerPhaseRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-AUDIT-18 (аудит F-14): наблюдаемость «completion, не соответствующая выданной
 * воркеру задаче».
 *
 * <p><b>Что этот тест фиксирует (и почему он такой).</b> Раздача джобы воркеру идёт
 * в очередь по ТИПУ джобы ({@code zorrobpm.jobs.<jobType>}), потребители конкурируют,
 * брокер публикатору не сообщает, КТО взял джобу, а в теле completion нет личности
 * воркера — поэтому «completion от воркера, не получавшего задачу» атрибутировать
 * НЕЛЬЗЯ (см. отчёт §4). Вычислимо другое: completion, которая не соответствует НИ
 * ОДНОЙ задаче, выданной воркеру:
 * <ul>
 *   <li>{@code unknown_task} — такого id движок никогда не создавал;</li>
 *   <li>{@code no_job_issued} — задача создана, но джобу под неё воркеру НЕ
 *       выдавали: нет строки {@code service_tasks} и нет открытой listener-фазы
 *       (в т.ч. user task, который завершает человек через REST — движок таких
 *       задач воркерам не выдаёт НИКОГДА).</li>
 * </ul>
 *
 * <p>Тест — на настоящем H2-контексте движка ({@code TestMain}) с НАСТОЯЩИМИ
 * строками {@code activities}/{@code element_listener_phase}: никаких моков на
 * уровне БД, иначе проверка доказывала бы не работу монитора, а работу мока.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class CompletionTrustMonitorIntegrationTests {

    /** Метрика: completion, не соответствующая выданной воркеру задаче (серия на причину). */
    private static final String METER = "zbpm.completion.unissued";

    @Autowired private CompletionTrustMonitor monitor;
    @Autowired private ServiceTaskCompletionProcessor completionProcessor;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ElementListenerPhaseRepository phaseRepository;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private MeterRegistry meterRegistry;

    private double unissued(String reason) {
        return meterRegistry.find(METER).tag("reason", reason).counter().count();
    }

    private UUID deploy(String fixture) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fixture));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private UUID start(UUID pdId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ActivityEntity activityOf(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals(elementId))
            .findFirst().orElseThrow();
    }

    /**
     * WO-AUDIT-18 (1): id, которого движок никогда не создавал. Ни одна задача с
     * таким id не выдавалась воркеру — completion по нему не соответствует выданной
     * задаче (подделка/чужая/устаревшая).
     */
    @Test
    void completionForIdTheEngineNeverCreated_isCountedAsUnknownTask() {
        UUID neverCreated = UUID.randomUUID();
        double before = unissued(CompletionTrustMonitor.REASON_UNKNOWN_TASK);

        String reason = monitor.inspect(neverCreated);

        assertThat(reason)
            .as("completion по id, которого движок никогда не создавал, обязана быть помечена")
            .isEqualTo(CompletionTrustMonitor.REASON_UNKNOWN_TASK);
        assertThat(unissued(CompletionTrustMonitor.REASON_UNKNOWN_TASK) - before)
            .as("аномалия обязана быть ИЗМЕРИМА, а не только залогирована")
            .isEqualTo(1.0);
    }

    /**
     * WO-AUDIT-18 (2): user task. Движок НИКОГДА не выдаёт user task воркеру
     * (её завершает человек через REST) — значит completion по её id не
     * соответствует выданной задаче. Это ровно тот случай, который F-14 называет
     * «completion для задачи, не выданной этому воркеру», и который сегодня виден
     * только тем, что completion ПРОХОДИТ (см. отчёт §5, находка F-14-A).
     */
    @Test
    void completionForUserTask_isCountedAsNoJobIssued() throws Exception {
        UUID pi = start(deploy("test-metrics-park.bpmn"));
        ActivityEntity userTask = activityOf(pi, "taskM");
        assertThat(userTask.getType())
            .as("фикстура должна parksить на user task — иначе тест проверяет не то")
            .isEqualTo(BpmnElementType.USER_TASK);
        double before = unissued(CompletionTrustMonitor.REASON_NO_JOB_ISSUED);

        String reason = monitor.inspect(userTask.getId());

        assertThat(reason)
            .as("user task воркерам не выдаётся — completion по ней не соответствует выданной задаче")
            .isEqualTo(CompletionTrustMonitor.REASON_NO_JOB_ISSUED);
        assertThat(unissued(CompletionTrustMonitor.REASON_NO_JOB_ISSUED) - before)
            .isEqualTo(1.0);
    }

    /**
     * WO-AUDIT-18 (2-бис): аномалия — не значит, что completion ПРОШЛА. Задача,
     * которую воркеру не выдавали, не должна завершиться от чужого completion'а:
     * движок сам поглощает такой результат ({@code rejectPhaseOnlyCompletion} — у
     * user task без открытой listener-фазы). Тест фиксирует это свойство, чтобы
     * «мониторим» не превратилось в «молча пропускаем».
     *
     * <p>Проверено живым зондом 2026-10-21 (движок пишет «Ignoring service-task
     * completion of USER_TASK … with no listener phase in flight» и считает это
     * replay'ом) — здесь то же поведение зафиксировано как ТЕСТ.
     */
    @Test
    void completionForUserTask_doesNotCompleteTheUserTask() throws Exception {
        UUID pi = start(deploy("test-metrics-park.bpmn"));
        ActivityEntity userTask = activityOf(pi, "taskM");

        // Ровно тот вызов, который делает AMQP-путь приёма completion'ов.
        runtimeService.completeServiceTask(userTask.getId(), List.of());

        assertThat(activityRepository.findById(userTask.getId()).orElseThrow().getStatus())
            .as("чужой completion не должен завершать задачу, которую воркеру не выдавали")
            .isNotEqualTo(ActivityStatus.COMPLETED);
    }

    /**
     * WO-AUDIT-18 (2-бис): user task с ОТКРЫТОЙ listener-фазой (WO-C8-21/24/28).
     * Под user task выдаётся listener-джоба, и её serviceTaskId — это id САМОЙ
     * активности. Пока фаза открыта, такой id занят легальным worker-job'ом —
     * монитор не должен считать его аномалией (иначе метрика шумит на каждом
     * штатном listener-completion'е).
     */
    @Test
    void completionForUserTaskWithOpenListenerPhase_isNotCounted() throws Exception {
        UUID pi = start(deploy("test-metrics-park.bpmn"));
        ActivityEntity userTask = activityOf(pi, "taskM");
        userTask.setPendingCreatingListenerIndex(0);
        activityRepository.save(userTask);
        double before = unissued(CompletionTrustMonitor.REASON_NO_JOB_ISSUED);

        String reason = monitor.inspect(userTask.getId());

        assertThat(reason)
            .as("открытая listener-фаза = под этот id выдана listener-джоба")
            .isNull();
        assertThat(unissued(CompletionTrustMonitor.REASON_NO_JOB_ISSUED) - before).isZero();
    }

    /**
     * WO-AUDIT-20 (3): повторная доставка completion по УЖЕ завершённой задаче.
     *
     * <p>Задача закрыта (человеком через REST — движок закрыл activity в COMPLETED), и
     * приходит ВТОРАЯ доставка результата по тому же id. Это НЕ «джобу под неё
     * воркеру не выдавали»: джоба была выдана, задача просто уже закрыта, а движок
     * такой повтор поглощает guard'ом и считает в
     * {@code zbpm.completion.replay{kind=…}} (WO-AUDIT-8). Вердикт монитора
     * {@code no_job_issued} здесь — ложный (он утверждает о СОСТОЯНИИ задачи, которой
     * как рабочей единицы уже нет) и даёт шум: одна аномалия — много WARN-строк.
     *
     * <p>Живая аномалия НЕ теряется: для ЖИВОЙ (не завершённой) user task'и
     * {@code no_job_issued} по-прежнему считается — см.
     * {@code completionForUserTask_isCountedAsNoJobIssued}.
     */
    @Test
    void completionForAlreadyFinishedTask_isNotCountedAsNoJobIssued() throws Exception {
        UUID pi = start(deploy("test-metrics-park.bpmn"));
        ActivityEntity userTask = activityOf(pi, "taskM");

        // Задача закрыта — после этого приходит повторная доставка результата.
        runtimeService.completeUserTask(userTask.getId(), List.of());
        assertThat(activityRepository.findById(userTask.getId()).orElseThrow().getStatus())
            .as("фикстура должна закрыть задачу — иначе тест проверяет не то")
            .isEqualTo(ActivityStatus.COMPLETED);
        double before = unissued(CompletionTrustMonitor.REASON_NO_JOB_ISSUED);

        String reason = monitor.inspect(userTask.getId());

        assertThat(reason)
            .as("задача уже закрыта — это повтор (replay), а не «джобу под неё не выдавали»")
            .isNull();
        assertThat(unissued(CompletionTrustMonitor.REASON_NO_JOB_ISSUED) - before)
            .as("повтор не должен засчитываться как «джобу не выдавали»")
            .isZero();
    }

    /**
     * WO-AUDIT-18 (3): штатный случай — service task, которую движок воркеру выдал.
     * Аномалией НЕ считается и счётчик не растёт (иначе метрика шумит на каждом
     * нормальном completion).
     */
    @Test
    void completionForServiceTask_isNotCounted() throws Exception {
        UUID pi = start(deploy("test3.bpmn"));
        ActivityEntity serviceTask = activityOf(pi, "serviceTask1");
        assertThat(serviceTask.getType()).isEqualTo(BpmnElementType.SERVICE_TASK);
        double unknownBefore = unissued(CompletionTrustMonitor.REASON_UNKNOWN_TASK);
        double notServiceBefore = unissued(CompletionTrustMonitor.REASON_NO_JOB_ISSUED);

        String reason = monitor.inspect(serviceTask.getId());

        assertThat(reason).as("штатный service task — не аномалия").isNull();
        assertThat(unissued(CompletionTrustMonitor.REASON_UNKNOWN_TASK) - unknownBefore).isZero();
        assertThat(unissued(CompletionTrustMonitor.REASON_NO_JOB_ISSUED) - notServiceBefore).isZero();
    }

    /**
     * WO-AUDIT-18 (4): element-listener phase (WO-C8-25). У фазовой джобы НЕТ
     * activity-строки — её carrier-id и есть serviceTaskId. Без этой проверки
     * монитор считал бы каждый штатный phase-completion как «unknown_task».
     */
    @Test
    void completionForElementListenerPhase_isNotCounted() {
        ElementListenerPhaseEntity phase = new ElementListenerPhaseEntity();
        phase.setId(UUID.randomUUID());
        phase.setProcessInstanceId(UUID.randomUUID());
        phase.setTokenId(UUID.randomUUID());
        phase.setBpmnElementId("someGateway");
        phase.setPhase(ListenerPhase.START);
        phase.setListenerIndex(0);
        phase.setRetriesRemaining(3);
        phase.setCreatedAt(Instant.now());
        phaseRepository.save(phase);
        double before = unissued(CompletionTrustMonitor.REASON_UNKNOWN_TASK);

        String reason = monitor.inspect(phase.getId());

        assertThat(reason)
            .as("фазовая джоба не имеет activity-строки по построению — это не аномалия")
            .isNull();
        assertThat(unissued(CompletionTrustMonitor.REASON_UNKNOWN_TASK) - before).isZero();
    }

    /**
     * WO-AUDIT-18 (5): ПРОВОДКА. Монитор обязан стоять на ЖИВОМ пути приёма
     * completion'ов, а не быть вызываемым по требованию. Тест гоняет НАСТОЯЩИЙ
     * процессор (тот же бин, что поднимает Spring) с настоящим монитором: счётчик
     * обязан вырасти. Само завершение при этом падает (id несуществующий) — так и
     * должно быть: монитор наблюдает, но НЕ меняет исход.
     */
    @Test
    void completionOnTheWorkerChannelPath_isCountedByTheRealProcessor() {
        UUID neverCreated = UUID.randomUUID();
        double before = unissued(CompletionTrustMonitor.REASON_UNKNOWN_TASK);
        ServiceTaskCompleted completion = new ServiceTaskCompleted();
        completion.setServiceTaskId(neverCreated);
        completion.setStatus("SUCCESS");

        assertThatThrownBy(() -> completionProcessor.process(completion))
            .as("завершение по несуществующей задаче обязано падать и дальше — монитор не меняет исход")
            .isInstanceOf(NoSuchElementException.class);

        assertThat(unissued(CompletionTrustMonitor.REASON_UNKNOWN_TASK) - before)
            .as("монитор обязан быть вызван на живом пути приёма completion'ов")
            .isEqualTo(1.0);
    }
}
