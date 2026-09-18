package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.*;
import com.zorrodev.bpm.engine.repository.*;
import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WO-DEBT-7 S1: unit tests for {@link RuntimeSupportService} — moved verbatim
 * from rest {@code RuntimeOperationSupportTest} together with the code they pin.
 * Only fixtures adapted (target bean + entity args unpacked to scalar fields);
 * every assertion/expectation is byte-identical to the original.
 */
@ExtendWith(MockitoExtension.class)
class RuntimeSupportServiceTest {

    @Mock private UiUserRepository uiUserRepository;
    @Mock private UserGroupRepository userGroupRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ProcessDefinitionRepository processDefinitionRepository;
    @Mock private ProcessRepository processRepository;
    @Mock private ProcessMemberRepository processMemberRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private ActivityRepository activityRepository;

    @InjectMocks private RuntimeSupportService support;

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

    // checkAssignee
    @Test
    void checkAssignee_allowsSuperAdmin() {
        Principal superAdmin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        // need to mock isSuperAdmin true
        // SuperAdmin isSuperAdmin returns true
        // should not throw
        support.checkAssignee(superAdmin, "bob", "group1", UUID.randomUUID());
    }

    @Test
    void checkAssignee_allowsWhenAssigneeMatches() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        support.checkAssignee(user, "alice", "group1", UUID.randomUUID());
    }

    @Test
    void checkAssignee_deniesWhenAssignedToOther() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        assertThatThrownBy(() -> support.checkAssignee(user, "bob", "group1", UUID.randomUUID()))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void checkAssignee_allowsWhenCandidateGroupMatches() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        when(userGroupRepository.findGroupNamesByUserId(userId)).thenReturn(List.of("group2", "group3"));
        support.checkAssignee(user, null, "group1,group2", UUID.randomUUID());
    }

    @Test
    void checkAssignee_deniesWhenCandidateGroupNotMatches() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        when(userGroupRepository.findGroupNamesByUserId(userId)).thenReturn(List.of("group3"));
        assertThatThrownBy(() -> support.checkAssignee(user, null, "group1,group2", UUID.randomUUID()))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void checkAssignee_allowsWhenProcessMember() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        UUID piId = UUID.randomUUID();
        when(processInstanceRepository.findDefinitionKeyById(piId)).thenReturn(Optional.of("key"));
        when(processMemberRepository.isMemberByDefinitionKey(userId, "key")).thenReturn(true);
        support.checkAssignee(user, null, null, piId);
    }

    @Test
    void checkAssignee_deniesWhenNotProcessMember() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        UUID piId = UUID.randomUUID();
        when(processInstanceRepository.findDefinitionKeyById(piId)).thenReturn(Optional.of("key"));
        when(processMemberRepository.isMemberByDefinitionKey(userId, "key")).thenReturn(false);
        assertThatThrownBy(() -> support.checkAssignee(user, null, null, piId))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void checkAssignee_deniesWhenNoProcessForDefinitionKey() {
        UUID userId = UUID.randomUUID();
        Principal.UserPrincipal user = new Principal.UserPrincipal(userId, "alice", "USER");
        UUID piId = UUID.randomUUID();
        when(processInstanceRepository.findDefinitionKeyById(piId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> support.checkAssignee(user, null, null, piId))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    // requireOnBehalfMatchesTask
    @Test
    void requireOnBehalfMatchesTask_allowsWhenAssigneeMatches() {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("bob");
        when(uiUserRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        support.requireOnBehalfMatchesTask("bob", null, UUID.randomUUID(), "bob");
    }

    @Test
    void requireOnBehalfMatchesTask_deniesWhenAssigneeMismatch() {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("bob");
        when(uiUserRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> support.requireOnBehalfMatchesTask("alice", null, null, "bob"))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void requireOnBehalfMatchesTask_allowsWhenCandidateGroupMatches() {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("bob");
        when(uiUserRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        when(userGroupRepository.findGroupNamesByUserId(user.getId())).thenReturn(List.of("group2"));
        support.requireOnBehalfMatchesTask(null, "group1,group2", UUID.randomUUID(), "bob");
    }

    @Test
    void requireOnBehalfMatchesTask_deniesWhenUserNotFound() {
        when(uiUserRepository.findByUsername("bob")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> support.requireOnBehalfMatchesTask("bob", null, null, "bob"))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }
}
