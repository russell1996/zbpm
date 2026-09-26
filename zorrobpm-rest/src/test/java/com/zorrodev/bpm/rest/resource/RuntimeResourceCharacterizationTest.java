package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.*;
import com.zorrodev.bpm.engine.repository.*;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.security.UiUserLookupService;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormArtifactService;
import com.zorrodev.bpm.engine.service.FormValidator;
import com.zorrodev.bpm.engine.service.RuntimeService;
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

import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RuntimeResource characterization — V11 MockMvc + real JwtAuthFilter.
 * 9 endpoints × 3 (happy/deny/404) = 27 + formValidation + onBehalfOf×2 = 30.
 *
 * Paths: NO /runtime prefix (RuntimeResource has no @RequestMapping).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeResourceCharacterizationTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /* --- mock all downstream services/repos --- */
    @MockitoBean private RuntimeService runtimeService;
    @MockitoBean private com.zorrodev.bpm.engine.service.ActivityService activityService;
    @MockitoBean private DBService dbService;
    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private AuthorizationService authorizationService;
    @MockitoBean private FormArtifactService formArtifactService;
    // WO-API-1 (F16): сабмит читает UserTaskFormData (formId/binding/formKey) +
    // versionTag элемента + deploymentId — стабы ниже (реальный прод-путь в
    // UserTaskRuntimeOperationsImpl.completeUserTask; strict-stub требует
    // стабить ТОЛЬКО используемый путь — см. formValidation-тест).
    @MockitoBean private com.zorrodev.bpm.engine.service.TaskFormDataService taskFormDataService;
    @MockitoBean private UserTaskRepository userTaskRepository;
    @MockitoBean private ServiceTaskRepository serviceTaskRepository;
    @MockitoBean private ProcessInstanceRepository processInstanceRepository;
    @MockitoBean private ProcessDefinitionRepository processDefinitionRepository;
    @MockitoBean private ProcessRepository processRepository;
    @MockitoBean private IncidentRepository incidentRepository;
    @MockitoBean private ActivityRepository activityRepository;
    @MockitoBean private UserGroupRepository userGroupRepository;
    @MockitoBean private ProcessMemberRepository processMemberRepository;
    @MockitoBean private UiUserRepository uiUserRepository;

    /* --- JwtAuthFilter deps: mock to control auth without DB --- */
    @MockitoBean private TokenService tokenService;
    @MockitoBean private ApiKeyRepository apiKeyRepository;
    @MockitoBean private ApiKeyGrantRepository apiKeyGrantRepository;
    @MockitoBean private UiUserLookupService userLookupService;

    private static final String ADMIN_TOKEN = "test-admin-jwt";
    private static final String USER_TOKEN = "test-user-jwt";
    private static final String SERVICE_KEY = "zbpm_sk_test123";

    @BeforeEach
    void setupAuth() {
        // Stable ids so the JwtAuthFilter securityState stub (WO-SEC-63) can match per-token.
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        // JWT → UserPrincipal (filter calls tokenService.verify())
        lenient().when(tokenService.verify(ADMIN_TOKEN)).thenReturn(
            new TokenService.Claims(adminId, "admin", "SUPER_ADMIN", 0, Long.MAX_VALUE));
        lenient().when(tokenService.verify(USER_TOKEN)).thenReturn(
            new TokenService.Claims(userId, "user", "USER", 0, Long.MAX_VALUE));

        // WO-SEC-63: filter now checks the live user-state (active/role/tokenVersion) on every
        // request; tokenVersion 0 matches the claims above, roles must match exactly.
        lenient().when(userLookupService.securityState(eq(adminId))).thenReturn(Optional.of(
            new UiUserLookupService.UserSecurityState(adminId, "admin", "SUPER_ADMIN", true, 0, false)));
        lenient().when(userLookupService.securityState(eq(userId))).thenReturn(Optional.of(
            new UiUserLookupService.UserSecurityState(userId, "user", "USER", true, 0, false)));

        // API key → ServicePrincipal (filter calls apiKeyRepository.findByKeyHash())
        ApiKeyEntity apiKey = new ApiKeyEntity();
        apiKey.setId(UUID.randomUUID());
        apiKey.setOwnerUserId(UUID.randomUUID());
        apiKey.setPrefix("zbpm_sk_");
        lenient().when(apiKeyRepository.findByKeyHash(any())).thenReturn(Optional.of(apiKey));
        lenient().when(apiKeyGrantRepository.findByApiKeyId(any())).thenReturn(List.of());
        lenient().when(userLookupService.isActive(any())).thenReturn(true);
        lenient().when(userLookupService.isForcePasswordChange(any())).thenReturn(false);
        lenient().when(authorizationService.effectiveGrants(any(), any())).thenReturn(Map.of());
        // lenient runtimeService stubs for RED mutations to reach 200 when auth/validation bypassed
        lenient().when(runtimeService.completeUserTask(any(), any())).thenReturn(new com.zorrodev.bpm.engine.dto.IdDTO(UUID.randomUUID()));
        lenient().when(runtimeService.completeServiceTask(any(), any())).thenReturn(new com.zorrodev.bpm.engine.dto.IdDTO(UUID.randomUUID()));
        lenient().when(runtimeService.failServiceTask(any(), any(), any())).thenReturn(new com.zorrodev.bpm.engine.dto.IdDTO(UUID.randomUUID()));
        lenient().when(runtimeService.resolveIncident(any(), any())).thenReturn(new com.zorrodev.bpm.engine.dto.IdDTO(UUID.randomUUID()));
    }

    // ==================== startProcessInstance ====================

    @Test
    void startProcessInstance_happy() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("test-key");
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID()); pd.setKey("test-key"); pd.setVersion(1);
        when(processDefinitionRepository.findByKeyAndVersion(eq("test-key"), any())).thenReturn(Optional.of(pd));
        when(processDefinitionRepository.findById(any())).thenReturn(Optional.of(pd));
        when(processDefinitionRepository.findMaxByKey("test-key")).thenReturn(Optional.of(1));
        when(authorizationService.canOperate(any(), eq("test-key"), eq(AuthorizationService.Action.START))).thenReturn(true);
        UUID resultId = UUID.randomUUID();
        // WO-API-1 (API-7): фасад зовёт startProcessInstance(dto, claimed) —
        // стаб старой одноаргументной сигнатуры мёртв (strict-stub роняет).
        // Сигнатуры (UUID,DTO) vs (DTO,String): any() неоднозначен — типизируем.
        StartProcessInstanceDTO anyDto = any();
        when(runtimeService.startProcessInstance(anyDto, isNull())).thenReturn(new com.zorrodev.bpm.engine.dto.IdDTO(resultId));
        when(processInstanceRepository.findById(resultId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        verify(auditLogService).record(any(), eq("START"), eq("test-key"), eq(resultId.toString()), any());
    }

    @Test
    void startProcessInstance_deny() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("test-key");
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID()); pd.setKey("test-key");
        when(processDefinitionRepository.findById(any())).thenReturn(Optional.of(pd));
        when(authorizationService.canOperate(any(), eq("test-key"), eq(AuthorizationService.Action.START))).thenReturn(false);

        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());

        verify(runtimeService, never()).startProcessInstance(any());
    }

    @Test
    void startProcessInstance_notFound() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("missing");
        when(processDefinitionRepository.findByKeyAndVersion(eq("missing"), any())).thenReturn(Optional.empty());
        when(processDefinitionRepository.findMaxByKey("missing")).thenReturn(Optional.empty());
        when(authorizationService.canOperate(any(), eq("missing"), eq(AuthorizationService.Action.START))).thenReturn(true);

        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    // ==================== completeServiceTask ====================

    @Test
    void completeServiceTask_happy() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        ServiceTaskEntity st = new ServiceTaskEntity(); st.setId(id); st.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(piId); pi.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity(); pd.setId(pdId); pd.setKey("svc-key");
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(st));
        when(serviceTaskRepository.findDefinitionKeyById(id)).thenReturn(Optional.of("svc-key"));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        when(authorizationService.canOperate(any(), eq("svc-key"), eq(AuthorizationService.Action.COMPLETE_SERVICE_TASK))).thenReturn(true);
        UUID resultId = UUID.randomUUID();
        when(runtimeService.completeServiceTask(eq(id), any())).thenReturn(new com.zorrodev.bpm.engine.dto.IdDTO(resultId));

        mockMvc.perform(post("/service-tasks/" + id + "/complete")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyCompleteTaskDTO())))
                .andExpect(status().isOk());

        verify(auditLogService).record(any(), eq("COMPLETE_SERVICE_TASK"), eq("svc-key"), eq(id.toString()));
    }

    @Test
    void completeServiceTask_deny() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        ServiceTaskEntity st = new ServiceTaskEntity(); st.setId(id); st.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(piId); pi.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity(); pd.setId(pdId); pd.setKey("svc-key");
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(st));
        when(serviceTaskRepository.findDefinitionKeyById(id)).thenReturn(Optional.of("svc-key"));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        when(authorizationService.canOperate(any(), eq("svc-key"), eq(AuthorizationService.Action.COMPLETE_SERVICE_TASK))).thenReturn(false);

        mockMvc.perform(post("/service-tasks/" + id + "/complete")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyCompleteTaskDTO())))
                .andExpect(status().isForbidden());

        verify(runtimeService, never()).completeServiceTask(any(), any());
    }

    @Test
    void completeServiceTask_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(post("/service-tasks/" + id + "/complete")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyCompleteTaskDTO())))
                .andExpect(status().isNotFound());
    }

    // ==================== failServiceTask ====================

    @Test
    void failServiceTask_happy() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        ServiceTaskEntity st = new ServiceTaskEntity(); st.setId(id); st.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(piId); pi.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity(); pd.setId(pdId); pd.setKey("svc-key");
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(st));
        when(serviceTaskRepository.findDefinitionKeyById(id)).thenReturn(Optional.of("svc-key"));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        when(authorizationService.canOperate(any(), eq("svc-key"), eq(AuthorizationService.Action.COMPLETE_SERVICE_TASK))).thenReturn(true);
        UUID resultId = UUID.randomUUID();
        when(runtimeService.failServiceTask(eq(id), any(), any())).thenReturn(new com.zorrodev.bpm.engine.dto.IdDTO(resultId));

        FailServiceTaskDTO dto = new FailServiceTaskDTO(); dto.setMessage("err"); dto.setRetries(1);
        mockMvc.perform(post("/service-tasks/" + id + "/fail")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        verify(auditLogService).record(any(), eq("FAIL_SERVICE_TASK"), eq("svc-key"), eq(id.toString()));
    }

    @Test
    void failServiceTask_deny() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
ServiceTaskEntity st = new ServiceTaskEntity(); st.setId(id); st.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(piId); pi.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity(); pd.setId(pdId); pd.setKey("svc-key");
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(st));
        when(serviceTaskRepository.findDefinitionKeyById(id)).thenReturn(Optional.of("svc-key"));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        when(authorizationService.canOperate(any(), eq("svc-key"), eq(AuthorizationService.Action.COMPLETE_SERVICE_TASK))).thenReturn(false);

        FailServiceTaskDTO dto = new FailServiceTaskDTO(); dto.setMessage("err"); dto.setRetries(1);
        mockMvc.perform(post("/service-tasks/" + id + "/fail")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());

        verify(runtimeService, never()).failServiceTask(any(), any(), any());
    }

    @Test
    void failServiceTask_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.empty());

        FailServiceTaskDTO dto = new FailServiceTaskDTO(); dto.setMessage("err"); dto.setRetries(1);
        mockMvc.perform(post("/service-tasks/" + id + "/fail")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    // ==================== completeUserTask ====================

    @Test
    void completeUserTask_happy() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setCandidateGroups("g1"); task.setAssignee(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canCompleteUserTask(any(), eq(piId), eq("g1"))).thenReturn(true);
        UUID resultId = UUID.randomUUID();
        when(runtimeService.completeUserTask(eq(id), any())).thenReturn(new com.zorrodev.bpm.engine.dto.IdDTO(resultId));

        mockMvc.perform(post("/user-tasks/" + id + "/complete")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyCompleteTaskDTO())))
                .andExpect(status().isOk());

        verify(auditLogService).record(any(), eq("COMPLETE_USER_TASK"), any(), eq(id.toString()), any());
    }

    @Test
    void completeUserTask_deny() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setCandidateGroups("g1");
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canCompleteUserTask(any(), eq(piId), eq("g1"))).thenReturn(false);

        mockMvc.perform(post("/user-tasks/" + id + "/complete")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyCompleteTaskDTO())))
                .andExpect(status().isForbidden());

        verify(runtimeService, never()).completeUserTask(any(), any());
    }

    @Test
    void completeUserTask_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(userTaskRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(post("/user-tasks/" + id + "/complete")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyCompleteTaskDTO())))
                .andExpect(status().isNotFound());
    }

    @Test
    void completeUserTask_formValidation_400() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setFormKey("form1"); task.setCandidateGroups("g1");
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canCompleteUserTask(any(), eq(piId), eq("g1"))).thenReturn(true);
        // WO-API-1 (F16): сабмит идёт через validateTaskSubmit — стаб мёртвого
        // validateFormIfApplicable заменён (strict-stub: мёртвый стаб роняет тест).
        // Скаляры задачи — через реальный TaskFormDataService-бин? Нет: здесь мок
        // репозитория userTaskRepository отдаёт task с formKey=form1 напрямую.
        when(taskFormDataService.loadUserTaskFormData(id)).thenReturn(
            new com.zorrodev.bpm.engine.service.TaskFormDataService.UserTaskFormData(
                id, null, null, "form1", piId, "elem1", UUID.randomUUID()));
        when(taskFormDataService.findDeploymentId(any())).thenReturn(null);
        when(formArtifactService.validateTaskSubmit(isNull(), isNull(), eq("form1"), isNull(), isNull(), any()))
            .thenReturn(List.of(new FormValidator.ValidationError("field1", "required")));

        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(List.of(new ProcessVariable()));
        mockMvc.perform(post("/user-tasks/" + id + "/complete")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeUserTask_onBehalfOf_mismatch_403() throws Exception {
        // UserPrincipal + X-On-Behalf-Of → checkedOnBehalfOf() rejects (not ServicePrincipal)
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setCandidateGroups("g1");
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canCompleteUserTask(any(), eq(piId), eq("g1"))).thenReturn(true);

        mockMvc.perform(post("/user-tasks/" + id + "/complete")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .header("X-On-Behalf-Of", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyCompleteTaskDTO())))
                .andExpect(status().isForbidden());

        verify(runtimeService, never()).completeUserTask(any(), any());
    }

    @Test
    void completeUserTask_onBehalfOf_matching_200() throws Exception {
        // ServicePrincipal (API key) + X-On-Behalf-Of where named user IS the assignee.
        // WO-SEC-64 HOLD: start/task-пути требуют существующего OBO-принципала —
        // мокаем existsByUsername (реальный RuntimeSupportService в контексте).
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setCandidateGroups("g1"); task.setAssignee("alice");
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canCompleteUserTask(any(), eq(piId), eq("g1"))).thenReturn(true);
        UiUserEntity named = new UiUserEntity(); named.setId(UUID.randomUUID()); named.setUsername("alice");
        when(uiUserRepository.findByUsername("alice")).thenReturn(Optional.of(named));
        // WO-SEC-64 HOLD: existence-гейт требует существующего OBO-принципала.
        when(uiUserRepository.existsByUsername("alice")).thenReturn(true);
        UUID resultId = UUID.randomUUID();
        when(runtimeService.completeUserTask(eq(id), any())).thenReturn(new com.zorrodev.bpm.engine.dto.IdDTO(resultId));

        mockMvc.perform(post("/user-tasks/" + id + "/complete")
                .header("Authorization", "Bearer " + SERVICE_KEY)
                .header("X-On-Behalf-Of", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyCompleteTaskDTO())))
                .andExpect(status().isOk());
    }

    // ==================== claimUserTask ====================

    @Test
    void claimUserTask_happy() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setCandidateGroups("g1");
        task.setCompletedAt(null); task.setAssignee(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canClaimUserTask(any(), eq(piId), eq("g1"))).thenReturn(true);
        doNothing().when(activityService).claimUserTask(eq(id), any());

        mockMvc.perform(post("/user-tasks/" + id + "/claim")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk());

        verify(auditLogService).record(any(), eq("CLAIM_USER_TASK"), any(), eq(id.toString()), any());
    }

    @Test
    void claimUserTask_deny() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setCandidateGroups("g1");
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canClaimUserTask(any(), eq(piId), eq("g1"))).thenReturn(false);

        mockMvc.perform(post("/user-tasks/" + id + "/claim")
                .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden());

        verify(dbService, never()).claimUserTask(any(), any());
    }

    @Test
    void claimUserTask_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(userTaskRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(post("/user-tasks/" + id + "/claim")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound());
    }

    // ==================== unclaimUserTask ====================

    @Test
    void unclaimUserTask_happy() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setCandidateGroups("g1"); task.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canClaimUserTask(any(), eq(piId), eq("g1"))).thenReturn(true);
        doNothing().when(dbService).unclaimUserTask(id);

        mockMvc.perform(post("/user-tasks/" + id + "/unclaim")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk());

        verify(auditLogService).record(any(), eq("UNCLAIM_USER_TASK"), any(), eq(id.toString()));
    }

    @Test
    void unclaimUserTask_deny() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setCandidateGroups("g1");
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canClaimUserTask(any(), eq(piId), eq("g1"))).thenReturn(false);

        mockMvc.perform(post("/user-tasks/" + id + "/unclaim")
                .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden());

        verify(dbService, never()).unclaimUserTask(any());
    }

    @Test
    void unclaimUserTask_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(userTaskRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(post("/user-tasks/" + id + "/unclaim")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound());
    }

    // ==================== assignUserTask ====================

    @Test
    void assignUserTask_happy() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canReassignUserTask(any(), eq(piId))).thenReturn(true);
        doNothing().when(activityService).assignUserTask(eq(id), any());

        AssignUserTaskDTO dto = new AssignUserTaskDTO(); dto.setAssignee("bob");
        mockMvc.perform(post("/user-tasks/" + id + "/assign")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        verify(auditLogService).record(any(), eq("ASSIGN_USER_TASK"), any(), eq(id.toString()), eq("bob"));
    }

    @Test
    void assignUserTask_deny() throws Exception {
        UUID id = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UserTaskEntity task = new UserTaskEntity();
        task.setId(id); task.setProcessInstanceId(piId); task.setCompletedAt(null);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));
        when(authorizationService.canReassignUserTask(any(), eq(piId))).thenReturn(false);

        AssignUserTaskDTO dto = new AssignUserTaskDTO(); dto.setAssignee("bob");
        mockMvc.perform(post("/user-tasks/" + id + "/assign")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());

        verify(dbService, never()).assignUserTask(any(), any());
    }

    @Test
    void assignUserTask_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(userTaskRepository.findById(id)).thenReturn(Optional.empty());

        AssignUserTaskDTO dto = new AssignUserTaskDTO(); dto.setAssignee("bob");
        mockMvc.perform(post("/user-tasks/" + id + "/assign")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    // ==================== resolveIncident ====================

    @Test
    void resolveIncident_happy() throws Exception {
        UUID id = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        IncidentEntity incident = new IncidentEntity(); incident.setId(id); incident.setActivityId(actId);
        ActivityEntity act = new ActivityEntity(); act.setId(actId); act.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(piId); pi.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity(); pd.setId(pdId); pd.setKey("inc-key");
        when(incidentRepository.findById(id)).thenReturn(Optional.of(incident));
        when(incidentRepository.findDefinitionKeyById(id)).thenReturn(Optional.of("inc-key"));
        when(activityRepository.findById(actId)).thenReturn(Optional.of(act));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        when(authorizationService.canOperate(any(), eq("inc-key"), eq(AuthorizationService.Action.COMPLETE_SERVICE_TASK))).thenReturn(true);
        UUID resultId = UUID.randomUUID();
        when(runtimeService.resolveIncident(eq(id), any())).thenReturn(new com.zorrodev.bpm.engine.dto.IdDTO(resultId));

        mockMvc.perform(post("/incidents/" + id + "/resolve")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyResolveIncidentDTO())))
                .andExpect(status().isOk());

        verify(auditLogService).record(any(), eq("RESOLVE_INCIDENT"), eq("inc-key"), eq(id.toString()));
    }

    @Test
    void resolveIncident_deny() throws Exception {
        UUID id = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        IncidentEntity incident = new IncidentEntity(); incident.setId(id); incident.setActivityId(actId);
        ActivityEntity act = new ActivityEntity(); act.setId(actId); act.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(piId); pi.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity(); pd.setId(pdId); pd.setKey("inc-key");
        when(incidentRepository.findById(id)).thenReturn(Optional.of(incident));
        when(incidentRepository.findDefinitionKeyById(id)).thenReturn(Optional.of("inc-key"));
        when(activityRepository.findById(actId)).thenReturn(Optional.of(act));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        when(authorizationService.canOperate(any(), eq("inc-key"), eq(AuthorizationService.Action.COMPLETE_SERVICE_TASK))).thenReturn(false);

        mockMvc.perform(post("/incidents/" + id + "/resolve")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyResolveIncidentDTO())))
                .andExpect(status().isForbidden());

        verify(runtimeService, never()).resolveIncident(any(), any());
    }

    @Test
    void resolveIncident_notFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(incidentRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(post("/incidents/" + id + "/resolve")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyResolveIncidentDTO())))
                .andExpect(status().isNotFound());
    }

    // ==================== cancelProcessInstance ====================

    @Test
    void cancelProcessInstance_happy() throws Exception {
        UUID id = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        ProcessInstance pi = new ProcessInstance(); pi.setId(id); pi.setCompletedAt(null); pi.setCancelled(false);
        when(dbService.getProcessInstance(id)).thenReturn(pi);
        ProcessInstanceEntity piEntity = new ProcessInstanceEntity(); piEntity.setId(id); piEntity.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity(); pd.setId(pdId); pd.setKey("cancel-key");
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(piEntity));
        when(processInstanceRepository.findDefinitionKeyById(id)).thenReturn(Optional.of("cancel-key"));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        when(authorizationService.canOperate(any(), eq("cancel-key"), eq(AuthorizationService.Action.DELETE_PROCESS))).thenReturn(true);

        mockMvc.perform(post("/process-instances/" + id + "/cancel")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isAccepted());

        verify(auditLogService).record(any(), eq("CANCEL"), eq("cancel-key"), eq(id.toString()));
    }

    @Test
    void cancelProcessInstance_deny() throws Exception {
        UUID id = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        ProcessInstance pi = new ProcessInstance(); pi.setId(id); pi.setCompletedAt(null); pi.setCancelled(false);
        when(dbService.getProcessInstance(id)).thenReturn(pi);
        ProcessInstanceEntity piEntity = new ProcessInstanceEntity(); piEntity.setId(id); piEntity.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity(); pd.setId(pdId); pd.setKey("cancel-key");
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(piEntity));
        when(processInstanceRepository.findDefinitionKeyById(id)).thenReturn(Optional.of("cancel-key"));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        when(authorizationService.canOperate(any(), eq("cancel-key"), eq(AuthorizationService.Action.DELETE_PROCESS))).thenReturn(false);

        mockMvc.perform(post("/process-instances/" + id + "/cancel")
                .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden());

        verify(dbService, never()).cancelProcessInstance(any());
    }

    @Test
    void cancelProcessInstance_alreadyCompleted_409() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance pi = new ProcessInstance(); pi.setId(id); pi.setCompletedAt(Instant.now()); pi.setCancelled(false);
        when(dbService.getProcessInstance(id)).thenReturn(pi);

        mockMvc.perform(post("/process-instances/" + id + "/cancel")
                .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isConflict());
    }

    // WO-API-1: пустой DTO обязан нести variables=[] (null отклоняется @NotNull).
    private static com.zorrodev.bpm.contract.dto.CompleteTaskDTO emptyCompleteTaskDTO() {
        com.zorrodev.bpm.contract.dto.CompleteTaskDTO dto = new com.zorrodev.bpm.contract.dto.CompleteTaskDTO();
        dto.setVariables(java.util.List.of());
        return dto;
    }

    private static com.zorrodev.bpm.contract.dto.ResolveIncidentDTO emptyResolveIncidentDTO() {
        com.zorrodev.bpm.contract.dto.ResolveIncidentDTO dto = new com.zorrodev.bpm.contract.dto.ResolveIncidentDTO();
        dto.setVariables(java.util.List.of());
        return dto;
    }

    private static com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO emptyEvaluateDecisionDTO() {
        com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO dto = new com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO();
        dto.setVariables(java.util.List.of());
        return dto;
    }
}
