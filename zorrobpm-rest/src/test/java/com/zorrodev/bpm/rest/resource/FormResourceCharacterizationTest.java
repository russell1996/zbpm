package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.CreateElementBindingDTO;
import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.SaveElementSchemaDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.security.UiUserLookupService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-DEBT-4a — FormResource characterization (Phase 0, tests only).
 * V11 MockMvc + real JwtAuthFilter + real EventAuthzResolver + real FormResolver
 * + real JsonSchemaValidator; mocked repos/services only at the persistence boundary.
 *
 * 10 endpoints, each happy + deny-or-validation + not-found (or equivalent).
 * Pins the @Transactional boundary on the 4 write methods (reflection fact) so the
 * later slice can move it without silent loss.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormResourceCharacterizationTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /* --- mocked persistence / downstream boundary --- */
    @MockitoBean private FormRepository formRepository;
    @MockitoBean private UserTaskRepository userTaskRepository;
    @MockitoBean private ProcessDefinitionRepository processDefinitionRepository;
    @MockitoBean private ElementArtifactBindingRepository bindingRepository;
    @MockitoBean private ProcessInstanceRepository processInstanceRepository;
    @MockitoBean private ProcessRepository processRepository;
    @MockitoBean private ProcessMemberRepository processMemberRepository;
    @MockitoBean private UiUserRepository uiUserRepository;
    @MockitoBean private DBService dbService;
    @MockitoBean private BpmnService bpmnService;

    /* --- JwtAuthFilter deps: mock to control auth without DB --- */
    @MockitoBean private TokenService tokenService;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private ApiKeyGrantRepository apiKeyGrantRepository;
    @MockitoBean private UiUserLookupService userLookupService;
    @MockitoBean private AuthorizationService authorizationService;

    private static final String ADMIN_TOKEN = "test-admin-jwt";
    private static final String USER_TOKEN = "test-user-jwt";

    @BeforeEach
    void setupAuth() {
        // Stable ids so the JwtAuthFilter securityState stub (WO-SEC-63) can match per-token.
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        lenient().when(tokenService.verify(ADMIN_TOKEN)).thenReturn(
            new TokenService.Claims(adminId, "admin", "SUPER_ADMIN", 0, Long.MAX_VALUE));
        lenient().when(tokenService.verify(USER_TOKEN)).thenReturn(
            new TokenService.Claims(userId, "user", "USER", 0, Long.MAX_VALUE));
        // WO-SEC-63: filter checks live user-state (active/role/tokenVersion); version 0 matches
        // the claims above, roles must match exactly or the token is rejected.
        lenient().when(userLookupService.securityState(eq(adminId))).thenReturn(Optional.of(
            new UiUserLookupService.UserSecurityState(adminId, "admin", "SUPER_ADMIN", true, 0, false)));
        lenient().when(userLookupService.securityState(eq(userId))).thenReturn(Optional.of(
            new UiUserLookupService.UserSecurityState(userId, "user", "USER", true, 0, false)));
        lenient().when(userLookupService.isActive(any())).thenReturn(true);
        lenient().when(userLookupService.isForcePasswordChange(any())).thenReturn(false);
        lenient().when(authorizationService.effectiveGrants(any(), any())).thenReturn(Map.of());
        // USER has no process memberships → readableRuntimePdIds = empty → runtime DENY
        lenient().when(processMemberRepository.findByUserId(any())).thenReturn(List.of());
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

    private static ProcessDefinitionEntity pd(String key, int version) {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey(key);
        pd.setVersion(version);
        return pd;
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
    void listForms_happy_adminSeesAll() throws Exception {
        when(formRepository.findLatestVersions()).thenReturn(List.of(form("orderForm", 3), form("taskForm", 1)));
        when(bindingRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[?(@.key == 'orderForm')].version", hasItem(3)));
    }

    @Test
    void listForms_happy_userSeesDefinitionsToo() throws Exception {
        // WO-ACL-1 / ADR-8 §1: definition reads — any authenticated user sees all (null = no filter)
        when(formRepository.findLatestVersions()).thenReturn(List.of(form("orderForm", 3)));
        when(bindingRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/forms")
                .header("Authorization", "Bearer " + USER_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].key").value("orderForm"));
    }

    @Test
    void listForms_empty() throws Exception {
        when(formRepository.findLatestVersions()).thenReturn(List.of());
        when(bindingRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listForms_unauthenticated_401() throws Exception {
        mockMvc.perform(get("/forms"))
            .andExpect(status().isUnauthorized());
    }

    // ==================== deployForm ====================

    @Test
    void deployForm_versionTagParsedFromSchemaJson() throws Exception {
        // WO-C8-31, крит. 1 (upload-путь): top-level "versionTag" JSON штампуется в
        // version_tag сохраняемой строки (каптор — прод-маппинг DTO→entity идёт реально).
        when(formRepository.findMaxVersionByFormKey("orderForm")).thenReturn(2);
        org.mockito.ArgumentCaptor<FormEntity> captor =
            org.mockito.ArgumentCaptor.forClass(FormEntity.class);

        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", "FORM_JS",
                    "{\"id\": \"order-form\", \"versionTag\": \"v1\", \"components\":[]}"))))
            .andExpect(status().isCreated());

        verify(formRepository).save(captor.capture());
        assertThat(captor.getValue().getFormId()).isEqualTo("order-form");
        assertThat(captor.getValue().getVersionTag()).isEqualTo("v1");
    }

    @Test
    void deployForm_noVersionTag_storesNull() throws Exception {
        // WO-C8-31, крит. 1 (lenient): тега нет — null, строка резолвится как раньше.
        when(formRepository.findMaxVersionByFormKey("orderForm")).thenReturn(2);
        org.mockito.ArgumentCaptor<FormEntity> captor =
            org.mockito.ArgumentCaptor.forClass(FormEntity.class);

        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", "FORM_JS",
                    "{\"components\":[]}"))))
            .andExpect(status().isCreated());

        verify(formRepository).save(captor.capture());
        assertThat(captor.getValue().getVersionTag()).isNull();
    }

    @Test
    void deployForm_happy_versionMaxPlusOne() throws Exception {
        when(formRepository.findMaxVersionByFormKey("orderForm")).thenReturn(2);

        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", "FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.key").value("orderForm"))
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.kind").value("FORM_JS"));

        verify(formRepository).save(any(FormEntity.class));
    }

    @Test
    void deployForm_versioning_existingKeyWithManyVersions() throws Exception {
        when(formRepository.findMaxVersionByFormKey("orderForm")).thenReturn(5);

        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", "FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(6));
    }

    @Test
    void deployForm_deny_userForbiddenAndNothingSaved() throws Exception {
        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", "FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isForbidden());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_unauthenticated_401() throws Exception {
        mockMvc.perform(post("/forms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", "FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isUnauthorized());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_blankKey_400() throws Exception {
        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("  ", "FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isBadRequest());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_blankKind_400() throws Exception {
        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", null, "{\"components\":[]}"))))
            .andExpect(status().isBadRequest());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_blankSchema_400() throws Exception {
        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", "FORM_JS", ""))))
            .andExpect(status().isBadRequest());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_invalidKind_400() throws Exception {
        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", "WRONG", "{\"components\":[]}"))))
            .andExpect(status().isBadRequest());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_invalidJson_400() throws Exception {
        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", "FORM_JS", "{not-json"))))
            .andExpect(status().isBadRequest());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_invalidVariableSchema_400() throws Exception {
        // Valid JSON, but not a parseable JSON Schema (WO-VM-5, networknt Draft 2020-12):
        // $schema points to a non-existent draft (proven payload from
        // JsonSchemaValidationIntegrationTest.criterion2_invalidSchema_returns400).
        // Note: an unknown "type" alone is TOLERATED by networknt at parse time — characterization fact.
        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("orderForm", "VARIABLE_SCHEMA", "{\"$schema\": \"https://json-schema.org/draft/9999-99/schema\", \"type\": \"object\"}"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("Invalid JSON Schema")));

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void deployForm_validVariableSchema_200() throws Exception {
        when(formRepository.findMaxVersionByFormKey("vars")).thenReturn(0);

        mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deploy("vars", "VARIABLE_SCHEMA", "{\"type\":\"object\"}"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1));
    }

    // ==================== getForm ====================

    @Test
    void getForm_happy_unbound() throws Exception {
        when(formRepository.findTopByFormKeyOrderByVersionDesc("orderForm"))
            .thenReturn(Optional.of(form("orderForm", 3)));
        when(bindingRepository.findByArtifactKey("orderForm")).thenReturn(List.of());

        mockMvc.perform(get("/forms/orderForm")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("orderForm"))
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.kind").value("FORM_JS"));
    }

    @Test
    void getForm_happy_boundVisibleToUser() throws Exception {
        // G-L deny-by-default, but allowedPdIds == null for any authenticated user (ADR-8 §1) → see all
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setProcessDefinitionId(UUID.randomUUID());
        b.setArtifactKey("orderForm");
        when(formRepository.findTopByFormKeyOrderByVersionDesc("orderForm"))
            .thenReturn(Optional.of(form("orderForm", 3)));
        when(bindingRepository.findByArtifactKey("orderForm")).thenReturn(List.of(b));

        mockMvc.perform(get("/forms/orderForm")
                .header("Authorization", "Bearer " + USER_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("orderForm"));
    }

    @Test
    void getForm_notFound() throws Exception {
        when(formRepository.findTopByFormKeyOrderByVersionDesc("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/forms/missing")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isNotFound());
    }

    // ==================== getUserTaskForm ====================

    @Test
    void getUserTaskForm_happy_embeddedWithPrefill() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setFormKey("orderForm");
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(UUID.randomUUID());
        when(userTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(formRepository.findTopByFormKeyOrderByVersionDesc("orderForm"))
            .thenReturn(Optional.of(form("orderForm", 2)));
        ProcessVariable v = new ProcessVariable();
        v.setName("orderId");
        v.setValue("123");
        when(dbService.getVariables(eq(piId))).thenReturn(List.of(v));

        // Real FormResolver: proves actual resolve (schema) + prefill (data), not just non-empty body
        mockMvc.perform(get("/user-tasks/" + taskId + "/form")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("embedded"))
            .andExpect(jsonPath("$.kind").value("FORM_JS"))
            .andExpect(jsonPath("$.schema").value("{\"components\":[]}"))
            .andExpect(jsonPath("$.data.orderId").value("123"));
    }

    @Test
    void getUserTaskForm_deny_userWithoutMembership_404() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setFormKey("orderForm");
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(UUID.randomUUID());
        when(userTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));

        mockMvc.perform(get("/user-tasks/" + taskId + "/form")
                .header("Authorization", "Bearer " + USER_TOKEN))
            .andExpect(status().isNotFound());

        verify(dbService, never()).getVariables(any());
    }

    @Test
    void getUserTaskForm_taskNotFound() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(userTaskRepository.findById(taskId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/user-tasks/" + taskId + "/form")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isNotFound());
    }

    @Test
    void getUserTaskForm_instanceNotFound() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(piId);
        task.setFormKey("orderForm");
        when(userTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/user-tasks/" + taskId + "/form")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isNotFound());
    }

    // ==================== getStartForm ====================

    @Test
    void getStartForm_happy_bindingPinnedToVersion() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setProcessDefinitionId(pd.getId());
        b.setArtifactKey("startForm");
        b.setArtifactVersion(2);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of(b));
        FormEntity pinned = form("startForm", 2);
        when(formRepository.findByFormKeyAndVersion("startForm", 2)).thenReturn(Optional.of(pinned));
        // WO-C8-26: plain start without formDefinition → legacy binding path
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId())))
            .thenReturn(new BpmnProcessDefinitionModel());
        // ADR-6 §D8: pinned to artifact_version from binding, not latest
        mockMvc.perform(get("/process-definitions/ord/start-form")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("embedded"))
            .andExpect(jsonPath("$.kind").value("FORM_JS"))
            .andExpect(jsonPath("$.schema").value("{\"components\":[]}"));
    }

    @Test
    void getStartForm_happy_fallbackToScalarStartFormKey() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        pd.setStartFormKey("sForm");
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of());
        when(formRepository.findTopByFormKeyOrderByVersionDesc("sForm"))
            .thenReturn(Optional.of(form("sForm", 1)));
        // WO-C8-26: plain start without formDefinition → legacy scalar path
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId())))
            .thenReturn(new BpmnProcessDefinitionModel());

        mockMvc.perform(get("/process-definitions/ord/start-form")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("embedded"))
            .andExpect(jsonPath("$.schema").value("{\"components\":[]}"));
    }

    @Test
    void getStartForm_noForm_returnsNone() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of());
        // WO-C8-26: plain start without formDefinition → none path
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId())))
            .thenReturn(new BpmnProcessDefinitionModel());

        mockMvc.perform(get("/process-definitions/ord/start-form")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("none"));
    }

    @Test
    void getStartForm_processNotFound() throws Exception {
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/process-definitions/missing/start-form")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isNotFound());
    }

    // ==================== createElementBinding ====================

    @Test
    void createElementBinding_happy_pinsArtifactVersion() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        FormEntity artifact = form("art1", 4);
        when(formRepository.findTopByFormKeyOrderByVersionDesc("art1")).thenReturn(Optional.of(artifact));
        when(bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), "start1"))
            .thenReturn(Optional.empty());

        CreateElementBindingDTO dto = new CreateElementBindingDTO();
        dto.setElementId("start1");
        dto.setArtifactKey("art1");

        mockMvc.perform(post("/process-definitions/ord/element-bindings")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.elementId").value("start1"))
            .andExpect(jsonPath("$.artifactKey").value("art1"))
            .andExpect(jsonPath("$.artifactVersion").value(4))
            .andExpect(jsonPath("$.processDefinitionVersion").value(3));

        verify(bindingRepository).save(any(ElementArtifactBindingEntity.class));
    }

    @Test
    void createElementBinding_upsert_replacesExisting() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(formRepository.findTopByFormKeyOrderByVersionDesc("art1"))
            .thenReturn(Optional.of(form("art1", 4)));
        ElementArtifactBindingEntity existing = new ElementArtifactBindingEntity();
        existing.setId(UUID.randomUUID());
        when(bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), "start1"))
            .thenReturn(Optional.of(existing));

        CreateElementBindingDTO dto = new CreateElementBindingDTO();
        dto.setElementId("start1");
        dto.setArtifactKey("art1");

        mockMvc.perform(post("/process-definitions/ord/element-bindings")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isOk());

        verify(bindingRepository).delete(any(ElementArtifactBindingEntity.class));
        verify(bindingRepository).save(any(ElementArtifactBindingEntity.class));
    }

    @Test
    void createElementBinding_deny_userForbiddenAndNothingSaved() throws Exception {
        CreateElementBindingDTO dto = new CreateElementBindingDTO();
        dto.setElementId("start1");
        dto.setArtifactKey("art1");

        mockMvc.perform(post("/process-definitions/ord/element-bindings")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isForbidden());

        verify(bindingRepository, never()).save(any(ElementArtifactBindingEntity.class));
    }

    @Test
    void createElementBinding_blankElementId_400() throws Exception {
        CreateElementBindingDTO dto = new CreateElementBindingDTO();
        dto.setElementId("  ");
        dto.setArtifactKey("art1");

        mockMvc.perform(post("/process-definitions/ord/element-bindings")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isBadRequest());

        verify(bindingRepository, never()).save(any(ElementArtifactBindingEntity.class));
    }

    @Test
    void createElementBinding_blankArtifactKey_400() throws Exception {
        CreateElementBindingDTO dto = new CreateElementBindingDTO();
        dto.setElementId("start1");
        dto.setArtifactKey(null);

        mockMvc.perform(post("/process-definitions/ord/element-bindings")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isBadRequest());

        verify(bindingRepository, never()).save(any(ElementArtifactBindingEntity.class));
    }

    @Test
    void createElementBinding_processNotFound() throws Exception {
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());
        CreateElementBindingDTO dto = new CreateElementBindingDTO();
        dto.setElementId("start1");
        dto.setArtifactKey("art1");

        mockMvc.perform(post("/process-definitions/missing/element-bindings")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isNotFound());
    }

    @Test
    void createElementBinding_artifactNotFound() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(formRepository.findTopByFormKeyOrderByVersionDesc("nope")).thenReturn(Optional.empty());
        CreateElementBindingDTO dto = new CreateElementBindingDTO();
        dto.setElementId("start1");
        dto.setArtifactKey("nope");

        mockMvc.perform(post("/process-definitions/ord/element-bindings")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isNotFound());
    }

    // ==================== listElementBindings ====================

    @Test
    void listElementBindings_happy() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        ElementArtifactBindingEntity b1 = new ElementArtifactBindingEntity();
        b1.setId(UUID.randomUUID());
        b1.setElementId("start1");
        b1.setArtifactKey("a1");
        b1.setArtifactVersion(1);
        b1.setProcessDefinitionId(pd.getId());
        b1.setProcessDefinitionVersion(3);
        ElementArtifactBindingEntity b2 = new ElementArtifactBindingEntity();
        b2.setId(UUID.randomUUID());
        b2.setElementId("task1");
        b2.setArtifactKey("a2");
        b2.setArtifactVersion(2);
        b2.setProcessDefinitionId(pd.getId());
        b2.setProcessDefinitionVersion(3);
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of(b1, b2));

        mockMvc.perform(get("/process-definitions/ord/element-bindings")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[?(@.elementId == 'task1')].artifactKey", hasItem("a2")));
    }

    @Test
    void listElementBindings_empty() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of());

        mockMvc.perform(get("/process-definitions/ord/element-bindings")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listElementBindings_processNotFound() throws Exception {
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/process-definitions/missing/element-bindings")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isNotFound());
    }

    // ==================== deleteElementBinding ====================

    @Test
    void deleteElementBinding_happy() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));

        mockMvc.perform(delete("/process-definitions/ord/element-bindings/start1")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk());

        verify(bindingRepository).deleteByProcessDefinitionIdAndElementId(eq(pd.getId()), eq("start1"));
    }

    @Test
    void deleteElementBinding_deny_userForbiddenAndNothingDeleted() throws Exception {
        mockMvc.perform(delete("/process-definitions/ord/element-bindings/start1")
                .header("Authorization", "Bearer " + USER_TOKEN))
            .andExpect(status().isForbidden());

        verify(bindingRepository, never()).deleteByProcessDefinitionIdAndElementId(any(), any());
    }

    @Test
    void deleteElementBinding_unauthenticated_401() throws Exception {
        mockMvc.perform(delete("/process-definitions/ord/element-bindings/start1"))
            .andExpect(status().isUnauthorized());

        verify(bindingRepository, never()).deleteByProcessDefinitionIdAndElementId(any(), any());
    }

    @Test
    void deleteElementBinding_processNotFound() throws Exception {
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/process-definitions/missing/element-bindings/start1")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isNotFound());
    }

    // ==================== getSchemaMap ====================

    private BpmnProcessDefinitionModel schemaMapModel() {
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        BpmnElementModel start = new BpmnElementModel();
        start.setId("start1");
        start.setName("Start");
        start.setType(BpmnElementType.START_EVENT);
        model.addElement(start);
        BpmnElementModel task = new BpmnElementModel();
        task.setId("task1");
        task.setName("Approve");
        task.setType(BpmnElementType.USER_TASK);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        UserTaskExtensionModel ute = new UserTaskExtensionModel();
        ute.setFormKey("taskForm");
        ext.setUserTaskExtension(ute);
        task.setExtensions(ext);
        model.addElement(task);
        return model;
    }

    @Test
    void getSchemaMap_happy() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(schemaMapModel());
        ElementArtifactBindingEntity b = new ElementArtifactBindingEntity();
        b.setProcessDefinitionId(pd.getId());
        b.setElementId("start1");
        b.setArtifactKey("startArtifact");
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of(b));
        when(bindingRepository.findByArtifactKeyIn(any())).thenReturn(List.of());
        when(processDefinitionRepository.findAll()).thenReturn(List.of());
        FormEntity startForm = form("startArtifact", 7);
        when(formRepository.findByFormKeyIn(any()))
            .thenReturn(List.of(startForm, form("taskForm", 2)));

        mockMvc.perform(get("/process-definitions/ord/schema-map")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processDefinitionKey").value("ord"))
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.elements", hasSize(2)))
            .andExpect(jsonPath("$.elements[?(@.elementId == 'start1')].artifactKey", hasItem("startArtifact")))
            .andExpect(jsonPath("$.elements[?(@.elementId == 'start1')].artifactVersion", hasItem(7)))
            .andExpect(jsonPath("$.elements[?(@.elementId == 'start1')].kind", hasItem("FORM_JS")))
            .andExpect(jsonPath("$.elements[?(@.elementId == 'start1')].shared", hasItem(false)))
            .andExpect(jsonPath("$.elements[?(@.elementId == 'task1')].artifactKey", hasItem("taskForm")))
            .andExpect(jsonPath("$.elements[?(@.elementId == 'task1')].hasExternalReference", hasItem(false)));
    }

    @Test
    void getSchemaMap_emptyModel() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId())))
            .thenReturn(new BpmnProcessDefinitionModel());
        when(bindingRepository.findByProcessDefinitionId(pd.getId())).thenReturn(List.of());
        when(bindingRepository.findByArtifactKeyIn(any())).thenReturn(List.of());
        when(processDefinitionRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/process-definitions/ord/schema-map")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.elements", hasSize(0)));
    }

    @Test
    void getSchemaMap_processNotFound() throws Exception {
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/process-definitions/missing/schema-map")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isNotFound());
    }

    // ==================== saveElementSchema ====================

    private static SaveElementSchemaDTO schemaDto(String kind, String schema) {
        SaveElementSchemaDTO dto = new SaveElementSchemaDTO();
        dto.setKind(kind);
        dto.setSchema(schema);
        return dto;
    }

    @Test
    void saveElementSchema_happy_startEventCreatesVersionedArtifactAndBinding() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        BpmnElementModel start = new BpmnElementModel();
        start.setId("start1");
        start.setName("Start");
        start.setType(BpmnElementType.START_EVENT);
        model.addElement(start);
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(model);
        when(formRepository.findMaxVersionByFormKey("ord:start1")).thenReturn(1);
        when(bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), "start1"))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/process-definitions/ord/elements/start1/schema")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto("FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.elementId").value("start1"))
            .andExpect(jsonPath("$.artifactKey").value("ord:start1"))
            .andExpect(jsonPath("$.kind").value("FORM_JS"))
            .andExpect(jsonPath("$.artifactVersion").value(2));

        verify(formRepository).save(any(FormEntity.class));
        verify(bindingRepository).save(any(ElementArtifactBindingEntity.class));
    }

    @Test
    void saveElementSchema_happy_userTaskUsesExternalReferenceWithoutBinding() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        BpmnElementModel task = new BpmnElementModel();
        task.setId("task1");
        task.setName("Approve");
        task.setType(BpmnElementType.USER_TASK);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        UserTaskExtensionModel ute = new UserTaskExtensionModel();
        ute.setExternalReference("extForm");
        ext.setUserTaskExtension(ute);
        task.setExtensions(ext);
        model.addElement(task);
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(model);
        when(formRepository.findMaxVersionByFormKey("extForm")).thenReturn(0);

        mockMvc.perform(post("/process-definitions/ord/elements/task1/schema")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto("FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.artifactKey").value("extForm"))
            .andExpect(jsonPath("$.artifactVersion").value(1));

        verify(formRepository).save(any(FormEntity.class));
        // User-task path never touches bindings — pins the branch
        verify(bindingRepository, never()).save(any(ElementArtifactBindingEntity.class));
    }

    @Test
    void saveElementSchema_deny_userForbiddenAndNothingSaved() throws Exception {
        mockMvc.perform(post("/process-definitions/ord/elements/start1/schema")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto("FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isForbidden());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_blankKind_400() throws Exception {
        mockMvc.perform(post("/process-definitions/ord/elements/start1/schema")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto(null, "{\"components\":[]}"))))
            .andExpect(status().isBadRequest());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_invalidKind_400() throws Exception {
        mockMvc.perform(post("/process-definitions/ord/elements/start1/schema")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto("WRONG", "{\"components\":[]}"))))
            .andExpect(status().isBadRequest());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_blankSchema_400() throws Exception {
        mockMvc.perform(post("/process-definitions/ord/elements/start1/schema")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto("FORM_JS", " "))))
            .andExpect(status().isBadRequest());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_invalidJson_400() throws Exception {
        mockMvc.perform(post("/process-definitions/ord/elements/start1/schema")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto("FORM_JS", "{oops"))))
            .andExpect(status().isBadRequest());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_invalidVariableSchema_400() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));

        mockMvc.perform(post("/process-definitions/ord/elements/start1/schema")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto("VARIABLE_SCHEMA", "{\"$schema\": \"https://json-schema.org/draft/9999-99/schema\", \"type\": \"object\"}"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("Invalid JSON Schema")));

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_processNotFound() throws Exception {
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());

        mockMvc.perform(post("/process-definitions/missing/elements/start1/schema")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto("FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void saveElementSchema_elementNotFound() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId())))
            .thenReturn(new BpmnProcessDefinitionModel());

        mockMvc.perform(post("/process-definitions/ord/elements/nope/schema")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto("FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isNotFound());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    @Test
    void saveElementSchema_userTaskWithoutExternalReference_400() throws Exception {
        ProcessDefinitionEntity pd = pd("ord", 3);
        when(processDefinitionRepository.findMaxByKey("ord")).thenReturn(Optional.of(3));
        when(processDefinitionRepository.findByKeyAndVersion(eq("ord"), any())).thenReturn(Optional.of(pd));
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        BpmnElementModel task = new BpmnElementModel();
        task.setId("task1");
        task.setName("Approve");
        task.setType(BpmnElementType.USER_TASK);
        model.addElement(task);
        when(bpmnService.getProcessDefinitionModelById(eq(pd.getId()))).thenReturn(model);

        mockMvc.perform(post("/process-definitions/ord/elements/task1/schema")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(schemaDto("FORM_JS", "{\"components\":[]}"))))
            .andExpect(status().isBadRequest());

        verify(formRepository, never()).save(any(FormEntity.class));
    }

    // ==================== @Transactional boundary pin ====================

    @Test
    void transactionalBoundary_writeMethodsAreTransactional() throws Exception {
        // Fact about current prod code: write endpoints own their tx boundary on the
        // domain impl, never on the FormResource delegates.
        // WO-DEBT-4c MOVED the ElementBinding boundary, WO-DEBT-4e MOVED the Forms and
        // SchemaMap boundaries (none dropped): deployForm/saveElementSchema are @Transactional
        // on FormOperationsImpl/SchemaMapOperationsImpl now, FormResource delegates carry none.
        // (Pre-move REDs are kept in governance/reports/WO-DEBT-4c.md and WO-DEBT-4e.md
        // as move-proofs; the pin's intent — boundary never silently lost — is preserved.)
        assertThat(FormResource.class
            .getMethod("deployForm", com.zorrodev.bpm.contract.dto.DeployFormDTO.class)
            .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(FormResource.class
            .getMethod("createElementBinding", String.class, CreateElementBindingDTO.class)
            .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(FormResource.class
            .getMethod("deleteElementBinding", String.class, String.class)
            .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(FormResource.class
            .getMethod("saveElementSchema", String.class, String.class, SaveElementSchemaDTO.class)
            .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(ElementBindingOperationsImpl.class
            .getMethod("createElementBinding", String.class, CreateElementBindingDTO.class)
            .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(ElementBindingOperationsImpl.class
            .getMethod("deleteElementBinding", String.class, String.class)
            .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(ElementBindingOperationsImpl.class
            .getMethod("listElementBindings", String.class)
            .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(FormOperationsImpl.class
            .getMethod("deployForm", com.zorrodev.bpm.contract.dto.DeployFormDTO.class)
            .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(FormOperationsImpl.class
            .getMethod("listForms")
            .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(FormOperationsImpl.class
            .getMethod("getForm", String.class)
            .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(SchemaMapOperationsImpl.class
            .getMethod("saveElementSchema", String.class, String.class, SaveElementSchemaDTO.class)
            .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(SchemaMapOperationsImpl.class
            .getMethod("getSchemaMap", String.class)
            .isAnnotationPresent(Transactional.class)).isFalse();
    }
}
