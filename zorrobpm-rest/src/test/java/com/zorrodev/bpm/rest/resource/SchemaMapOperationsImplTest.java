package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.SaveElementSchemaDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapElementDTO;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.JsonSchemaValidator;
import com.zorrodev.bpm.engine.service.SchemaMapService;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-4e — unit tests for the SchemaMap domain slice. Real
 * {@link SchemaMapOperationsImpl} over a real {@link SchemaMapService} with
 * mocked repositories/BpmnService/ObjectMapper/validator/support (WO-DEBT-7 S5:
 * only the wiring moved — all 19 expectations unchanged).
 */
class SchemaMapOperationsImplTest {

    private ProcessDefinitionRepository processDefinitionRepository;
    private FormRepository formRepository;
    private ElementArtifactBindingRepository bindingRepository;
    private BpmnService bpmnService;
    private ObjectMapper objectMapper;
    private JsonSchemaValidator jsonSchemaValidator;
    private FormAccessSupport formAccessSupport;
    private SchemaMapOperationsImpl impl;

    private final Principal admin =
        new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    private final Principal user =
        new Principal.UserPrincipal(UUID.randomUUID(), "user", "USER");

    @BeforeEach
    void setup() {
        processDefinitionRepository = mock(ProcessDefinitionRepository.class);
        formRepository = mock(FormRepository.class);
        bindingRepository = mock(ElementArtifactBindingRepository.class);
        bpmnService = mock(BpmnService.class);
        objectMapper = mock(ObjectMapper.class);
        jsonSchemaValidator = mock(JsonSchemaValidator.class);
        formAccessSupport = mock(FormAccessSupport.class);
        // WO-DEBT-7 S5: the impl is now a thin facade — test through it into a
        // real service over the same mocks (assertions below unchanged).
        SchemaMapService schemaMapService = new SchemaMapService(processDefinitionRepository,
            formRepository, bindingRepository, bpmnService, objectMapper, jsonSchemaValidator);
        impl = new SchemaMapOperationsImpl(schemaMapService, formAccessSupport);
    }

    private static ProcessDefinitionEntity pd(String key, int version) {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey(key);
        pd.setVersion(version);
        return pd;
    }

    private static BpmnElementModel startEvent(String id) {
        BpmnElementModel e = new BpmnElementModel();
        e.setId(id);
        e.setName("Start");
        e.setType(BpmnElementType.START_EVENT);
        return e;
    }

    private static BpmnElementModel userTask(String id, String formKey, String externalReference) {
        BpmnElementModel e = new BpmnElementModel();
        e.setId(id);
        e.setName("Approve");
        e.setType(BpmnElementType.USER_TASK);
        if (formKey != null || externalReference != null) {
            BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
            UserTaskExtensionModel ute = new UserTaskExtensionModel();
            ute.setFormKey(formKey);
            ute.setExternalReference(externalReference);
            ext.setUserTaskExtension(ute);
            e.setExtensions(ext);
        }
        return e;
    }

    private static ElementArtifactBindingEntity binding(UUID pdId, String elementId, String artifactKey, int artifactVersion) {
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setId(UUID.randomUUID());
        b.setProcessDefinitionId(pdId);
        b.setElementId(elementId);
        b.setArtifactKey(artifactKey);
        b.setArtifactVersion(artifactVersion);
        return b;
    }

    private static SaveElementSchemaDTO schemaDto(String kind, String schema) {
        SaveElementSchemaDTO dto = new SaveElementSchemaDTO();
        dto.setKind(kind);
        dto.setSchema(schema);
        return dto;
    }

    // ==================== getSchemaMap ====================

    @Test
    void getSchemaMap_happy_globalUsageSharedAcrossPds() {
        // WO-VM-9a: shared=true when >1 usage across ALL PDs, not just the current one
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(startEvent("start1"));
        model.addElement(userTask("task1", "sharedArt", null));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(model);
        ElementArtifactBindingEntity current = binding(pd.getId(), "start1", "sharedArt", 5);
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of(current));
        // The same key is also bound elsewhere → global usage 2 → shared
        when(bindingRepository.findByArtifactKeyIn(any()))
            .thenReturn(List.of(current, binding(UUID.randomUUID(), "other", "sharedArt", 1)));
        when(processDefinitionRepository.findAll()).thenReturn(List.of());
        FormEntity art = new FormEntity();
        art.setFormKey("sharedArt");
        art.setVersion(5);
        art.setKind(FormArtifactKind.FORM_JS);
        art.setSchemaJson("{\"components\":[]}");
        when(formRepository.findByFormKeyIn(any())).thenReturn(List.of(art));

        SchemaMapDTO result = impl.getSchemaMap("ord");

        assertThat(result.getProcessDefinitionKey()).isEqualTo("ord");
        assertThat(result.getVersion()).isEqualTo(3);
        assertThat(result.getElements()).hasSize(2);
        assertThat(result.getElements())
            .filteredOn(e -> e.getElementId().equals("start1"))
            .singleElement()
            .satisfies(e -> {
                assertThat(e.getArtifactKey()).isEqualTo("sharedArt");
                assertThat(e.getKind()).isEqualTo("FORM_JS");
                assertThat(e.getArtifactVersion()).isEqualTo(5);
                assertThat(e.isShared()).isTrue();
                assertThat(e.isHasExternalReference()).isFalse();
            });
        assertThat(result.getElements())
            .filteredOn(e -> e.getElementId().equals("task1"))
            .singleElement()
            .satisfies(e -> {
                assertThat(e.getArtifactKey()).isEqualTo("sharedArt");
                assertThat(e.isShared()).isTrue();
            });
    }

    @Test
    void getSchemaMap_singleUsage_notShared_externalRefCounted() {
        // Usage counted from user-task formKeys of OTHER PDs too (WO-VM-9a second source)
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(userTask("task1", "extArt", "https://ext.example/f"));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(model);
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of());
        when(bindingRepository.findByArtifactKeyIn(any())).thenReturn(List.of());
        ProcessDefinitionEntity otherPd = pd("other", 1);
        when(processDefinitionRepository.findAll()).thenReturn(List.of(otherPd));
        BpmnProcessDefinitionModel otherModel = new BpmnProcessDefinitionModel();
        otherModel.addElement(userTask("otask", "extArt", null));
        when(bpmnService.getProcessDefinitionModelById(eq(otherPd.getId()))).thenReturn(otherModel);
        when(formRepository.findByFormKeyIn(any())).thenReturn(List.of());

        SchemaMapDTO result = impl.getSchemaMap("ord");

        // usage: 0 bindings + 1 (other PD user-task formKey) = 1 → not shared
        assertThat(result.getElements()).hasSize(1);
        assertThat(result.getElements().get(0).isShared()).isFalse();
        assertThat(result.getElements().get(0).isHasExternalReference()).isTrue();
        assertThat(result.getElements().get(0).getArtifactKey()).isEqualTo("extArt");
    }

    @Test
    void getSchemaMap_deny_404BeforeParsing() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"))
            .when(formAccessSupport).requirePdAccess(pd.getId());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getSchemaMap("ord"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(bpmnService, never()).getProcessDefinitionModelById(any());
    }

    @Test
    void getSchemaMap_processNotFound_404() {
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.getSchemaMap("missing"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void getSchemaMap_emptyModel_noElements() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId())))
            .thenReturn(new BpmnProcessDefinitionModel());
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of());
        when(bindingRepository.findByArtifactKeyIn(any())).thenReturn(List.of());
        when(processDefinitionRepository.findAll()).thenReturn(List.of());

        SchemaMapDTO result = impl.getSchemaMap("ord");

        assertThat(result.getElements()).isEmpty();
    }

    @Test
    void getSchemaMap_batchedReads_noFullScansNoPerElementLookups() {
        // WO-AUDIT-3 (P3): one getSchemaMap must not scan whole tables nor fan out
        // per-element form lookups — scoped IN-queries only.
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(startEvent("start1"));
        model.addElement(userTask("task1", "artA", null));
        model.addElement(userTask("task2", "artB", null));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(model);
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of());
        when(bindingRepository.findByArtifactKeyIn(any())).thenReturn(List.of());
        when(processDefinitionRepository.findAll()).thenReturn(List.of());
        FormEntity fa = new FormEntity();
        fa.setFormKey("artA");
        fa.setVersion(2);
        fa.setKind(FormArtifactKind.FORM_JS);
        FormEntity fb = new FormEntity();
        fb.setFormKey("artB");
        fb.setVersion(1);
        fb.setKind(FormArtifactKind.VARIABLE_SCHEMA);
        when(formRepository.findByFormKeyIn(any())).thenReturn(List.of(fa, fb));

        SchemaMapDTO result = impl.getSchemaMap("ord");

        assertThat(result.getElements()).hasSize(3);
        verify(formRepository, times(1)).findByFormKeyIn(argThat(keys ->
            keys.contains("artA") && keys.contains("artB")));
        verify(formRepository, never()).findTopByFormKeyOrderByVersionDesc(any());
        verify(bindingRepository, never()).findAll();
        verify(bindingRepository, times(1)).findByArtifactKeyIn(any());
    }

    // ==================== saveElementSchema ====================

    @Test
    void saveElementSchema_happy_startEvent_autogenKeyVersionPlusOne() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(startEvent("start1"));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(model);
        when(formRepository.findMaxVersionByFormKey("ord:start1")).thenReturn(1);
        when(bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), "start1"))
            .thenReturn(Optional.empty());

        SchemaMapElementDTO result =
            impl.saveElementSchema("ord", "start1", schemaDto("FORM_JS", "{\"components\":[]}"));

        // START_EVENT key auto-generated as key:elementId, version max+1
        assertThat(result.getArtifactKey()).isEqualTo("ord:start1");
        assertThat(result.getArtifactVersion()).isEqualTo(2);
        assertThat(result.getKind()).isEqualTo("FORM_JS");
        ArgumentCaptor<FormEntity> captor = ArgumentCaptor.forClass(FormEntity.class);
        verify(formRepository).save(captor.capture());
        assertThat(captor.getValue().getFormKey()).isEqualTo("ord:start1");
        assertThat(captor.getValue().getVersion()).isEqualTo(2);
    }

    @Test
    void saveElementSchema_happy_startEvent_upsertPinsVersion() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(startEvent("start1"));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(model);
        when(formRepository.findMaxVersionByFormKey("ord:start1")).thenReturn(4);
        ElementArtifactBindingEntity existing = binding(pd.getId(), "start1", "ord:start1", 4);
        when(bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), "start1"))
            .thenReturn(Optional.of(existing));

        SchemaMapElementDTO result =
            impl.saveElementSchema("ord", "start1", schemaDto("FORM_JS", "{\"components\":[]}"));

        verify(bindingRepository).delete(existing);
        ArgumentCaptor<ElementArtifactBindingEntity> captor =
            ArgumentCaptor.forClass(ElementArtifactBindingEntity.class);
        verify(bindingRepository).save(captor.capture());
        // Pin: new binding nails the just-created version 5, not "latest"
        assertThat(captor.getValue().getArtifactVersion()).isEqualTo(5);
        assertThat(result.getArtifactVersion()).isEqualTo(5);
    }

    @Test
    void saveElementSchema_happy_userTask_noBindingWrite() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(userTask("task1", null, "extForm"));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(model);
        when(formRepository.findMaxVersionByFormKey("extForm")).thenReturn(0);

        SchemaMapElementDTO result =
            impl.saveElementSchema("ord", "task1", schemaDto("FORM_JS", "{\"components\":[]}"));

        assertThat(result.getArtifactKey()).isEqualTo("extForm");
        assertThat(result.getArtifactVersion()).isEqualTo(1);
        assertThat(result.isHasExternalReference()).isTrue();
        verify(formRepository).save(any(FormEntity.class));
        verify(bindingRepository, never()).save(any(ElementArtifactBindingEntity.class));
    }

    @Test
    void saveElementSchema_deny_nonAdmin_403AndNothingSaved() {
        when(formAccessSupport.getPrincipal()).thenReturn(user);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.saveElementSchema("ord", "start1", schemaDto("FORM_JS", "{\"components\":[]}")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_unauthenticated_401() {
        when(formAccessSupport.getPrincipal()).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.saveElementSchema("ord", "start1", schemaDto("FORM_JS", "{\"components\":[]}")));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_blankKind_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.saveElementSchema("ord", "start1", schemaDto(null, "{\"components\":[]}")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_invalidKind_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.saveElementSchema("ord", "start1", schemaDto("WRONG", "{\"components\":[]}")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_blankSchema_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.saveElementSchema("ord", "start1", schemaDto("FORM_JS", " ")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_invalidJson_400() throws Exception {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(objectMapper.readValue(eq("{oops"), eq(Object.class)))
            .thenThrow(new RuntimeException("bad json"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.saveElementSchema("ord", "start1", schemaDto("FORM_JS", "{oops")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_invalidVariableSchema_400() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(jsonSchemaValidator.validateSchema(any())).thenReturn(Set.of("bad type"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.saveElementSchema("ord", "start1",
                schemaDto("VARIABLE_SCHEMA", "{\"type\":\"object\"}")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_processNotFound_404() {
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.saveElementSchema("missing", "start1", schemaDto("FORM_JS", "{\"components\":[]}")));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_elementNotFound_404() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId())))
            .thenReturn(new BpmnProcessDefinitionModel());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.saveElementSchema("ord", "nope", schemaDto("FORM_JS", "{\"components\":[]}")));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_userTaskWithoutExternalReference_400() {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(formAccessSupport.getPrincipal()).thenReturn(admin);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.addElement(userTask("task1", "someForm", null));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(model);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> impl.saveElementSchema("ord", "task1", schemaDto("FORM_JS", "{\"components\":[]}")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(formRepository, never()).save(any(FormEntity.class));
    }
}
