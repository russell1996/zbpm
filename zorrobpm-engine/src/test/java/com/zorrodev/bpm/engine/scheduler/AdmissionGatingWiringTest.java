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
 * <p>WO-ENG-35 раунд 2 (Б-2): гейтятся ТОЛЬКО FEEL-достижимые входы (8), а не
 * все 12 — script-free операции (claim/unclaim/assign/cancel) идут напрямую,
 * иначе sustained-перегрузка script-пула роняла бы их в 503 (поймано красной
 * командой живьём: cancel → 503 на забитом пуле).
 *
 * <p>Три ловушки против «тест, который не может краснеть» (прецедент
 * {@code DeactivationWakeupCoverageTest}):
 * <ul>
 *   <li>литеральные множества входов (8 гейтованных + 4 прямых) — новый вход
 *       или смена гейта валит тест, пока список не пересмотрен;</li>
 *   <li>поведенческие проверки (InOrder, отсутствие аннотации, прямой
 *       unit-вызов listener с verify), а не только подсчёт строк;</li>
 *   <li>мутации: убрать {@code gatedFeel(...)} в одном методе → тест 1 КРАСНЫЙ;
 *       снова завернуть script-free вход в гейт → тест 1 КРАСНЫЙ (двусторонний:
 *       и снятие, и лишний гейт ловятся); вернуть {@code @Transactional} на
 *       listener → тест 2 КРАСНЫЙ; убрать гейт из одной fire-лямбды → тест 3
 *       КРАСНЫЙ; убрать вызов admit из listener → тест 5 КРАСНЫЙ; убрать
 *       поле/метод → компиляция падает (тест не нужен).</li>
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

    /**
     * WO-ENG-35 раунд 2 (Б-2): ровно 8 FEEL-достижимых входов идут через
     * {@code gatedFeel()}, а 4 script-free — напрямую. Двусторонний: краснеет
     * и при снятии гейта с FEEL-входа, и при заворачивании script-free входа
     * обратно в гейт (мутация over-gating).
     */
    @Test
    void runtimeResource_onlyFeelReachableEntriesGated() throws IOException {
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
            .as("число входов RuntimeResource (новый вход — пересмотреть тест)")
            .isEqualTo(12);

        // 8 FEEL-достижимых — через гейт (имена methods + gatedFeel рядом).
        for (String gated : List.of("startProcessInstance", "completeServiceTask",
                "completeAdHocScopeJob", "failServiceTask", "throwServiceTaskError",
                "publishMessage", "completeUserTask", "resolveIncident")) {
            Matcher m = Pattern.compile(
                "public \\S+ " + gated + "\\([^)]*\\) \\{[^}]*?gatedFeel\\(\\(\\) ->",
                Pattern.DOTALL).matcher(src);
            assertThat(m.find())
                .as("FEEL-достижимый вход %s обёрнут в gatedFeel(() -> ...)", gated)
                .isTrue();
        }
        // 4 script-free — напрямую, без гейта.
        for (String direct : List.of("claimUserTask", "unclaimUserTask",
                "assignUserTask", "cancelProcessInstance")) {
            Matcher m = Pattern.compile(
                "public \\S+ " + direct + "\\(").matcher(src);
            assertThat(m.find()).as("вход %s существует", direct).isTrue();
            int bodyStart = src.indexOf("{", m.end());
            int bodyEnd = src.indexOf("}", bodyStart);
            String body = src.substring(bodyStart, bodyEnd);
            assertThat(body)
                .as("script-free вход %s идёт напрямую, без gatedFeel (over-gating)", direct)
                .doesNotContain("gatedFeel");
        }
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

    /**
     * WO-ENG-35 раунд 2 (оформительская красной команды): поведенческий ассерт
     * вызова гейта в listener — не опираемся на strict-stubs соседнего теста.
     * Прямой unit-вызов {@code on} с мок-гейтом (no-op лиза) и мок-процессором:
     * гейт вызывается РОВНО один раз ДО тела (InOrder), тело — один раз.
     */
    @Test
    void amqpListener_admissionBeforeProcess() {
        com.zorrodev.bpm.engine.tracing.TracingSupport tracing =
            org.mockito.Mockito.mock(com.zorrodev.bpm.engine.tracing.TracingSupport.class);
        com.zorrodev.bpm.engine.listener.ServiceTaskCompletionProcessor processor =
            org.mockito.Mockito.mock(
                com.zorrodev.bpm.engine.listener.ServiceTaskCompletionProcessor.class);
        org.mockito.Mockito.lenient().when(scriptService.admitOutsideTx())
            .thenReturn(AdmissionLease.noop());
        ServiceTaskCompleteListener listener =
            new ServiceTaskCompleteListener(tracing, scriptService, processor);
        com.zorrodev.bpm.exchange.ServiceTaskCompleted event =
            new com.zorrodev.bpm.exchange.ServiceTaskCompleted();
        event.setServiceTaskId(UUID.randomUUID());

        listener.on(event);

        InOrder order = inOrder(scriptService, processor);
        order.verify(scriptService).admitOutsideTx();
        order.verify(processor).process(event);
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
