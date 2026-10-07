package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.BpmnRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.scheduler.TimerScheduler;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WO-DEBT-5a — transactional atomicity (WO-REL-15 R-05). Real DB + real impl/repos/
 * parse/file/cache; only the mid-artifact failure itself (DBService) is mocked, and the
 * cache seam (BpmnService) is mocked for a consistency check. (The never() below is
 * consistency-only: the failure happens before the cache line, so it would pass under
 * both designs. The positive cache proof lives in the characterization class + the
 * order-pinning class.)
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
// NEW5-05 (WO-QW-10): @MockitoBean DBService — общий мок для теста И для потоков
// планировщиков того же контекста (@EnableScheduling активен и в тестах). Поллер с
// интервалом 2-5с успевал дёрнуть мок из своего потока, пока тест ставил на нём
// стабы → UnfinishedStubbingException, недетерминированно (в CI это и ловилось).
// Паркуем ВСЕ поллеры, которые ходят в DBService, на час — тем же приёмом, что
// TimerBatchIsolationPgIT/FeedPosition*PgIT паркуют таймер-поллер. Поведение
// самого теста не меняется: он проверяет атомарность деплоя, а не поллеры.
@TestPropertySource(properties = {
    "zorrobpm.feed-position.poll-interval-ms=3600000",
    "zorrobpm.engine.outbox-poll-interval-ms=3600000",
    "zorrobpm.engine.timer-poll-interval-ms=3600000",
    "zorrobpm.servicetask.watchdog-interval-ms=3600000"
})
class ProcessDefinitionServiceAtomicityIntegrationTests {

    @Autowired private ProcessDefinitionService service;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private BpmnRepository bpmnRepository;
    // WO-QW-15: нейтрализуем TimerScheduler целиком (расписание зовёт no-op мока).
    // Парковка интервала (=3600000 выше и в application.properties) НЕ подавляет
    // первый тик @Scheduled (initialDelay по умолчанию 0 — стреляет на старте),
    // а тик идёт в DBService из чужого потока и срывает doThrow-стаббинг
    // тест-потока (CI 558188: "Expecting code to raise a throwable" на :78 +
    // "Timer batch failed … dueJobs is null" — мок вернул null). Outbox/Feed
    // парковать не надо: они под @Profile("!test") и в этом контексте их нет;
    // StuckServiceTaskWatchdog мока не трогает (ходит в ActivityRepository
    // напрямую) — его не трогаем (минимальный дифф).
    @MockitoBean private TimerScheduler timerScheduler;
    @MockitoBean private DBService dbService;
    @MockitoBean private BpmnService bpmnService;

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String messageStartBpmn(String key, String messageName) throws Exception {
        return Files.readString(Path.of("src/test/files/test-rel15-msg-deploy.bpmn"))
            .replace("rel15-msg-deploy", key)
            .replace("rel15-msg-received", messageName);
    }

    @Test
    void schedulerTick_mustNotTouchSharedDbMock() throws Exception {
        // WO-QW-15: TimerScheduler живёт в том же контексте и ходит в DBService
        // (TimerBatchProcessor.processBatch → findDueTimerJobsLocked) из потоков
        // шедулера/диспетчера. Парковка интервала на час НЕ подавляет первый тик
        // (@Scheduled без initialDelay стреляет сразу на старте), а Outbox/Feed
        // здесь вообще выключены профилем — тикал именно таймер (в CI-логe
        // "Timer batch failed … dueJobs is null": мок вернул null, NPE).
        // Чужой вызов мока во время doThrow-стаббинга срывает стабинг тест-потока
        // → "Expecting code to raise a throwable" на :78. Тик обязан быть
        // нейтрализован (см. @MockitoBean ниже), этот тест это пинает.
        clearInvocations(dbService);
        timerScheduler.fireDueTimers();
        boolean touched = false;
        for (int i = 0; i < 40 && !touched; i++) {
            Thread.sleep(50);
            touched = !mockingDetails(dbService).getInvocations().isEmpty();
        }
        assertThat(touched)
            .as("scheduler tick must not touch the shared DBService mock (flakes stubbing)")
            .isFalse();
    }

    @Test
    void addProcessDefinition_midArtifactFailure_rollsBackEverything() throws Exception {
        String key = uniq("pdx");
        String msg = "msg-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = messageStartBpmn(key, msg);
        // Fail in the MIDDLE of the artifact set (after version save + file save)
        doThrow(new RuntimeException("forced mid-artifacts failure"))
            .when(dbService).createMessageStartSubscription(anyString(), any(), anyString(), anyString());

        assertThatThrownBy(() -> service.addProcessDefinition(bpmn))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("forced mid-artifacts failure");

        // WO-REL-15 R-05: nothing partial remains — no version row, no file row...
        long rows = processDefinitionRepository.findAll().stream()
            .filter(e -> key.equals(e.getKey())).count();
        assertThat(rows).as("no half-deployed version row").isZero();
        // WO-QW-4 (NEW-14): собственный признак вместо глобального count():
        // глобальный count() в общем контексте флейкал в CI (pipeline 171975),
        // а чужие строки других тестов здесь ни при чём. Наш BPMN несёт key
        // в XML — считаем только свои строки.
        long ownFiles = bpmnRepository.findAll().stream()
            .filter(e -> e.getBpmn() != null && e.getBpmn().contains(key)).count();
        assertThat(ownFiles).as("no orphaned BPMN file row for this deploy").isZero();
        // ...and the model cache was never filled (afterCommit never fired on rollback)
        verify(bpmnService, never()).addProcessDefinition(any(), any());
    }
}
