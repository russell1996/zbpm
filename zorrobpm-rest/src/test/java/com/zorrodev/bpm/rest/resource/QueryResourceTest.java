package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.engine.service.QueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
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

    @InjectMocks private QueryResource resource;

    @BeforeEach
    void setUp() {
        // Stub principal so resolveAllowedPdIds() returns null (see-all, admin)
        when(request.getAttribute("principal")).thenReturn(
            new com.zorrodev.bpm.engine.security.Principal.UserPrincipal(
                UUID.randomUUID(), "admin", "SUPER_ADMIN"));
        when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
    }

    @Test
    void getVariables_delegates() {
        VariableQuery q = new VariableQuery();
        var expected = new PagedDataDTO<ProcessVariable>();
        when(queryService.findVariables(q, null)).thenReturn(expected);
        assertThat(resource.getVariables(q)).isSameAs(expected);
    }

    @Test
    void getServiceTasks_delegates() {
        ServiceTaskQuery q = new ServiceTaskQuery();
        var expected = new PagedDataDTO<ServiceTask>();
        when(queryService.findServiceTasks(q, null)).thenReturn(expected);
        assertThat(resource.getServiceTasks(q)).isSameAs(expected);
    }

    @Test
    void getUserTasks_delegates() {
        UserTaskQuery q = new UserTaskQuery();
        var expected = new PagedDataDTO<UserTask>();
        when(queryService.findUserTasks(q, null)).thenReturn(expected);
        assertThat(resource.getUserTasks(q)).isSameAs(expected);
    }

    @Test
    void getProcessInstances_delegates() {
        ProcessInstanceQuery q = new ProcessInstanceQuery();
        var expected = new PagedDataDTO<ProcessInstance>();
        when(queryService.findProcessInstances(q, null)).thenReturn(expected);
        assertThat(resource.getProcessInstances(q)).isSameAs(expected);
    }

    @Test
    void getIncidents_delegates() {
        IncidentQuery q = new IncidentQuery();
        var expected = new PagedDataDTO<Incident>();
        when(queryService.findIncidents(q, null)).thenReturn(expected);
        assertThat(resource.getIncidents(q)).isSameAs(expected);
    }

    @Test
    void getTimerJobs_delegates() {
        TimerJobQuery q = new TimerJobQuery();
        var expected = new PagedDataDTO<TimerJob>();
        when(queryService.findTimerJobs(q, null)).thenReturn(expected);
        assertThat(resource.getTimerJobs(q)).isSameAs(expected);
    }

    @Test
    void getMessageSubscriptions_delegates() {
        MessageSubscriptionQuery q = new MessageSubscriptionQuery();
        var expected = new PagedDataDTO<MessageSubscription>();
        when(queryService.findMessageSubscriptions(q, null)).thenReturn(expected);
        assertThat(resource.getMessageSubscriptions(q)).isSameAs(expected);
    }
}
