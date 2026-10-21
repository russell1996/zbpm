package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-33 (п.2 «Что сделать»): перепроверка припаркованных join'ов НЕ должна создавать
 * activity в завершённом инстансе — терминальный инстанс не получает новых элементов.
 *
 * <p><b>Что это за состояние.</b> «Инстанс завершён, а в нём висит припаркованный join» —
 * это ровно то состояние, из-за которого старая правка счётчика ветвей давала преждевременное
 * завершение: счётчик обнулялся (одна ветвь форка потрачена дважды: гашением границей и её
 * же end event'ом), инстанс помечался завершённым, а движок затем резюмил parked join и
 * создавал {@code taskAfter} В УЖЕ ЗАВЕРШЁННОМ инстансе. Основной фикс (счётчик не тратится
 * дважды) убирает причину; этот тест — страховка на САМ guard в
 * {@code InclusiveGatewayHandler.resumeParkedInclusiveJoins}: терминальный инстанс ⇒ резюм
 * ничего не создаёт.
 *
 * <p><b>Почему состояние строится низкоуровневым вызовом.</b> После фикса счётчика это
 * состояние уже не возникает само по себе (пока ветвь форка жива, счётчик не обнуляется) —
 * иначе это был бы тест на недостижимое. Поэтому терминальное состояние инстанса задаётся
 * прямым вызовом {@code DBService.completeProcessInstance} (тот же приём, что в
 * {@code MetricsWiringIntegrationTests}), а ТРИГГЕР резюма — настоящий боевой путь:
 * прерывающая граница на живом хосте ({@code EventTrigger.fireBoundary} → отложенный/обычный
 * хвост → {@code resumeParkedInclusiveJoins}).
 *
 * <p><b>Мутация.</b> Убрать guard в {@code resumeParkedInclusiveJoins} ⇒ резюм срабатывает ⇒
 * join COMPLETED=1 и taskAfter CREATED=1 в завершённом инстансе ⇒ тест красный (и заодно
 * краснеет инвариант утечек: живая activity в завершённом инстансе).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class TerminalInstanceNoNewElementsIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private ActivityService activityService;
    @Autowired private DBService dbService;

    @Transactional
    @Test
    void parkedJoinInTerminalInstance_isNotResumed() {
        UUID defId = processDefinitionService.addProcessDefinition(diagram()).getId();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(defId);
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        // ── ветвь taskA приходит в join, вторая ветвь форка (taskSvc → host) жива ⇒ join паркуется
        complete(pi, "taskA");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("премисса: join припаркован (вторая ветвь форка ещё жива)")
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as("премисса: после паркованного join ничего не создано")
            .isEqualTo(0L);

        // ── инстанс становится терминальным (низкоуровневый вызов — см. javadoc класса)
        complete(pi, "taskSvc");
        assertThat(countOf(pi, "host", ActivityStatus.CREATED))
            .as("премисса: хост второй ветви форка жив (на нём и сработает граница)")
            .isEqualTo(1L);
        dbService.completeProcessInstance(pi);
        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt())
            .as("премисса: инстанс терминален (completedAt задан)")
            .isNotNull();

        // ── боевой триггер резюма: прерывающая граница гасит хост (последний возможный
        //    доставщик второй ветви форка) → движок перепроверяет припаркованные join'и
        activityService.fireBoundaryTimer(activeId(pi, "host"), "killBnd");
        assertThat(countOf(pi, "host", ActivityStatus.CANCELLED))
            .as("премисса: прерывающая граница погасила хост")
            .isEqualTo(1L);

        // ── терминальный инстанс НЕ получает новых элементов ─────────────────────────────
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("инстанс терминален — припаркованный join не должен стрелять (WO-OPS-33 п.2)")
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as("инстанс терминален — taskAfter не должен создаваться (WO-OPS-33 п.2)")
            .isEqualTo(0L);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("инстанс остался терминальным")
            .isNotNull();
    }

    // ── водительство ──────────────────────────────────────────────────────────────────────

    private List<ActivityEntity> activities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
    }

    private long countOf(UUID pi, String elementId, ActivityStatus status) {
        return activities(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == status)
            .count();
    }

    private UUID activeId(UUID pi, String elementId) {
        return activities(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CREATED " + elementId + " in " + pi))
            .getId();
    }

    private void complete(UUID pi, String elementId) {
        runtimeService.completeUserTask(activeId(pi, elementId), List.of());
    }

    /**
     * Диаграмма — та же форма, что у границы на форке (WO-OPS-33): форк на две ветви,
     * ветвь taskA приходит в inclusive-join, вторая ветвь (taskSvc → host) несёт ДВЕ
     * прерывающие границы: killBnd (её хвост ведёт в end event корневого scope — тот самый
     * дефект WO-OPS-33) и bnd (её поток ведёт в join — вторая входящая потока join'а,
     * без неё join не ждёт второго доставщика и не паркуется).
     */
    private String diagram() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
            xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="Definitions_ops33term" \
            targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="ops33terminal" name="ops33terminal" isExecutable="true">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="pfork" />
                <bpmn:parallelGateway id="pfork">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>fa</bpmn:outgoing>
                  <bpmn:outgoing>fsvc</bpmn:outgoing>
                </bpmn:parallelGateway>
                <bpmn:sequenceFlow id="fa" sourceRef="pfork" targetRef="taskA" />
                <bpmn:userTask id="taskA">
                  <bpmn:incoming>fa</bpmn:incoming><bpmn:outgoing>gC</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="gC" sourceRef="taskA" targetRef="join" />
                <bpmn:sequenceFlow id="fsvc" sourceRef="pfork" targetRef="taskSvc" />
                <bpmn:userTask id="taskSvc">
                  <bpmn:incoming>fsvc</bpmn:incoming><bpmn:outgoing>fH</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="fH" sourceRef="taskSvc" targetRef="host" />
                <bpmn:userTask id="host">
                  <bpmn:incoming>fH</bpmn:incoming><bpmn:outgoing>fHostEnd</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="fHostEnd" sourceRef="host" targetRef="endHost" />
                <bpmn:endEvent id="endHost"><bpmn:incoming>fHostEnd</bpmn:incoming></bpmn:endEvent>
                <bpmn:boundaryEvent id="killBnd" attachedToRef="host" cancelActivity="true">
                  <bpmn:outgoing>fKill</bpmn:outgoing>
                  <bpmn:timerEventDefinition id="killDef">
                    <bpmn:timeDuration>PT10H</bpmn:timeDuration>
                  </bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:sequenceFlow id="fKill" sourceRef="killBnd" targetRef="escapeEnd" />
                <bpmn:endEvent id="escapeEnd"><bpmn:incoming>fKill</bpmn:incoming></bpmn:endEvent>
                <bpmn:boundaryEvent id="bnd" attachedToRef="host" cancelActivity="true">
                  <bpmn:outgoing>fB</bpmn:outgoing>
                  <bpmn:timerEventDefinition id="bDef">
                    <bpmn:timeDuration>PT10H</bpmn:timeDuration>
                  </bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:sequenceFlow id="fB" sourceRef="bnd" targetRef="join" />
                <bpmn:inclusiveGateway id="join">
                  <bpmn:incoming>gC</bpmn:incoming>
                  <bpmn:incoming>fB</bpmn:incoming>
                  <bpmn:outgoing>fAfter</bpmn:outgoing>
                </bpmn:inclusiveGateway>
                <bpmn:sequenceFlow id="fAfter" sourceRef="join" targetRef="taskAfter" />
                <bpmn:userTask id="taskAfter">
                  <bpmn:incoming>fAfter</bpmn:incoming><bpmn:outgoing>fEnd</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="fEnd" sourceRef="taskAfter" targetRef="endEvent" />
                <bpmn:endEvent id="endEvent"><bpmn:incoming>fEnd</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;
    }
}
