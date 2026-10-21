package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ElementListenerPhaseRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.trust.CompletionTrustMonitor;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-AUDIT-20: монитор доверия канала завершений — НАБЛЮДАТЕЛЬ, и он не имеет
 * права ронять приём completion.
 *
 * <p><b>Что фиксирует этот тест.</b> {@code CompletionTrustMonitor.inspect} стоит на
 * живом пути приёма AMQP-completion'ов ({@code ServiceTaskCompletionProcessor.process})
 * В ТОЙ ЖЕ транзакции, что и применение результата. Монитор — только счётчик и
 * WARN-лог, но его исключение (сбой чтения, NPE в будущей правке) уронило бы ПРИЁМ
 * результата: транзакция откатилась бы, движок посчитал бы задачу невыполненной, а
 * контейнерный retry переигрывал бы то же сообщение по кругу.
 *
 * <p>Подмена сделана на РЕПОЗИТОРИИ монитора (не на самом мониторе и не на
 * {@code RuntimeService}): {@code ActivityRepository.findById} бросает — так падает
 * ровно тот «сбой чтения», который называет WO. Сам монитор и сам процессор — НАСТОЯЩИЕ
 * (G-N: тест гоняет прод-код, а не его копию).
 *
 * <p>Ожидания по трём независимым осям:
 * <ul>
 *   <li><b>исход не меняется</b> — результат всё равно применён
 *       ({@code runtimeService.completeServiceTask/failServiceTask} вызваны с теми же
 *       аргументами), т.е. try/catch не превратился в «тихо проглотить completion»;</li>
 *   <li><b>приём не падает</b> — исключение монитора не выходит наружу;</li>
 *   <li><b>падение видно</b> — счётчик {@code zbpm.completion.monitor.errors} растёт
 *       (наблюдатель не может молчать).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ServiceTaskCompletionProcessorTest {

    /** Счётчик падений наблюдателя (WO-AUDIT-20). */
    private static final String MONITOR_ERRORS_METER = "zbpm.completion.monitor.errors";

    @Mock private RuntimeService runtimeService;

    private MeterRegistry registry;
    private BpmMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BpmMetrics(registry);
    }

    /** Монитор, чьё чтение падает (сбой репозитория) — ровно тот случай, что называет WO. */
    private CompletionTrustMonitor brokenMonitor() {
        ActivityRepository activities = mock(ActivityRepository.class);
        when(activities.findById(any(UUID.class)))
            .thenThrow(new IllegalStateException("completion trust monitor: db read failed"));
        return new CompletionTrustMonitor(activities, mock(ServiceTaskRepository.class),
            mock(ElementListenerPhaseRepository.class), metrics);
    }

    /** Монитор, который работает штатно (id, которого движок не знает). */
    private CompletionTrustMonitor healthyMonitor() {
        ActivityRepository activities = mock(ActivityRepository.class);
        when(activities.findById(any(UUID.class))).thenReturn(Optional.empty());
        return new CompletionTrustMonitor(activities, mock(ServiceTaskRepository.class),
            mock(ElementListenerPhaseRepository.class), metrics);
    }

    private double monitorErrors() {
        Counter counter = registry.find(MONITOR_ERRORS_METER).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static ServiceTaskCompleted completion(UUID serviceTaskId, String status) {
        ServiceTaskCompleted completion = new ServiceTaskCompleted();
        completion.setServiceTaskId(serviceTaskId);
        completion.setStatus(status);
        return completion;
    }

    /**
     * WO-AUDIT-20 (1): падение монитора НЕ ломает приём SUCCESS-completion'а.
     * Исход (результат применён) — тот же, что без падения монитора.
     */
    @Test
    void monitorFailure_doesNotBreakCompletionIntake_andCountsTheError() {
        UUID serviceTaskId = UUID.randomUUID();
        ServiceTaskCompletionProcessor processor =
            new ServiceTaskCompletionProcessor(runtimeService, brokenMonitor(), metrics);

        assertThatCode(() -> processor.process(completion(serviceTaskId, "SUCCESS")))
            .as("наблюдатель не имеет права ронять приём completion")
            .doesNotThrowAnyException();

        verify(runtimeService).completeServiceTask(eq(serviceTaskId), anyList(), isNull(), isNull());
        assertThat(monitorErrors())
            .as("падение наблюдателя обязано быть ИЗМЕРИМО, а не только залогировано")
            .isEqualTo(1.0);
    }

    /**
     * WO-AUDIT-20 (1-бис): тот же контракт на FAILED-ветке приёма (ретраи/инцидент) —
     * ветка другая, значит и фикс должен держаться на обеих.
     */
    @Test
    void monitorFailure_doesNotBreakFailedCompletionIntake() {
        UUID serviceTaskId = UUID.randomUUID();
        ServiceTaskCompleted completion = completion(serviceTaskId, "FAILED");
        completion.setErrorMessage("boom");
        ServiceTaskCompletionProcessor processor =
            new ServiceTaskCompletionProcessor(runtimeService, brokenMonitor(), metrics);

        assertThatCode(() -> processor.process(completion))
            .as("наблюдатель не имеет права ронять приём FAILED-completion'а")
            .doesNotThrowAnyException();

        verify(runtimeService).failServiceTask(eq(serviceTaskId), eq("boom"), isNull(), isNull(), isNull(), isNull());
        assertThat(monitorErrors()).isEqualTo(1.0);
    }

    /**
     * WO-AUDIT-20 (1-бис-бис): счётчик ошибок НЕ растёт на штатном приёме —
     * иначе «наблюдатель сломан» перестало бы отличаться от «наблюдатель в работе».
     */
    @Test
    void healthyMonitor_doesNotCountAnError() {
        ServiceTaskCompletionProcessor processor =
            new ServiceTaskCompletionProcessor(runtimeService, healthyMonitor(), metrics);

        assertThatCode(() -> processor.process(completion(UUID.randomUUID(), "SUCCESS")))
            .doesNotThrowAnyException();

        assertThat(monitorErrors())
            .as("штатный приём не должен считаться ошибкой наблюдателя")
            .isZero();
    }
}
