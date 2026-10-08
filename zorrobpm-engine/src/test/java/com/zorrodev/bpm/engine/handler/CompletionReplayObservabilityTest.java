package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-8 (A-NEW4-15): повторный complete — наблюдаемый, без breaking.
 *
 * <p>Контракт: первый вызов завершает задачу ({@code alreadyCompleted} отсутствует),
 * повторный — 2xx + {@code alreadyCompleted=true} + счётчик
 * {@code zbpm.completion.replay{kind}} +1. Статус-коды НЕ меняются.
 *
 * <p>POF-честность: эти тесты написаны ДО поведения и прогнаны на дереве, где
 * есть только поле DTO (шаг A). На нём они КРАСНЫЕ: метрики нет
 * (MeterNotFoundException) и флаг всегда null. Мутация «вернуть тихий return»
 * (убрать replay-проводку из CompletionService) роняет их обратно.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class CompletionReplayObservabilityTest {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private UUID startProcess(String bpmnFile) {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private UUID parkedActivityId(UUID pi, String elementId) {
        return tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi)
                && a.getBpmnElementId().equals(elementId)
                && (a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS))
            .map(a -> a.getId()).findFirst().orElseThrow());
    }

    private long countByElement(UUID pi, String elementId, ActivityStatus status) {
        return tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi)
                && a.getBpmnElementId().equals(elementId)
                && a.getStatus() == status)
            .count());
    }

    private double replayCount(String kind) {
        return meterRegistry.get("zbpm.completion.replay").tag("kind", kind).counter().count();
    }

    @Test
    void duplicateUserTaskComplete_returnsReplayFlag_andCountsMetric() {
        UUID pi = startProcess("test-usertask-query.bpmn");
        UUID userTaskId = parkedActivityId(pi, "approve");

        IdDTO first = tx.execute(s -> runtimeService.completeUserTask(userTaskId, List.of()));
        assertThat(first.getAlreadyCompleted())
            .as("первый complete — обычное завершение, флага нет")
            .isNull();
        double before = replayCount("user_task");

        IdDTO second = tx.execute(s -> runtimeService.completeUserTask(userTaskId, List.of()));
        assertThat(second.getAlreadyCompleted())
            .as("повторный complete — 2xx + alreadyCompleted=true (тихий успех запрещён)")
            .isEqualTo(Boolean.TRUE);
        assertThat(replayCount("user_task"))
            .as("повтор виден в метрике completion_replay_total{kind=user_task}")
            .isEqualTo(before + 1);

        assertThat(countByElement(pi, "approve", ActivityStatus.COMPLETED))
            .as("двойного продвижения нет — одна COMPLETED-строка")
            .isEqualTo(1);
    }

    @Test
    void duplicateServiceTaskComplete_returnsReplayFlag_andCountsMetric() {
        UUID pi = startProcess("test-service-task-fail.bpmn");
        UUID svcId = parkedActivityId(pi, "svc");

        IdDTO first = tx.execute(s -> runtimeService.completeServiceTask(svcId, List.of()));
        assertThat(first.getAlreadyCompleted())
            .as("первый complete — обычное завершение, флага нет")
            .isNull();
        double before = replayCount("service_task");

        IdDTO second = tx.execute(s -> runtimeService.completeServiceTask(svcId, List.of()));
        assertThat(second.getAlreadyCompleted())
            .as("повторный complete — 2xx + alreadyCompleted=true")
            .isEqualTo(Boolean.TRUE);
        assertThat(replayCount("service_task"))
            .as("повтор виден в метрике completion_replay_total{kind=service_task}")
            .isEqualTo(before + 1);

        assertThat(countByElement(pi, "svc", ActivityStatus.COMPLETED))
            .as("двойного продвижения нет — одна COMPLETED-строка")
            .isEqualTo(1);
    }

    @Test
    void oldClientContract_idOnlyJsonDeserializes_withoutReplayFlag() throws Exception {
        // Старый клиент: шлёт/читает DTO без поля — десериализация не ломается,
        // флаг отсутствует (null), а не false.
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        UUID id = UUID.randomUUID();
        com.zorrodev.bpm.contract.dto.IdDTO dto =
            mapper.readValue("{\"id\":\"" + id + "\"}", com.zorrodev.bpm.contract.dto.IdDTO.class);
        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getAlreadyCompleted())
            .as("старый JSON без поля → alreadyCompleted null (не false)")
            .isNull();

        // Сериализация ответа первого вызова: поля alreadyCompleted в теле НЕТ
        // (NON_NULL) — старый строгий клиент видит байтово тот же ответ.
        com.zorrodev.bpm.contract.dto.IdDTO first = new com.zorrodev.bpm.contract.dto.IdDTO(id);
        assertThat(mapper.writeValueAsString(first))
            .as("первый complete: alreadyCompleted отсутствует в JSON")
            .doesNotContain("alreadyCompleted");

        // Ответ повтора: поле ЕСТЬ и true.
        com.zorrodev.bpm.contract.dto.IdDTO replay =
            new com.zorrodev.bpm.contract.dto.IdDTO(id, Boolean.TRUE);
        assertThat(mapper.readTree(mapper.writeValueAsString(replay)).get("alreadyCompleted").asBoolean())
            .as("повтор: alreadyCompleted=true в JSON")
            .isTrue();
    }
}
