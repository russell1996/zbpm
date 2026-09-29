package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CreateElementBindingDTO;
import com.zorrodev.bpm.contract.dto.ElementBindingDTO;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.ElementBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-4c — unit tests for the ElementBinding domain slice. Real
 * {@link ElementBindingOperationsImpl} over a real {@link ElementBindingService}
 * with mocked repositories + FormAccessSupport (WO-DEBT-7 S6: only the wiring
 * moved — all 15 expectations unchanged).
 */
class ElementBindingOperationsImplTest {

    private ProcessDefinitionRepository processDefinitionRepository;
    private FormRepository formRepository;
    private ElementArtifactBindingRepository bindingRepository;
    private FormAccessSupport formAccessSupport;
    private ElementBindingOperationsImpl impl;

    private final Principal admin =
        new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    private final Principal user =
        new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");

    @BeforeEach
    void setup() {
        processDefinitionRepository = mock(ProcessDefinitionRepository.class);
        formRepository = mock(FormRepository.class);
        bindingRepository = mock(ElementArtifactBindingRepository.class);
        formAccessSupport = mock(FormAccessSupport.class);
        // WO-DEBT-7 S6: the impl is now a thin facade — test through it into a
        // real service over the same mocks (assertions below unchanged).
        ElementBindingService elementBindingService = new ElementBindingService(
            processDefinitionRepository, formRepository, bindingRepository);
        impl = new ElementBindingOperationsImpl(elementBindingService, formAccessSupport);
    }

    private static ProcessDefinitionEntity pd(String key, int version) {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey(key);
        pd.setVersion(version);
        return pd;
    }

    private static FormEntity artifact(String key, int version) {
        FormEntity f = new FormEntity();
        f.setId(UUID.randomUUID());
        f.setFormKey(key);
        f.setVersion(version);
        f.setKind(FormArtifactKind.FORM_JS);
        f.setSchemaJson("{\"components\":[]}");
        return f;
    }

    private static CreateElementBindingDTO dto(String elementId, String artifactKey) {
        CreateElementBindingDTO dto = new CreateElementBindingDTO();
        dto.setElementId(elementId);
        dto.setArtifactKey(artifactKey);
        return dto;
    }

    // ==================== createElementBinding ====================

    @Test
    void create_happy_pinsArtifactVersionResolvedAtCreation() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(formRepository.findTopByFormKeyOrderByVersionDesc("art1"))
            .thenReturn(Optional.of(artifact("art1", 4)));
        when(bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), "start1"))
            .thenReturn(Optional.empty());

        ElementBindingDTO result = impl.createElementBinding("ord", dto("start1", "art1"));

        // Explicit pin check (WO-DEBT-4a mutation 3 regression): version nailed to the
        // artifact resolved at creation time, on both the saved entity and the DTO
        assertThat(result.getArtifactVersion()).isEqualTo(4);
        assertThat(result.getElementId()).isEqualTo("start1");
        assertThat(result.getProcessDefinitionVersion()).isEqualTo(3);
        ArgumentCaptor<ElementArtifactBindingEntity> captor =
            ArgumentCaptor.forClass(ElementArtifactBindingEntity.class);
        verify(bindingRepository).save(captor.capture());
        assertThat(captor.getValue().getArtifactVersion()).isEqualTo(4);
        assertThat(captor.getValue().getProcessDefinitionId()).isEqualTo(pd.getId());
    }

    @Test
    void create_upsert_deletesExistingBinding() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(formRepository.findTopByFormKeyOrderByVersionDesc("art1"))
            .thenReturn(Optional.of(artifact("art1", 4)));
        ElementArtifactBindingEntity existing = new ElementArtifactBindingEntity();
        existing.setId(UUID.randomUUID());
        when(bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), "start1"))
            .thenReturn(Optional.of(existing));

        ElementBindingDTO result = impl.createElementBinding("ord", dto("start1", "art1"));

        verify(bindingRepository).delete(existing);
        verify(bindingRepository).save(any(ElementArtifactBindingEntity.class));
        assertThat(result.getArtifactVersion()).isEqualTo(4);
    }

    @Test
    void create_deny_nonAdmin_403AndNothingSaved() {
        when(formAccessSupport.getPrincipal()).thenReturn(user);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.createElementBinding("ord", dto("start1", "art1")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void create_unauthenticated_401() {
        when(formAccessSupport.getPrincipal()).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.createElementBinding("ord", dto("start1", "art1")));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void create_blankElementId_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.createElementBinding("ord", dto("  ", "art1")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void create_blankArtifactKey_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.createElementBinding("ord", dto("start1", null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void create_processNotFound_404() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.createElementBinding("missing", dto("start1", "art1")));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void create_artifactNotFound_404() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(formRepository.findTopByFormKeyOrderByVersionDesc("nope")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.createElementBinding("ord", dto("start1", "nope")));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(bindingRepository, never()).save(any());
    }

    // ==================== listElementBindings ====================

    @Test
    void list_happy_mapsAllFields() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setId(UUID.randomUUID());
        b.setProcessDefinitionId(pd.getId());
        b.setProcessDefinitionVersion(3);
        b.setElementId("start1");
        b.setArtifactKey("a1");
        b.setArtifactVersion(2);
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of(b));

        List<ElementBindingDTO> result = assertDoesNotThrow(() -> impl.listElementBindings("ord"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getElementId()).isEqualTo("start1");
        assertThat(result.get(0).getArtifactKey()).isEqualTo("a1");
        assertThat(result.get(0).getArtifactVersion()).isEqualTo(2);
        assertThat(result.get(0).getProcessDefinitionId()).isEqualTo(pd.getId());
        verify(formAccessSupport).requirePdAccess(pd.getId());
    }

    @Test
    void list_deniedBySupport_404AndNoListing() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"))
            .when(formAccessSupport).requirePdAccess(pd.getId());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.listElementBindings("ord"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(bindingRepository, never()).findByProcessDefinitionId(any());
    }

    @Test
    void list_processNotFound_404() {
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.listElementBindings("missing"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // ==================== deleteElementBinding ====================

    @Test
    void delete_happy_deletesByPdAndElement() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));

        assertDoesNotThrow(() -> impl.deleteElementBinding("ord", "start1"));

        verify(bindingRepository).deleteByProcessDefinitionIdAndElementId(eq(pd.getId()), eq("start1"));
    }

    @Test
    void delete_deny_nonAdmin_403AndNothingDeleted() {
        when(formAccessSupport.getPrincipal()).thenReturn(user);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deleteElementBinding("ord", "start1"));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(bindingRepository, never()).deleteByProcessDefinitionIdAndElementId(any(), any());
    }

    @Test
    void delete_unauthenticated_401() {
        when(formAccessSupport.getPrincipal()).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deleteElementBinding("ord", "start1"));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        verify(bindingRepository, never()).deleteByProcessDefinitionIdAndElementId(any(), any());
    }

    @Test
    void delete_processNotFound_404() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deleteElementBinding("missing", "start1"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(bindingRepository, never()).deleteByProcessDefinitionIdAndElementId(any(), any());
    }
}
