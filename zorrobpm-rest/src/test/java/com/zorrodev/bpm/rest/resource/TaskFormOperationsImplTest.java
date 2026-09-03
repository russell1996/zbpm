package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-4d — unit tests for the TaskForm domain slice. Real
 * {@link TaskFormOperationsImpl} (endpoints + 4 helpers exercised through them),
 * mocked repositories/services/support.
 */
class TaskFormOperationsImplTest {

    private UserTaskRepository userTaskRepository;
    private ProcessInstanceRepository processInstanceRepository;
    private ProcessDefinitionRepository processDefinitionRepository;
    private ElementArtifactBindingRepository bindingRepository;
    private FormRepository formRepository;
    private FormResolver formResolver;
    private DBService dbService;
    private FormAccessSupport formAccessSupport;
    private TaskFormOperationsImpl impl;

    @BeforeEach
    void setup() {
        userTaskRepository = mock(UserTaskRepository.class);
        processInstanceRepository = mock(ProcessInstanceRepository.class);
        processDefinitionRepository = mock(ProcessDefinitionRepository.class);
        bindingRepository = mock(ElementArtifactBindingRepository.class);
        formRepository = mock(FormRepository.class);
        formResolver = mock(FormResolver.class);
        dbService = mock(DBService.class);
        formAccessSupport = mock(FormAccessSupport.class);
        impl = new TaskFormOperationsImpl(userTaskRepository, processInstanceRepository,
            processDefinitionRepository, bindingRepository, formRepository,
            formResolver, dbService, formAccessSupport);
    }

    private static ProcessDefinitionEntity pd(String key, int version) {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey(key);
        pd.setVersion(version);
        return pd;
    }

    private static ProcessVariable variable(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        return v;
    }

    // ==================== getUserTaskForm ====================

    @Test
    void getUserTaskForm_happy_prefillFromVariables() {
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setFormKey("orderForm");
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(pdId);
        when(userTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
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
        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setFormKey("orderForm");
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(pdId);
        when(userTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
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
        when(userTaskRepository.findById(taskId)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getUserTaskForm(taskId));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void getUserTaskForm_instanceNotFound_404() {
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setFormKey("orderForm");
        when(userTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getUserTaskForm(taskId));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // ==================== getStartForm ====================

    @Test
    void getStartForm_happy_viaBinding() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setProcessDefinitionId(pd.getId());
        b.setArtifactKey("startForm");
        b.setArtifactVersion(2);
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of(b));
        FormEntity pinned = new FormEntity();
        pinned.setFormKey("startForm");
        pinned.setVersion(2);
        pinned.setKind(FormArtifactKind.FORM_JS);
        pinned.setSchemaJson("{\"components\":[]}");
        when(formRepository.findByFormKeyAndVersion("startForm", 2)).thenReturn(Optional.of(pinned));

        TaskFormDTO result = impl.getStartForm("ord");

        assertThat(result.getType()).isEqualTo("embedded");
        assertThat(result.getKind()).isEqualTo("FORM_JS");
        assertThat(result.getSchema()).isEqualTo("{\"components\":[]}");
        verify(formAccessSupport).requirePdAccess(pd.getId());
        // DEFINITION level, not RUNTIME level
        verify(formAccessSupport, never()).requireRuntimePdAccess(any());
    }

    @Test
    void getStartForm_bindingVersionPin_notLatest() {
        // Explicit ADR-6 §D8 pin check: binding pins version 2 while newer versions
        // exist — the resolved schema must be v2's, never latest's
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setProcessDefinitionId(pd.getId());
        b.setArtifactKey("startForm");
        b.setArtifactVersion(2);
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of(b));
        FormEntity v2 = new FormEntity();
        v2.setFormKey("startForm");
        v2.setVersion(2);
        v2.setKind(FormArtifactKind.FORM_JS);
        v2.setSchemaJson("{\"v\":2}");
        when(formRepository.findByFormKeyAndVersion("startForm", 2)).thenReturn(Optional.of(v2));

        TaskFormDTO result = impl.getStartForm("ord");

        assertThat(result.getSchema()).isEqualTo("{\"v\":2}");
        verify(formRepository).findByFormKeyAndVersion("startForm", 2);
        verify(formRepository, never()).findTopByFormKeyOrderByVersionDesc(any());
    }

    @Test
    void getStartForm_bindingVersionMissing_fallbackToLatest() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setProcessDefinitionId(pd.getId());
        b.setArtifactKey("startForm");
        b.setArtifactVersion(9);
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of(b));
        when(formRepository.findByFormKeyAndVersion("startForm", 9)).thenReturn(Optional.empty());
        TaskFormDTO latest = new TaskFormDTO();
        latest.setType("embedded");
        when(formResolver.resolveTaskForm(eq("startForm"), isNull())).thenReturn(latest);

        TaskFormDTO result = impl.getStartForm("ord");

        assertSame(latest, result);
    }

    @Test
    void getStartForm_happy_fallbackToScalarStartFormKey() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        pd.setStartFormKey("sForm");
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of());
        TaskFormDTO resolved = new TaskFormDTO();
        resolved.setType("embedded");
        when(formResolver.resolveTaskForm(eq("sForm"), isNull())).thenReturn(resolved);

        TaskFormDTO result = impl.getStartForm("ord");

        assertSame(resolved, result);
    }

    @Test
    void getStartForm_deny_definitionAccess_404BeforeResolve() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"))
            .when(formAccessSupport).requirePdAccess(pd.getId());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getStartForm("ord"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(bindingRepository, never()).findByProcessDefinitionId(any());
    }

    @Test
    void getStartForm_processNotFound_404() {
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getStartForm("missing"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }
}
