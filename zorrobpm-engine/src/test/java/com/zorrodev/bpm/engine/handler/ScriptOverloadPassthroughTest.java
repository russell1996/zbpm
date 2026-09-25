package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptOverloadException;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-ENG-24 (HOLD находка 1): временная перегрузка FEEL-пула пробрасывается
 * через element-уровень до 503-хендлера, а не глотается в инцидент.
 *
 * <p>Главный путь ({@code ActivityServiceImpl.execute}) пробрасывал
 * {@code EngineException} всегда — там перегрузка доходила до 503 без
 * изменений (доказано живым {@code ScriptOverloadMappingIT}). Но три
 * resolve-or-incident catch'а на том же синхронном HTTP-пути ловили
 * {@code EngineException} в инцидент — а {@code ScriptOverloadException} его
 * наследник, поэтому перегрузка молча становилась инцидентом вместо 503.
 * Три новых guard'а (P-46: на каждый — свой RED мутацией «убрать rethrow»):
 * MI-cardinality, MI-priority, user-task priority.
 *
 * <p>Timer-пути ({@code TimerCatchHandler}, {@code BoundaryScheduler}) —
 * осознанно НЕ тронуты: soft-fail там — Zeebe-parity дизайн WO-DIFF-7 (live
 * Raxon probe), плюс {@code BoundaryScheduler} вызывается из фоновых
 * расписаний, где HTTP-статуса нет вообще. Перегрузка timer-FEEL остаётся
 * видимым инцидентом (resolveIncident чинит, потери данных нет) —
 * задокументированный остаток, не молчаливый пропуск.
 */
class ScriptOverloadPassthroughTest {

    private static final ScriptOverloadException OVERLOAD =
        new ScriptOverloadException("Script execution rejected: pool overloaded (test), retry later", 5);

    // --- MI cardinality: перегрузка inputCollection/cardinality-eval идёт наружу ---

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void miCardinality_overload_propagates_noIncident() {
        ScriptService scriptService = mock(ScriptService.class);
        when(scriptService.evaluateExpression(any(), any())).thenThrow(OVERLOAD);
        // Verifier раунд 2: проверяемый мок инжектится в executor (раньше
        // verify шёл по отдельному моку — строка была vacuous).
        DBService dbService = mock(DBService.class);
        MultiInstanceExecutor executor = new MultiInstanceExecutor(
            dbService, scriptService, mock(com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService.class),
            new tools.jackson.databind.ObjectMapper(), mock(com.zorrodev.bpm.engine.service.BpmnService.class),
            mock(ElementSupport.class), mock(BoundaryScheduler.class), mock(FlowNavigator.class));

        BpmnElementModel element = miElement("=input");

        assertThatThrownBy(() -> executor.enter(UUID.randomUUID(), UUID.randomUUID(), element, mock(TokenExecutor.class)))
            .as("pool overload propagates past the cardinality incident-catch")
            .isSameAs(OVERLOAD);
        verify(dbService, never()).createIncident(any(), any());
    }

    // --- MI priority: перегрузка priority-resolve идёт наружу ---

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void miPriority_overload_propagates_noIncident() {
        ElementSupport elementSupport = mock(ElementSupport.class);
        when(elementSupport.resolveUserTaskPriorityOrThrow(any(), any())).thenThrow(OVERLOAD);
        DBService dbService = mock(DBService.class);
        MultiInstanceExecutor executor = new MultiInstanceExecutor(
            dbService, mock(ScriptService.class), mock(com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService.class),
            new tools.jackson.databind.ObjectMapper(), mock(com.zorrodev.bpm.engine.service.BpmnService.class),
            elementSupport, mock(BoundaryScheduler.class), mock(FlowNavigator.class));

        // userTask-ветка spawn path: priority резолвится на каждый инстанс.
        // Прямой вызов приватного spawn тяжёл — идём через публичный enter
        // с inputCollection=null + cardinality=3 (resolveCardinality идёт
        // через мок-ScriptService → число; инцидент-ветка priority — через
        // мок-ElementSupport → overload).
        ScriptService scriptService = mock(ScriptService.class);
        when(scriptService.evaluateExpression(any(), any())).thenReturn(3L);
        MultiInstanceExecutor executor2 = new MultiInstanceExecutor(
            dbService, scriptService, mock(com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService.class),
            new tools.jackson.databind.ObjectMapper(), mock(com.zorrodev.bpm.engine.service.BpmnService.class),
            elementSupport, mock(BoundaryScheduler.class), mock(FlowNavigator.class));

        BpmnElementModel element = miUserTaskElement("=3", true);
        assertThatThrownBy(() -> executor2.enter(UUID.randomUUID(), UUID.randomUUID(), element, mock(TokenExecutor.class)))
            .as("pool overload propagates past the MI priority incident-catch")
            .isSameAs(OVERLOAD);
        verify(dbService, never()).createIncident(any(), any());
    }

    // --- User-task priority: перегрузка идёт наружу, инцидента нет ---

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void userTaskPriority_overload_propagates_noIncident() {
        ElementSupport elementSupport = mock(ElementSupport.class);
        when(elementSupport.resolveUserTaskPriorityOrThrow(any(), any())).thenThrow(OVERLOAD);
        DBService dbService = mock(DBService.class);
        UserTaskHandler handler = new UserTaskHandler(dbService, elementSupport,
            mock(MultiInstanceExecutor.class), mock(BoundaryScheduler.class),
            mock(com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService.class));

        BpmnElementModel element = userTaskElement("=priority");
        UUID activityId = UUID.randomUUID();
        assertThatThrownBy(() -> handler.createTaskRow(UUID.randomUUID(), activityId, element))
            .as("pool overload propagates past the priority incident-catch")
            .isSameAs(OVERLOAD);
        // P-67: инцидента НЕТ (конкретное различение «проброс vs инцидент»):
        // ни errorActivity, ни createIncident не вызывались.
        verify(dbService, never()).errorActivity(any());
        verify(dbService, never()).createIncident(any(), any());
    }

    // --- Регрессия: битый priority по-прежнему идёт в инцидент, не наружу ---

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void userTaskPriority_brokenExpression_stillIncident() {
        ElementSupport elementSupport = mock(ElementSupport.class);
        com.zorrodev.bpm.contract.exception.EngineException broken =
            new com.zorrodev.bpm.contract.exception.EngineException("broken priorityDefinition");
        when(elementSupport.resolveUserTaskPriorityOrThrow(any(), any())).thenThrow(broken);
        DBService dbService = mock(DBService.class);
        UserTaskHandler handler = new UserTaskHandler(dbService, elementSupport,
            mock(MultiInstanceExecutor.class), mock(BoundaryScheduler.class),
            mock(com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService.class));

        BpmnElementModel element = userTaskElement("=broken");
        // Битое выражение — НЕ проброс: метод возвращает false + инцидент.
        assertThat(handler.createTaskRow(UUID.randomUUID(), UUID.randomUUID(), element))
            .as("broken priority still parks an incident, not a propagation")
            .isFalse();
        verify(dbService).createIncident(any(), any());
    }

    // --- Фикстуры ---

    private static BpmnElementModel miElement(String inputCollection) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId("miTask");
        element.setType(BpmnElementType.USER_TASK);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        MultiInstanceExtensionModel mi = new MultiInstanceExtensionModel();
        mi.setInputCollection(inputCollection);
        ext.setMultiInstanceExtension(mi);
        element.setExtensions(ext);
        return element;
    }

    private static BpmnElementModel miUserTaskElement(String cardinality, boolean withUserTaskExt) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId("miUserTask");
        element.setType(BpmnElementType.USER_TASK);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        MultiInstanceExtensionModel mi = new MultiInstanceExtensionModel();
        mi.setCardinality(cardinality);
        ext.setMultiInstanceExtension(mi);
        if (withUserTaskExt) {
            ext.setUserTaskExtension(new UserTaskExtensionModel());
        }
        element.setExtensions(ext);
        return element;
    }

    private static BpmnElementModel userTaskElement(String priority) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId("review");
        element.setType(BpmnElementType.USER_TASK);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        UserTaskExtensionModel ut = new UserTaskExtensionModel();
        ut.setPriority(priority);
        ext.setUserTaskExtension(ut);
        element.setExtensions(ext);
        return element;
    }
}
