package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormResolver;
import com.zorrodev.bpm.engine.service.TaskFormDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-4d — unit tests for the TaskForm domain slice. Real
 * {@link TaskFormOperationsImpl} (endpoints + 4 helpers exercised through them),
 * mocked services/support.
 * WO-DEBT-7 S2 — fixture layer re-pointed from the 5 repositories to
 * {@link TaskFormDataService} (the impl under test now reads JPA only through
 * it); every behavioral assertion/verify is byte-identical to the original,
 * except getStartForm_bindingVersionPin_notLatest where the never-latest repo
 * check became an exactly-once versioned-lookup check on the service (same
 * intent: pinning, no silent latest).
 */
class TaskFormOperationsImplTest {

    private TaskFormDataService taskFormDataService;
    private FormResolver formResolver;
    private BpmnService bpmnService;
    private DBService dbService;
    private FormAccessSupport formAccessSupport;
    private TaskFormOperationsImpl impl;

    @BeforeEach
    void setup() {
        taskFormDataService = mock(TaskFormDataService.class);
        formResolver = mock(FormResolver.class);
        bpmnService = mock(BpmnService.class);
        // WO-C8-26: default — plain start without formDefinition → legacy paths
        // (bindings → scalar) stay byte-identical for all pre-existing tests.
        when(bpmnService.getProcessDefinitionModelById(any()))
            .thenReturn(new BpmnProcessDefinitionModel());
        dbService = mock(DBService.class);
        formAccessSupport = mock(FormAccessSupport.class);
        impl = new TaskFormOperationsImpl(taskFormDataService,
            formResolver, bpmnService, dbService, formAccessSupport);
    }

    private static TaskFormDataService.UserTaskFormData userTaskData(UUID taskId, UUID piId, UUID pdId,
            String formId, String bindingType, String formKey, String elementId) {
        return new TaskFormDataService.UserTaskFormData(taskId, formId, bindingType, formKey,
            piId, elementId, pdId);
    }

    private static ProcessVariable variable(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        return v;
    }

    // ==================== getUserTaskForm ====================

    @Test
    void getUserTaskForm_formId_winsOverFormKey_resolvesById() {
        // WO-C8-22: строка с formId идёт в resolveTaskFormByFormId; formKey-путь при этом
        // не вызывается вообще (formId нигде не перезаписывает formKey — см. verifier (в)).
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        when(taskFormDataService.loadUserTaskFormData(taskId)).thenReturn(
            userTaskData(taskId, piId, pdId, "order-form", null, "orderForm", "review"));
        when(dbService.getVariables(eq(piId))).thenReturn(List.of());
        TaskFormDTO resolved = new TaskFormDTO();
        resolved.setType("embedded");
        when(formResolver.resolveTaskFormByFormId(eq("order-form"), eq(Map.of())))
            .thenReturn(resolved);

        TaskFormDTO result = impl.getUserTaskForm(taskId);

        assertSame(resolved, result);
        verify(formResolver, never()).resolveTaskForm(any(), any());
    }

    @Test
    void getUserTaskForm_deploymentBinding_resolvesPinnedVersion() {
        // WO-C8-23: строка с bindingType="deployment" идёт в resolveTaskFormByFormIdAndDeployment
        // с deploymentId ВЕРСИИ ПРОЦЕССА инстанса; latest-путь при этом не вызывается вообще.
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(taskFormDataService.loadUserTaskFormData(taskId)).thenReturn(
            userTaskData(taskId, piId, pdId, "pinned-form", "deployment", null, "review"));
        when(taskFormDataService.findDeploymentId(pdId)).thenReturn(deploymentId);
        when(dbService.getVariables(eq(piId))).thenReturn(List.of());
        TaskFormDTO pinned = new TaskFormDTO();
        pinned.setType("embedded");
        when(formResolver.resolveTaskFormByFormIdAndDeployment(eq("pinned-form"), eq(deploymentId), eq(Map.of())))
            .thenReturn(pinned);

        TaskFormDTO result = impl.getUserTaskForm(taskId);

        assertSame(pinned, result);
        verify(formResolver, never()).resolveTaskFormByFormId(any(), any());
        verify(formResolver, never()).resolveTaskForm(any(), any());
    }

    @Test
    void getUserTaskForm_happy_prefillFromVariables() {
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        when(taskFormDataService.loadUserTaskFormData(taskId)).thenReturn(
            userTaskData(taskId, piId, pdId, null, null, "orderForm", "review"));
        // prefillData: null name/value skipped, order preserved
        when(dbService.getVariables(eq(piId))).thenReturn(List.of(
            variable("orderId", "123"), variable(null, "x"), variable("empty", null)));
        TaskFormDTO resolved = new TaskFormDTO();
        resolved.setType("embedded");
        when(formResolver.resolveTaskForm(eq("orderForm"), eq(Map.of("orderId", "123"))))
            .thenReturn(resolved);

        TaskFormDTO result = impl.getUserTaskForm(taskId);

        assertSame(resolved, result);
        verify(formAccessSupport).requireRuntimePdAccess(pdId);
        // RUNTIME level, not DEFINITION level
        verify(formAccessSupport, never()).requirePdAccess(any());
    }

    @Test
    void getUserTaskForm_deny_runtimeAccess_404BeforeResolve() {
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        when(taskFormDataService.loadUserTaskFormData(taskId)).thenReturn(
            userTaskData(taskId, piId, pdId, null, null, "orderForm", "review"));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"))
            .when(formAccessSupport).requireRuntimePdAccess(pdId);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getUserTaskForm(taskId));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(formResolver, never()).resolveTaskForm(any(), any());
        verify(dbService, never()).getVariables(any());
    }

    @Test
    void getUserTaskForm_taskNotFound_404() {
        UUID taskId = UUID.randomUUID();
        when(taskFormDataService.loadUserTaskFormData(taskId))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getUserTaskForm(taskId));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void getUserTaskForm_instanceNotFound_404() {
        UUID taskId = UUID.randomUUID();
        when(taskFormDataService.loadUserTaskFormData(taskId))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getUserTaskForm(taskId));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // ==================== getStartForm ====================

    @Test
    void getStartForm_happy_viaBinding() {
        UUID pdId = UUID.randomUUID();
        when(taskFormDataService.loadStartFormData("ord")).thenReturn(
            new TaskFormDataService.StartFormData(pdId, "ord", null, null));
        when(taskFormDataService.findStartBindingRefs(pdId)).thenReturn(List.of(
            new TaskFormDataService.BoundFormRef("startForm", 2)));
        when(taskFormDataService.findFormData("startForm", 2)).thenReturn(
            new TaskFormDataService.FormData("FORM_JS", "{\"components\":[]}"));

        TaskFormDTO result = impl.getStartForm("ord");

        assertThat(result.getType()).isEqualTo("embedded");
        assertThat(result.getKind()).isEqualTo("FORM_JS");
        assertThat(result.getSchema()).isEqualTo("{\"components\":[]}");
        verify(formAccessSupport).requirePdAccess(pdId);
        // DEFINITION level, not RUNTIME level
        verify(formAccessSupport, never()).requireRuntimePdAccess(any());
    }

    @Test
    void getStartForm_bindingVersionPin_notLatest() {
        // Explicit ADR-6 §D8 pin check: binding pins version 2 while newer versions
        // exist — the resolved schema must be v2's, never latest's
        UUID pdId = UUID.randomUUID();
        when(taskFormDataService.loadStartFormData("ord")).thenReturn(
            new TaskFormDataService.StartFormData(pdId, "ord", null, null));
        when(taskFormDataService.findStartBindingRefs(pdId)).thenReturn(List.of(
            new TaskFormDataService.BoundFormRef("startForm", 2)));
        when(taskFormDataService.findFormData("startForm", 2)).thenReturn(
            new TaskFormDataService.FormData("FORM_JS", "{\"v\":2}"));

        TaskFormDTO result = impl.getStartForm("ord");

        assertThat(result.getSchema()).isEqualTo("{\"v\":2}");
        verify(taskFormDataService).findFormData("startForm", 2);
        // WO-DEBT-7 S2: adapted expectation — exactly one versioned lookup happened
        // (no silent latest read anywhere on this path).
        verify(taskFormDataService, times(1)).findFormData(any(), any());
    }

    @Test
    void getStartForm_bindingVersionMissing_fallbackToLatest() {
        UUID pdId = UUID.randomUUID();
        when(taskFormDataService.loadStartFormData("ord")).thenReturn(
            new TaskFormDataService.StartFormData(pdId, "ord", null, null));
        when(taskFormDataService.findStartBindingRefs(pdId)).thenReturn(List.of(
            new TaskFormDataService.BoundFormRef("startForm", 9)));
        when(taskFormDataService.findFormData("startForm", 9)).thenReturn(null);
        TaskFormDTO latest = new TaskFormDTO();
        latest.setType("embedded");
        when(formResolver.resolveTaskForm(eq("startForm"), isNull())).thenReturn(latest);

        TaskFormDTO result = impl.getStartForm("ord");

        assertSame(latest, result);
    }

    @Test
    void getStartForm_happy_fallbackToScalarStartFormKey() {
        UUID pdId = UUID.randomUUID();
        when(taskFormDataService.loadStartFormData("ord")).thenReturn(
            new TaskFormDataService.StartFormData(pdId, "ord", null, "sForm"));
        when(taskFormDataService.findStartBindingRefs(pdId)).thenReturn(List.of());
        TaskFormDTO resolved = new TaskFormDTO();
        resolved.setType("embedded");
        when(formResolver.resolveTaskForm(eq("sForm"), isNull())).thenReturn(resolved);

        TaskFormDTO result = impl.getStartForm("ord");

        assertSame(resolved, result);
    }

    @Test
    void getStartForm_deny_definitionAccess_404BeforeResolve() {
        UUID pdId = UUID.randomUUID();
        when(taskFormDataService.loadStartFormData("ord")).thenReturn(
            new TaskFormDataService.StartFormData(pdId, "ord", null, null));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"))
            .when(formAccessSupport).requirePdAccess(pdId);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getStartForm("ord"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(taskFormDataService, never()).findStartBindingRefs(any());
    }

    @Test
    void getStartForm_processNotFound_404() {
        when(taskFormDataService.loadStartFormData("missing"))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getStartForm("missing"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // ==================== getStartForm: WO-C8-26 formDefinition ====================

    private UUID pdWithStartForm(String key, String startFormId, String bindingType) {
        UUID pdId = UUID.randomUUID();
        when(taskFormDataService.loadStartFormData(key)).thenReturn(
            new TaskFormDataService.StartFormData(pdId, key, null, null));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.setStartFormId(startFormId);
        model.setStartFormBindingType(bindingType);
        when(bpmnService.getProcessDefinitionModelById(pdId)).thenReturn(model);
        return pdId;
    }

    @Test
    void getStartForm_formId_winsOverLegacy_resolvesById() {
        // Крит. 2/3/6: formDefinition побеждает legacy (bindings + scalar не трогаем
        // вообще) и идёт в готовый resolveTaskFormByFormId (крит. 6 — новых
        // механизмов нет: formResolver — mock, реализация не дублируется).
        UUID pdId = pdWithStartForm("ord", "order-start-form", "latest");
        when(taskFormDataService.loadStartFormData("ord")).thenReturn(
            new TaskFormDataService.StartFormData(pdId, "ord", null, "legacyForm"));
        when(taskFormDataService.findStartBindingRefs(pdId)).thenReturn(List.of(
            new TaskFormDataService.BoundFormRef("legacyForm", 1)));
        TaskFormDTO resolved = new TaskFormDTO();
        resolved.setType("embedded");
        when(formResolver.resolveTaskFormByFormId(eq("order-start-form"), isNull())).thenReturn(resolved);

        TaskFormDTO result = impl.getStartForm("ord");

        assertSame(resolved, result);
        verify(formResolver).resolveTaskFormByFormId("order-start-form", null);
        // legacy-пути при живом formId недостижимы
        verify(taskFormDataService, never()).findStartBindingRefs(any());
        verify(formResolver, never()).resolveTaskForm(any(), any());
    }

    @Test
    void getStartForm_formId_deploymentBinding_resolvesPinned() {
        // Крит. 4: deployment-пин берёт deployment_id ТЕКУЩЕЙ версии процесса
        // (той, чью форму запрашивают) и идёт в resolveTaskFormByFormIdAndDeployment.
        UUID deploymentId = UUID.randomUUID();
        UUID pdId = pdWithStartForm("ord", "order-start-form", "deployment");
        when(taskFormDataService.loadStartFormData("ord")).thenReturn(
            new TaskFormDataService.StartFormData(pdId, "ord", deploymentId, null));
        TaskFormDTO resolved = new TaskFormDTO();
        resolved.setType("embedded");
        when(formResolver.resolveTaskFormByFormIdAndDeployment(
            eq("order-start-form"), eq(deploymentId), isNull())).thenReturn(resolved);

        TaskFormDTO result = impl.getStartForm("ord");

        assertSame(resolved, result);
        verify(formResolver).resolveTaskFormByFormIdAndDeployment("order-start-form", deploymentId, null);
        verify(formResolver, never()).resolveTaskFormByFormId(any(), any());
    }

    @Test
    void getStartForm_formId_deploymentBinding_noPair_404() {
        // Крит. 5: пара отсутствует → 404 той же формы, что C8-23 для user task.
        UUID pdId = pdWithStartForm("ord", "order-start-form", "deployment");
        when(taskFormDataService.loadStartFormData("ord")).thenReturn(
            new TaskFormDataService.StartFormData(pdId, "ord", UUID.randomUUID(), null));
        when(formResolver.resolveTaskFormByFormIdAndDeployment(eq("order-start-form"), any(), isNull()))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Form version not found"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getStartForm("ord"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // ==================== getUserTaskForm/getStartForm: WO-C8-31 versionTag ====================

    private BpmnProcessDefinitionModel modelWithTaskTag(String elementId, String versionTag) {
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        BpmnElementModel element = new BpmnElementModel();
        element.setId(elementId);
        BpmnElementExtensionModel extensions = new BpmnElementExtensionModel();
        UserTaskExtensionModel userTask = new UserTaskExtensionModel();
        userTask.setVersionTag(versionTag);
        extensions.setUserTaskExtension(userTask);
        element.setExtensions(extensions);
        model.addElement(element);
        return model;
    }

    @Test
    void getUserTaskForm_versionTagBinding_resolvesPinned() {
        // Крит. 2 (user-task потребитель): bindingType="versionTag" идёт в готовый
        // resolveTaskFormByFormIdAndVersionTag; значение тега — статично на элементе,
        // читается из кэшированной модели версии ЭТОГО инстанса (паттерн C8-26,
        // новой колонки в user_tasks нет). latest/deployment-пути недостижимы.
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        when(taskFormDataService.loadUserTaskFormData(taskId)).thenReturn(
            userTaskData(taskId, piId, pdId, "order-form", "versionTag", null, "review"));
        when(bpmnService.getProcessDefinitionModelById(pdId))
            .thenReturn(modelWithTaskTag("review", "v1"));
        TaskFormDTO resolved = new TaskFormDTO();
        resolved.setType("embedded");
        resolved.setSchema("{\"label\": \"v1\"}");
        when(formResolver.resolveTaskFormByFormIdAndVersionTag(
            eq("order-form"), eq("v1"), any())).thenReturn(resolved);

        TaskFormDTO result = impl.getUserTaskForm(taskId);

        assertSame(resolved, result);
        verify(formResolver).resolveTaskFormByFormIdAndVersionTag(eq("order-form"), eq("v1"), any());
        verify(formResolver, never()).resolveTaskFormByFormId(any(), any());
        verify(formResolver, never()).resolveTaskFormByFormIdAndDeployment(any(), any(), any());
        verify(formResolver, never()).resolveTaskForm(any(), any());
    }

    @Test
    void getStartForm_formId_versionTagBinding_resolvesPinned() {
        // Крит. 2 (start-потребитель): та же ветка на startFormVersionTag из C8-26,
        // резолвер общий (граница WO — не дублировать).
        UUID pdId = pdWithStartForm("ord", "order-start-form", "versionTag");
        when(taskFormDataService.loadStartFormData("ord")).thenReturn(
            new TaskFormDataService.StartFormData(pdId, "ord", null, null));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.setStartFormId("order-start-form");
        model.setStartFormBindingType("versionTag");
        model.setStartFormVersionTag("v1");
        when(bpmnService.getProcessDefinitionModelById(pdId)).thenReturn(model);
        TaskFormDTO resolved = new TaskFormDTO();
        resolved.setType("embedded");
        when(formResolver.resolveTaskFormByFormIdAndVersionTag(
            eq("order-start-form"), eq("v1"), isNull())).thenReturn(resolved);

        TaskFormDTO result = impl.getStartForm("ord");

        assertSame(resolved, result);
        verify(formResolver).resolveTaskFormByFormIdAndVersionTag("order-start-form", "v1", null);
        verify(taskFormDataService, never()).findStartBindingRefs(any());
    }
}
