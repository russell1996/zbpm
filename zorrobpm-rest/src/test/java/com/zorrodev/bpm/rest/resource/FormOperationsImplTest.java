package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AdvisoryDeployLock;
import com.zorrodev.bpm.engine.service.FormDeploymentService;
import com.zorrodev.bpm.engine.service.JsonSchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-4e — unit tests for the Forms domain slice. Real
 * {@link FormOperationsImpl} over a real {@link FormDeploymentService} with
 * mocked repositories/ObjectMapper/validator/support (WO-DEBT-7 S7: only the
 * wiring moved — all 15 expectations unchanged).
 */
class FormOperationsImplTest {

    private FormRepository formRepository;
    private ElementArtifactBindingRepository bindingRepository;
    private ObjectMapper objectMapper;
    private JsonSchemaValidator jsonSchemaValidator;
    private FormAccessSupport formAccessSupport;
    private AdvisoryDeployLock advisoryDeployLock;
    private FormOperationsImpl impl;

    private final Principal admin =
        new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    private final Principal user =
        new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");

    @BeforeEach
    void setup() {
        formRepository = mock(FormRepository.class);
        bindingRepository = mock(ElementArtifactBindingRepository.class);
        objectMapper = mock(ObjectMapper.class);
        jsonSchemaValidator = mock(JsonSchemaValidator.class);
        formAccessSupport = mock(FormAccessSupport.class);
        advisoryDeployLock = mock(AdvisoryDeployLock.class);
        // WO-DEBT-7 S7: the impl is now a thin facade — test through it into a
        // real service over the same mocks (assertions below unchanged).
        FormDeploymentService formDeploymentService = new FormDeploymentService(
            formRepository, bindingRepository, objectMapper, jsonSchemaValidator, advisoryDeployLock);
        impl = new FormOperationsImpl(formDeploymentService, formAccessSupport);
    }

    private static FormEntity form(String key, int version) {
        FormEntity f = new FormEntity();
        f.setId(UUID.randomUUID());
        f.setFormKey(key);
        f.setVersion(version);
        f.setKind(FormArtifactKind.FORM_JS);
        f.setSchemaJson("{\"components\":[]}");
        return f;
    }

    private static ElementArtifactBindingEntity binding(UUID pdId, String artifactKey) {
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setId(UUID.randomUUID());
        b.setProcessDefinitionId(pdId);
        b.setArtifactKey(artifactKey);
        return b;
    }

    private static DeployFormDTO deploy(String key, String kind, String schema) {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(key);
        dto.setKind(kind);
        dto.setSchema(schema);
        return dto;
    }

    // ==================== listForms ====================

    @Test
    void listForms_seeAllNull_returnsEverything() {
        when(formAccessSupport.resolveAllowedPdIds()).thenReturn(null);
        when(formRepository.findLatestVersions()).thenReturn(List.of(form("a", 1), form("b", 2)));

        List<FormDTO> result = impl.listForms();

        assertThat(result).extracting(FormDTO::getKey).containsExactlyInAnyOrder("a", "b");
        // No filtering needed — helpers untouched
        verify(bindingRepository, never()).findAll();
    }

    @Test
    void listForms_emptyAllowed_onlyUnbound() {
        when(formAccessSupport.resolveAllowedPdIds()).thenReturn(Set.of());
        UUID pdId = UUID.randomUUID();
        when(bindingRepository.findAll()).thenReturn(List.of(binding(pdId, "bound")));
        when(formRepository.findLatestVersions()).thenReturn(List.of(form("bound", 1), form("free", 2)));

        List<FormDTO> result = impl.listForms();

        assertThat(result).extracting(FormDTO::getKey).containsExactly("free");
    }

    @Test
    void listForms_nonEmptyAllowed_boundPlusUnbound() {
        UUID pdId = UUID.randomUUID();
        when(formAccessSupport.resolveAllowedPdIds()).thenReturn(Set.of(pdId));
        when(bindingRepository.findByProcessDefinitionIdIn(Set.of(pdId)))
            .thenReturn(List.of(binding(pdId, "bound")));
        // "foreign" is bound to ANOTHER PD (visible in findAll, not in the allowed set)
        when(bindingRepository.findAll())
            .thenReturn(List.of(binding(pdId, "bound"), binding(UUID.randomUUID(), "foreign")));
        when(formRepository.findLatestVersions())
            .thenReturn(List.of(form("bound", 1), form("free", 2), form("foreign", 1)));

        List<FormDTO> result = impl.listForms();

        // "foreign" is bound to another PD the principal cannot see → filtered out
        assertThat(result).extracting(FormDTO::getKey).containsExactlyInAnyOrder("bound", "free");
    }

    // ==================== deployForm ====================

    @Test
    void deployForm_linkedFormIdFromSchemaJson_storedForResolve() throws Exception {
        // WO-C8-22: выложенный .form с "id" в JSON несёт form_id в строке (ключ адресации
        // linked-форм); без "id" — null, key-путь как раньше.
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(formRepository.findMaxVersionByFormKey("linkedKey")).thenReturn(0);

        impl.deployForm(deploy("linkedKey", "FORM_JS", "{\"id\":\"linked-form\",\"components\":[]}"));

        ArgumentCaptor<FormEntity> captor = ArgumentCaptor.forClass(FormEntity.class);
        verify(formRepository).save(captor.capture());
        assertThat(captor.getValue().getFormId()).isEqualTo("linked-form");
        assertThat(captor.getValue().getFormKey()).isEqualTo("linkedKey");
    }

    @Test
    void deployForm_happy_versionMaxPlusOne() throws Exception {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(formRepository.findMaxVersionByFormKey("orderForm")).thenReturn(2);

        FormDTO result = impl.deployForm(deploy("orderForm", "FORM_JS", "{\"components\":[]}"));

        assertThat(result.getKey()).isEqualTo("orderForm");
        assertThat(result.getVersion()).isEqualTo(3);
        assertThat(result.getKind()).isEqualTo("FORM_JS");
        ArgumentCaptor<FormEntity> captor = ArgumentCaptor.forClass(FormEntity.class);
        verify(formRepository).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
        assertThat(captor.getValue().getSchemaJson()).isEqualTo("{\"components\":[]}");
        // WO-C8-22: в JSON нет "id" — form_id null, key-путь прежний
        assertThat(captor.getValue().getFormId()).isNull();
    }

    @Test
    void deployForm_validVariableSchema_200() throws Exception {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(formRepository.findMaxVersionByFormKey("vars")).thenReturn(0);
        when(jsonSchemaValidator.validateSchema("{\"type\":\"object\"}")).thenReturn(Set.of());

        FormDTO result = impl.deployForm(deploy("vars", "VARIABLE_SCHEMA", "{\"type\":\"object\"}"));

        assertThat(result.getVersion()).isEqualTo(1);
        verify(formRepository).save(any(FormEntity.class));
    }

    @Test
    void deployForm_deny_nonAdmin_403AndNothingSaved() {
        when(formAccessSupport.getPrincipal()).thenReturn(user);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deployForm(deploy("orderForm", "FORM_JS", "{\"components\":[]}")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_unauthenticated_401() {
        when(formAccessSupport.getPrincipal()).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deployForm(deploy("orderForm", "FORM_JS", "{\"components\":[]}")));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_blankKey_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deployForm(deploy("  ", "FORM_JS", "{\"components\":[]}")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_blankKind_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deployForm(deploy("orderForm", null, "{\"components\":[]}")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_blankSchema_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deployForm(deploy("orderForm", "FORM_JS", "")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_invalidKind_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deployForm(deploy("orderForm", "WRONG", "{\"components\":[]}")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_invalidJson_400() throws Exception {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(objectMapper.readValue(eq("{not-json"), eq(Object.class)))
            .thenThrow(new RuntimeException("bad json"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deployForm(deploy("orderForm", "FORM_JS", "{not-json")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_invalidVariableSchema_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(jsonSchemaValidator.validateSchema(any())).thenReturn(Set.of("bad type"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.deployForm(deploy("vars", "VARIABLE_SCHEMA", "{\"type\":\"object\"}")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    // ==================== getForm ====================

    @Test
    void getForm_happy_unbound() {
        when(formRepository.findTopByFormKeyOrderByVersionDesc("orderForm"))
            .thenReturn(Optional.of(form("orderForm", 3)));
        when(bindingRepository.findByArtifactKey("orderForm")).thenReturn(List.of());

        FormDTO result = impl.getForm("orderForm");

        assertThat(result.getKey()).isEqualTo("orderForm");
        assertThat(result.getVersion()).isEqualTo(3);
    }

    @Test
    void getForm_happy_boundVisible() {
        UUID pdId = UUID.randomUUID();
        when(formAccessSupport.resolveAllowedPdIds()).thenReturn(Set.of(pdId));
        when(formRepository.findTopByFormKeyOrderByVersionDesc("orderForm"))
            .thenReturn(Optional.of(form("orderForm", 3)));
        when(bindingRepository.findByArtifactKey("orderForm"))
            .thenReturn(List.of(binding(pdId, "orderForm")));

        FormDTO result = impl.getForm("orderForm");

        assertThat(result.getKey()).isEqualTo("orderForm");
    }

    @Test
    void getForm_deny_boundToForeignProcess_404() {
        when(formAccessSupport.resolveAllowedPdIds()).thenReturn(Set.of(UUID.randomUUID()));
        when(formRepository.findTopByFormKeyOrderByVersionDesc("orderForm"))
            .thenReturn(Optional.of(form("orderForm", 3)));
        when(bindingRepository.findByArtifactKey("orderForm"))
            .thenReturn(List.of(binding(UUID.randomUUID(), "orderForm")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getForm("orderForm"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void getForm_notFound_404() {
        when(formRepository.findTopByFormKeyOrderByVersionDesc("missing")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getForm("missing"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }
}
