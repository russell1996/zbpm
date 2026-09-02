package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.*;
import com.zorrodev.bpm.engine.repository.*;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuntimeOperationSupportTest {

    @Mock private HttpServletRequest request;
    @Mock private AuthorizationService authorizationService;
    @Mock private UiUserRepository uiUserRepository;
    @Mock private UserGroupRepository userGroupRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ProcessDefinitionRepository processDefinitionRepository;
    @Mock private ProcessRepository processRepository;
    @Mock private ProcessMemberRepository processMemberRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private ActivityRepository activityRepository;

    @InjectMocks private RuntimeOperationSupport support;

    // getPrincipal
    @Test
    void getPrincipal_returnsPrincipalWhenPresent() {
        Principal p = new Principal.UserPrincipal(UUID.randomUUID(), "user1", "USER");
        when(request.getAttribute("principal")).thenReturn(p);
        assertThat(support.getPrincipal()).isEqualTo(p);
    }

    @Test
    void getPrincipal_returnsNullWhenAbsent() {
        when(request.getAttribute("principal")).thenReturn(null);
        assertThat(support.getPrincipal()).isNull();
    }

    @Test
    void getPrincipal_returnsNullWhenWrongType() {
        when(request.getAttribute("principal")).thenReturn("not a principal");
        assertThat(support.getPrincipal()).isNull();
    }

    // rawOnBehalfOf
    @Test
    void rawOnBehalfOf_returnsTrimmed() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("  bob  ");
        assertThat(support.rawOnBehalfOf()).isEqualTo("bob");
    }

    @Test
    void rawOnBehalfOf_returnsNullWhenBlank() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("   ");
        assertThat(support.rawOnBehalfOf()).isNull();
    }

    @Test
    void rawOnBehalfOf_truncatesAt255() {
        String longVal = "a".repeat(300);
        when(request.getHeader("X-On-Behalf-Of")).thenReturn(longVal);
        assertThat(support.rawOnBehalfOf()).hasSize(255);
    }

    // checkedOnBehalfOf
    @Test
    void checkedOnBehalfOf_returnsRawWhenServicePrincipal() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("bob");
        Principal.ServicePrincipal sp = new Principal.ServicePrincipal(UUID.randomUUID(), UUID.randomUUID(), Map.of());
        when(request.getAttribute("principal")).thenReturn(sp);
        assertThat(support.checkedOnBehalfOf()).isEqualTo("bob");
    }

    @Test
    void checkedOnBehalfOf_throwsWhenNotServicePrincipal() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("bob");
        Principal.UserPrincipal up = new Principal.UserPrincipal(UUID.randomUUID(), "user1", "USER");
        when(request.getAttribute("principal")).thenReturn(up);
        assertThatThrownBy(() -> support.checkedOnBehalfOf())
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void checkedOnBehalfOf_throwsWhenNoPrincipal() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn("bob");
        when(request.getAttribute("principal")).thenReturn(null);
        assertThatThrownBy(() -> support.checkedOnBehalfOf())
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void checkedOnBehalfOf_returnsNullWhenNoHeader() {
        when(request.getHeader("X-On-Behalf-Of")).thenReturn(null);
        assertThat(support.checkedOnBehalfOf()).isNull();
    }

    // requireOperate
    @Test
    void requireOperate_allowsWhenCanOperate() {
        Principal p = new Principal.UserPrincipal(UUID.randomUUID(), "user1", "USER");
        when(request.getAttribute("principal")).thenReturn(p);
        when(authorizationService.canOperate(p, "myKey", AuthorizationService.Action.START)).thenReturn(true);
        support.requireOperate("myKey", AuthorizationService.Action.START);
        verify(authorizationService).canOperate(p, "myKey", AuthorizationService.Action.START);
    }

    @Test
    void requireOperate_deniesWhenCannotOperate() {
        Principal p = new Principal.UserPrincipal(UUID.randomUUID(), "user1", "USER");
        when(request.getAttribute("principal")).thenReturn(p);
        when(authorizationService.canOperate(p, "myKey", AuthorizationService.Action.START)).thenReturn(false);
        assertThatThrownBy(() -> support.requireOperate("myKey", AuthorizationService.Action.START))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void requireOperate_throwsWhenNoPrincipal() {
        when(request.getAttribute("principal")).thenReturn(null);
        assertThatThrownBy(() -> support.requireOperate("key", AuthorizationService.Action.START))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requireOperate_throwsWhenDefinitionKeyNull() {
        Principal p = new Principal.UserPrincipal(UUID.randomUUID(), "user1", "USER");
        when(request.getAttribute("principal")).thenReturn(p);
        assertThatThrownBy(() -> support.requireOperate(null, AuthorizationService.Action.START))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    // resolveTargetDefinition
    @Test
    void resolveTargetDefinition_byId() {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        UUID id = UUID.randomUUID();
        dto.setProcessDefinitionId(id);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(id);
        when(processDefinitionRepository.findById(id)).thenReturn(Optional.of(pd));
        assertThat(support.resolveTargetDefinition(dto)).isEqualTo(pd);
    }

    @Test
    void resolveTargetDefinition_byKeyAndVersion() {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("key");
        dto.setProcessDefinitionVersion(2);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setKey("key");
        when(processDefinitionRepository.findByKeyAndVersion("key", 2)).thenReturn(Optional.of(pd));
        assertThat(support.resolveTargetDefinition(dto)).isEqualTo(pd);
    }

    @Test
    void resolveTargetDefinition_byKeyWithMaxVersion() {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("key");
        dto.setProcessDefinitionVersion(null);
        when(processDefinitionRepository.findMaxByKey("key")).thenReturn(Optional.of(5));
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setKey("key");
        when(processDefinitionRepository.findByKeyAndVersion("key", 5)).thenReturn(Optional.of(pd));
        assertThat(support.resolveTargetDefinition(dto)).isEqualTo(pd);
    }

    @Test
    void resolveTargetDefinition_returnsNullWhenKeyNull() {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(null);
        dto.setProcessDefinitionKey(null);
        assertThat(support.resolveTargetDefinition(dto)).isNull();
    }

    // resolveDefinitionKeyByInstance
    @Test
    void resolveDefinitionKeyByInstance_found() {
        UUID instanceId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(instanceId);
        pi.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(pdId);
        pd.setKey("myKey");
        when(processInstanceRepository.findById(instanceId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        assertThat(support.resolveDefinitionKeyByInstance(instanceId)).isEqualTo("myKey");
    }

    @Test
    void resolveDefinitionKeyByInstance_notFound() {
        UUID instanceId = UUID.randomUUID();
        when(processInstanceRepository.findById(instanceId)).thenReturn(Optional.empty());
        assertThat(support.resolveDefinitionKeyByInstance(instanceId)).isNull();
    }

    // resolveDefinitionKeyByServiceTask
    @Test
    void resolveDefinitionKeyByServiceTask_found() {
        UUID taskId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        ServiceTaskEntity st = new ServiceTaskEntity();
        st.setId(taskId);
        st.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(UUID.randomUUID());
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setKey("key");
        when(serviceTaskRepository.findById(taskId)).thenReturn(Optional.of(st));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pi.getProcessDefinitionId())).thenReturn(Optional.of(pd));
        assertThat(support.resolveDefinitionKeyByServiceTask(taskId)).isEqualTo("key");
    }

    @Test
    void resolveDefinitionKeyByServiceTask_notFound() {
        UUID taskId = UUID.randomUUID();
        when(serviceTaskRepository.findById(taskId)).thenReturn(Optional.empty());
        assertThat(support.resolveDefinitionKeyByServiceTask(taskId)).isNull();
    }

    // resolveDefinitionKeyByIncident
    @Test
    void resolveDefinitionKeyByIncident_found() {
        UUID incidentId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();
        IncidentEntity incident = new IncidentEntity();
        incident.setId(incidentId);
        incident.setActivityId(activityId);
        ActivityEntity activity = new ActivityEntity();
        activity.setId(activityId);
        activity.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(UUID.randomUUID());
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setKey("key");
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pi.getProcessDefinitionId())).thenReturn(Optional.of(pd));
        assertThat(support.resolveDefinitionKeyByIncident(incidentId)).isEqualTo("key");
    }

    @Test
    void resolveDefinitionKeyByIncident_notFound() {
        UUID incidentId = UUID.randomUUID();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());
        assertThat(support.resolveDefinitionKeyByIncident(incidentId)).isNull();
    }

    // resolvePrincipalId
    @Test
    void resolvePrincipalId_userPrincipal() {
        Principal.UserPrincipal up = new Principal.UserPrincipal(UUID.randomUUID(), "alice", "USER");
        assertThat(support.resolvePrincipalId(up)).isEqualTo("alice");
    }

    @Test
    void resolvePrincipalId_servicePrincipal() {
        UUID apiKeyId = UUID.randomUUID();
        Principal.ServicePrincipal sp = new Principal.ServicePrincipal(apiKeyId, UUID.randomUUID(), Map.of());
        assertThat(support.resolvePrincipalId(sp)).isEqualTo(apiKeyId.toString());
    }

    // checkAssignee
    @Test
    void checkAssignee_allowsSuperAdmin() {
        Principal superAdmin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        // need to mock isSuperAdmin true
        // SuperAdmin isSuperAdmin returns true
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee("bob");
        task.setCandidateGroups("group1");
        // should not throw
        support.checkAssignee(superAdmin, task);
    }

    @Test
    void checkAssignee_allowsWhenAssigneeMatches() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee("alice");
        task.setCandidateGroups("group1");
        task.setProcessInstanceId(UUID.randomUUID());
        support.checkAssignee(user, task);
    }

    @Test
    void checkAssignee_deniesWhenAssignedToOther() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee("bob");
        task.setCandidateGroups("group1");
        task.setProcessInstanceId(UUID.randomUUID());
        assertThatThrownBy(() -> support.checkAssignee(user, task))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void checkAssignee_allowsWhenCandidateGroupMatches() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee(null);
        task.setCandidateGroups("group1,group2");
        task.setProcessInstanceId(UUID.randomUUID());
        when(userGroupRepository.findGroupNamesByUserId(userId)).thenReturn(List.of("group2", "group3"));
        support.checkAssignee(user, task);
    }

    @Test
    void checkAssignee_deniesWhenCandidateGroupNotMatches() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee(null);
        task.setCandidateGroups("group1,group2");
        task.setProcessInstanceId(UUID.randomUUID());
        when(userGroupRepository.findGroupNamesByUserId(userId)).thenReturn(List.of("group3"));
        assertThatThrownBy(() -> support.checkAssignee(user, task))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void checkAssignee_allowsWhenProcessMember() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee(null);
        task.setCandidateGroups(null);
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        task.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(pdId);
        pd.setKey("key");
        ProcessEntity process = new ProcessEntity();
        process.setId(UUID.randomUUID());
        process.setDefinitionKey("key");
        ProcessMemberEntity membership = new ProcessMemberEntity();
        membership.setProcessId(process.getId());
        membership.setUserId(userId);
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        when(processRepository.findByDefinitionKey("key")).thenReturn(Optional.of(process));
        when(processMemberRepository.findById(new ProcessMemberId(process.getId(), userId))).thenReturn(Optional.of(membership));
        support.checkAssignee(user, task);
    }

    @Test
    void checkAssignee_deniesWhenNotProcessMember() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee(null);
        task.setCandidateGroups(null);
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        task.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(pdId);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(pdId);
        pd.setKey("key");
        ProcessEntity process = new ProcessEntity();
        process.setId(UUID.randomUUID());
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(processDefinitionRepository.findById(pdId)).thenReturn(Optional.of(pd));
        when(processRepository.findByDefinitionKey("key")).thenReturn(Optional.of(process));
        when(processMemberRepository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> support.checkAssignee(user, task))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    // requireOnBehalfMatchesTask
    @Test
    void requireOnBehalfMatchesTask_allowsWhenAssigneeMatches() {
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee("bob");
        task.setCandidateGroups(null);
        task.setProcessInstanceId(UUID.randomUUID());
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("bob");
        when(uiUserRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        support.requireOnBehalfMatchesTask(task, "bob");
    }

    @Test
    void requireOnBehalfMatchesTask_deniesWhenAssigneeMismatch() {
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee("alice");
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("bob");
        when(uiUserRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> support.requireOnBehalfMatchesTask(task, "bob"))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void requireOnBehalfMatchesTask_allowsWhenCandidateGroupMatches() {
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee(null);
        task.setCandidateGroups("group1,group2");
        task.setProcessInstanceId(UUID.randomUUID());
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("bob");
        when(uiUserRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        when(userGroupRepository.findGroupNamesByUserId(user.getId())).thenReturn(List.of("group2"));
        support.requireOnBehalfMatchesTask(task, "bob");
    }

    @Test
    void requireOnBehalfMatchesTask_deniesWhenUserNotFound() {
        UserTaskEntity task = new UserTaskEntity();
        task.setAssignee("bob");
        when(uiUserRepository.findByUsername("bob")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> support.requireOnBehalfMatchesTask(task, "bob"))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    // toDTO
    @Test
    void toDTO_mapsId() {
        com.zorrodev.bpm.engine.dto.IdDTO engineDto = new com.zorrodev.bpm.engine.dto.IdDTO();
        UUID id = UUID.randomUUID();
        engineDto.setId(id);
        com.zorrodev.bpm.contract.dto.IdDTO result = support.toDTO(engineDto);
        assertThat(result.getId()).isEqualTo(id);
    }
}
