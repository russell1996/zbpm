package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.engine.service.QueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryResourceTest {

    @Mock private QueryService queryService;
    @Mock private EventAuthzResolver eventAuthzResolver;
    @Mock private HttpServletRequest request;

    @InjectMocks
    private QueryResource resource;

    private static com.zorrodev.bpm.engine.security.Principal superAdmin() {
        return new com.zorrodev.bpm.engine.security.Principal.UserPrincipal(
            UUID.randomUUID(), "admin", "SUPER_ADMIN");
    }

    @Test
    void getVariables_delegates() {
        VariableQuery query = new VariableQuery();
        PagedDataDTO<ProcessVariable> expected = new PagedDataDTO<>();
        when(queryService.findVariables(query, null)).thenReturn(expected);

        assertThat(resource.getVariables(query)).isSameAs(expected);
    }

    @Test
    void getServiceTasks_delegates() {
        ServiceTaskQuery query = new ServiceTaskQuery();
        PagedDataDTO<ServiceTask> expected = new PagedDataDTO<>();
        when(queryService.findServiceTasks(query, null)).thenReturn(expected);

        assertThat(resource.getServiceTasks(query)).isSameAs(expected);
    }

    @Test
    void getUserTasks_delegates() {
        UserTaskQuery query = new UserTaskQuery();
        PagedDataDTO<UserTask> expected = new PagedDataDTO<>();
        when(queryService.findUserTasks(query, null)).thenReturn(expected);
        when(request.getAttribute("principal")).thenReturn(superAdmin());
        when(eventAuthzResolver.resolve(any(), any())).thenReturn(null); // null = see all (admin)

        assertThat(resource.getUserTasks(query)).isSameAs(expected);
    }

    @Test
    void getProcessInstances_delegates() {
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        PagedDataDTO<ProcessInstance> expected = new PagedDataDTO<>();
        when(queryService.findProcessInstances(query, null)).thenReturn(expected);
        when(request.getAttribute("principal")).thenReturn(superAdmin());
        when(eventAuthzResolver.resolve(any(), any())).thenReturn(null);

        assertThat(resource.getProcessInstances(query)).isSameAs(expected);
    }

    @Test
    void getIncidents_delegates() {
        IncidentQuery query = new IncidentQuery();
        PagedDataDTO<Incident> expected = new PagedDataDTO<>();
        when(queryService.findIncidents(query, null)).thenReturn(expected);

        assertThat(resource.getIncidents(query)).isSameAs(expected);
    }
}
