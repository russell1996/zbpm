package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.*;
import com.zorrodev.bpm.engine.repository.*;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeResourceCharacterizationTest {

    @Mock private RuntimeService runtimeService;
    @Mock private UserTaskRepository userTaskRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ProcessDefinitionRepository processDefinitionRepository;
    @Mock private ProcessRepository processRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private AuthorizationService authorizationService;
    @Mock private DBService dbService;
    @Mock private AuditLogService auditLogService;
    @Mock private UserGroupRepository userGroupRepository;
    @Mock private FormArtifactService formArtifactService;
    @Mock private ProcessMemberRepository processMemberRepository;
    @Mock private UiUserRepository uiUserRepository;
    @Mock private jakarta.servlet.http.HttpServletRequest request;
    @InjectMocks private RuntimeResource resource;

    @Test
    void startProcessInstance_unauthorized_throws401() {
        when(request.getAttribute("principal")).thenReturn(null);
        com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO dto = new com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("k");
        assertThatThrownBy(() -> resource.startProcessInstance(dto)).hasMessageContaining("Authentication required");
    }

    @Test
    void completeUserTask_notFound_throws404() {
        Principal.UserPrincipal p = new Principal.UserPrincipal(UUID.randomUUID(), "u", "USER");
        when(request.getAttribute("principal")).thenReturn(p);
        when(userTaskRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resource.completeUserTask(UUID.randomUUID(), new com.zorrodev.bpm.contract.dto.CompleteTaskDTO())).hasMessageContaining("User task not found");
    }

    @Test
    void claimUserTask_alreadyCompleted_throws409() {
        Principal.UserPrincipal p = new Principal.UserPrincipal(UUID.randomUUID(), "u", "USER");
        when(request.getAttribute("principal")).thenReturn(p);
        UserTaskEntity t = new UserTaskEntity(); t.setId(UUID.randomUUID()); t.setCompletedAt(java.time.Instant.now());
        when(userTaskRepository.findById(any(UUID.class))).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> resource.claimUserTask(t.getId())).hasMessageContaining("already completed");
    }

    @Test
    void getPrincipal_null_returnsNull() {
        when(request.getAttribute("principal")).thenReturn(null);
        assertThatThrownBy(() -> resource.startProcessInstance(new com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO())).hasMessageContaining("Authentication required");
    }
}
