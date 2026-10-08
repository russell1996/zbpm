package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.TimerStartJob;
import com.zorrodev.bpm.engine.listener.ServiceTaskCompleteListener;
import com.zorrodev.bpm.engine.service.AdmissionLease;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * WO-ENG-35 (NEW2-16): охранный тест гейтования admission-гейта на входах.
 *
 * <p>Три ловушки против «тест, который не может краснеть» (прецедент
 * {@code DeactivationWakeupCoverageTest}):
 * <ul>
 *   <li>литеральные числа методов (12/2/2) — новый вход без гейта валит тест,
 *       пока список не пересмотрен;</li>
 *   <li>поведенческие проверки (InOrder, отсутствие аннотации), а не только
 *       подсчёт строк;</li>
 *   <li>мутации: убрать {@code gated(...)} в одном методе → тест 1 КРАСНЫЙ;
 *       вернуть {@code @Transactional} на listener → тест 2 КРАСНЫЙ; убрать
 *       гейт из одной fire-лямбды → тест 3 КРАСНЫЙ; убрать поле/метод →
 *       компиляция падает (тест не нужен).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdmissionGatingWiringTest {

    @Mock private DBService dbService;
    @Mock private ScriptService scriptService;
    @Mock private TimerJobExecutor timerJobExecutor;
    @Mock private TimerStartJobExecutor timerStartJobExecutor;

    private static final Path RUNTIME_RESOURCE =
        Path.of("../zorrobpm-rest/src/main/java/com/zorrodev/bpm/rest/resource/RuntimeResource.java");

    /** WO-ENG-35: все 12 входов RuntimeResource идут через gated(). */
    @Test
    void runtimeResource_allTwelveEntriesGated() throws IOException {
        assertThat(Files.exists(RUNTIME_RESOURCE))
            .as("путь к RuntimeResource от cwd surefire (модуль engine): %s",
                RUNTIME_RESOURCE.toAbsolutePath())
            .isTrue();
        String src = Files.readString(RUNTIME_RESOURCE, StandardCharsets.UTF_8);

        Matcher methods = Pattern.compile(
            "public \\S+ (startProcessInstance|completeServiceTask|completeAdHocScopeJob|"
            + "failServiceTask|throwServiceTaskError|publishMessage|completeUserTask|"
            + "claimUserTask|unclaimUserTask|assignUserTask|resolveIncident|cancelProcessInstance)\\(")
            .matcher(src);
        int entryCount = 0;
        while (methods.find()) {
            entryCount++;
        }
        assertThat(entryCount)
            .as("число входов RuntimeResource (новый вход без гейта — пересмотреть тест)")
            .isEqualTo(12);

        Matcher gated = Pattern.compile("gated\\(\\(\\) ->").matcher(src);
        int gatedCount = 0;
        while (gated.find()) {
            gatedCount++;
        }
        assertThat(gatedCount)
            .as("каждый вход обёрнут в gated(() -> ...)")
            .isEqualTo(12);
        assertThat(src)
            .as("прямых вызовов *Operations в обход gated нет")
            .doesNotContain("return processInstanceRuntimeOperations.",
                "return serviceTaskRuntimeOperations.",
                "return userTaskRuntimeOperations.",
                "return messageRuntimeOperations.",
                "return incidentRuntimeOperations.");
    }

    /** WO-ENG-35: AMQP-слушатель ждёт admission ВНЕ транзакции. */
    @Test
    void amqpListener_noTransactionalAnnotation() throws Exception {
        assertThat(AnnotationUtils.findAnnotation(
            ServiceTaskCompleteListener.class, Transactional.class))
            .as("ServiceTaskCompleteListener БЕЗ @Transactional: транзакция — "
                + "в ServiceTaskCompletionProcessor, ожидание гейта — до неё")
            .isNull();
        assertThat(AnnotationUtils.findAnnotation(
            com.zorrodev.bpm.engine.listener.ServiceTaskCompletionProcessor.class
                .getMethod("process", com.zorrodev.bpm.exchange.ServiceTaskCompleted.class),
            Transactional.class))
            .as("транзакционная половина осталась @Transactional (граница та же)")
            .isNotNull();
    }

    /** WO-ENG-35: fire таймера идёт ПОСЛЕ acquire гейта (InOrder, обе лямбды). */
    @Test
    void timerFire_admissionBeforeFire_bothLambdas() throws Exception {
        org.mockito.Mockito.lenient().when(scriptService.admitOutsideTx())
            .thenReturn(AdmissionLease.noop());
        TimerBatchProcessor processor = new TimerBatchProcessor(
            dbService, timerJobExecutor, timerStartJobExecutor, Runnable::run, scriptService);
        java.lang.reflect.Field f = TimerBatchProcessor.class.getDeclaredField("batchSize");
        f.setAccessible(true);
        f.setInt(processor, 100);

        TimerJob job = new TimerJob();
        job.setId(UUID.randomUUID());
        job.setActivityId(UUID.randomUUID());
        TimerStartJob startJob = new TimerStartJob();
        startJob.setId(UUID.randomUUID());
        startJob.setProcessDefinitionId(UUID.randomUUID());
        startJob.setElementId("start");
        when(dbService.findDueTimerJobsLocked(any(), any(Integer.class)))
            .thenReturn(List.of(job));
        when(dbService.findDueTimerStartJobsLocked(any(), any(Integer.class)))
            .thenReturn(List.of(startJob));

        processor.processBatch();

        InOrder order = inOrder(scriptService, timerJobExecutor, timerStartJobExecutor);
        order.verify(scriptService).admitOutsideTx();
        order.verify(timerJobExecutor).fire(job);
        order.verify(scriptService).admitOutsideTx();
        order.verify(timerStartJobExecutor).fire(any(), any(), any(), any(), any());
    }

    /** WO-ENG-35: отказ гейта до fire не даёт выполнить fire (fail-fast до tx). */
    @Test
    void timerFire_gateRejection_skipsFire_recordsError() throws Exception {
        when(scriptService.admitOutsideTx()).thenThrow(
            new com.zorrodev.bpm.engine.service.ScriptOverloadException("pool overloaded (test)", 2));
        TimerBatchProcessor processor = new TimerBatchProcessor(
            dbService, timerJobExecutor, timerStartJobExecutor, Runnable::run, scriptService);
        java.lang.reflect.Field f = TimerBatchProcessor.class.getDeclaredField("batchSize");
        f.setAccessible(true);
        f.setInt(processor, 100);

        TimerJob job = new TimerJob();
        job.setId(UUID.randomUUID());
        job.setActivityId(UUID.randomUUID());
        when(dbService.findDueTimerJobsLocked(any(), any(Integer.class)))
            .thenReturn(List.of(job));
        when(dbService.findDueTimerStartJobsLocked(any(), any(Integer.class)))
            .thenReturn(List.of());

        // Не бросает: тот же catch, что у ошибок fire (запись + повтор позже).
        processor.processBatch();

        org.mockito.Mockito.verify(timerJobExecutor, org.mockito.Mockito.never()).fire(any());
        org.mockito.Mockito.verify(dbService).recordTimerJobError(any(), any());
    }
}
