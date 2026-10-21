package com.zorrodev.bpm.engine.trust;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.listener.ServiceTaskCompletionProcessor;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * WO-AUDIT-20: ПРОВОДКА hardening'а — падение наблюдателя доверия канала завершений
 * не роняет приём completion НА ЖИВОМ ПУТИ (полный Spring-контекст движка, реальная БД).
 *
 * <p>Юнит-тест {@code ServiceTaskCompletionProcessorTest} доказывает контракт на
 * сконструированных объектах; этот — на настоящем бине {@code ServiceTaskCompletionProcessor},
 * который Spring поднимает в контексте движка, с настоящим {@code RuntimeService} и
 * настоящей строкой {@code service_tasks}. Подменён ровно ОДИН бин — сам наблюдатель
 * ({@code CompletionTrustMonitor}), потому что он больше нигде в движке не используется
 * (grep: единственный вызывающий — {@code ServiceTaskCompletionProcessor}); всё остальное
 * — боевой wiring.
 *
 * <p>Проверяются три оси:
 * <ul>
 *   <li>приём completion НЕ падает (исключение наблюдателя не выходит наружу);</li>
 *   <li>исход НЕ изменился: задача завершилась ровно как при живом мониторе
 *       (строка activity — COMPLETED, процесс дошёл до конца);</li>
 *   <li>падение наблюдателя ИЗМЕРИМО: {@code zbpm.completion.monitor.errors} вырос.</li>
 * </ul>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class CompletionTrustMonitorFailureIsolationIntegrationTests {

    /** Счётчик падений наблюдателя (WO-AUDIT-20). */
    private static final String MONITOR_ERRORS_METER = "zbpm.completion.monitor.errors";

    /** Наблюдатель сломан намеренно — ровно тот случай, который обязан быть пережитым. */
    @MockitoBean
    private CompletionTrustMonitor monitor;

    @Autowired private ServiceTaskCompletionProcessor completionProcessor;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private MeterRegistry meterRegistry;

    private double monitorErrors() {
        Counter counter = meterRegistry.find(MONITOR_ERRORS_METER).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private ActivityEntity activityOf(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals(elementId))
            .findFirst().orElseThrow();
    }

    /**
     * WO-AUDIT-20 (1): падение монитора не роняет приём и не меняет его исход.
     */
    @Test
    void monitorFailure_onTheLiveIntakePath_doesNotBreakTheCompletion() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test3.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of());
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        ActivityEntity serviceTask = activityOf(pi, "serviceTask1");
        assertThat(serviceTask.getType())
            .as("фикстура должна встать на service task — иначе тест проверяет не то")
            .isEqualTo(com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.SERVICE_TASK);

        doThrow(new IllegalStateException("completion trust monitor: db read failed"))
            .when(monitor).inspect(any());
        double errorsBefore = monitorErrors();

        ServiceTaskCompleted completion = new ServiceTaskCompleted();
        completion.setServiceTaskId(serviceTask.getId());
        completion.setStatus("SUCCESS");

        assertThatCode(() -> completionProcessor.process(completion))
            .as("падение наблюдателя не имеет права ронять приём completion")
            .doesNotThrowAnyException();

        assertThat(activityRepository.findById(serviceTask.getId()).orElseThrow().getStatus())
            .as("исход приёма не изменился: результат применён, задача завершена")
            .isEqualTo(ActivityStatus.COMPLETED);
        assertThat(monitorErrors() - errorsBefore)
            .as("падение наблюдателя обязано быть ИЗМЕРИМО")
            .isEqualTo(1.0);
    }
}
