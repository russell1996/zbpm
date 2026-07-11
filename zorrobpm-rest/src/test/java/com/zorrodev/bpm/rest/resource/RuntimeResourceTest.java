package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeResourceTest {

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
    @Mock private HttpServletRequest request;

    @InjectMocks
    private RuntimeResource resource;

    private com.zorrodev.bpm.engine.dto.IdDTO toEngineDTO(IdDTO expected) {
        return new com.zorrodev.bpm.engine.dto.IdDTO(expected.getId());
    }

    private ProcessDefinitionEntity mockResolveChain() {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setKey("test-process");
        when(processInstanceRepository.findById(any())).thenReturn(Optional.of(new ProcessInstanceEntity()));
        when(processDefinitionRepository.findById(any())).thenReturn(Optional.of(pd));
        return pd;
    }

    @Test
    void startProcessInstance_delegatesToService() {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("test-process");
        IdDTO expected = new IdDTO(UUID.randomUUID());
        when(runtimeService.startProcessInstance(dto)).thenReturn(toEngineDTO(expected));

        when(request.getAttribute("principal")).thenReturn(
            new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN"));
        when(authorizationService.canOperate(any(), any(), any())).thenReturn(true);

        IdDTO result = resource.startProcessInstance(dto);

        assertThat(result.getId()).isSameAs(expected.getId());
    }

    @Test
    void completeServiceTask_delegatesToService() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(vars);
        IdDTO expected = new IdDTO(id);
        when(runtimeService.completeServiceTask(id, vars)).thenReturn(toEngineDTO(expected));

        when(request.getAttribute("principal")).thenReturn(
            new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN"));
        when(authorizationService.canOperate(any(), any(), any())).thenReturn(true);

        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(new ServiceTaskEntity()));
        mockResolveChain();

        IdDTO result = resource.completeServiceTask(id, dto);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).completeServiceTask(id, vars);
    }

    @Test
    void completeUserTask_delegatesToService() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(vars);
        IdDTO expected = new IdDTO(id);
        when(runtimeService.completeUserTask(id, vars)).thenReturn(toEngineDTO(expected));

        Principal.UserPrincipal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        when(request.getAttribute("principal")).thenReturn(admin);
        when(authorizationService.canCompleteUserTask(any(), any())).thenReturn(true);

        UserTaskEntity task = new UserTaskEntity();
        task.setId(id);
        task.setProcessInstanceId(UUID.randomUUID());
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(task));

        IdDTO result = resource.completeUserTask(id, dto);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).completeUserTask(id, vars);
    }

    @Test
    void resolveIncident_delegatesToService() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();
        ResolveIncidentDTO dto = new ResolveIncidentDTO();
        dto.setVariables(vars);
        IdDTO expected = new IdDTO(id);
        when(runtimeService.resolveIncident(id, vars)).thenReturn(toEngineDTO(expected));

        when(request.getAttribute("principal")).thenReturn(
            new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN"));
        when(authorizationService.canOperate(any(), any(), any())).thenReturn(true);

        // Mock incident → activity → processInstance → processDefinition resolution
        IncidentEntity incident = new IncidentEntity();
        incident.setId(id);
        incident.setActivityId(UUID.randomUUID());
        when(incidentRepository.findById(id)).thenReturn(Optional.of(incident));
        when(activityRepository.findById(any())).thenReturn(Optional.of(new ActivityEntity()));
        mockResolveChain();

        IdDTO result = resource.resolveIncident(id, dto);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).resolveIncident(id, vars);
    }
}
