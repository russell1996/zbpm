package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.query.*;
import com.zorrodev.bpm.contract.model.*;
import com.zorrodev.bpm.engine.entity.*;
import com.zorrodev.bpm.engine.mapper.*;
import com.zorrodev.bpm.engine.repository.*;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.query.ActivityQueryOperations;
import com.zorrodev.bpm.engine.service.query.IncidentQueryOperations;
import com.zorrodev.bpm.engine.service.query.MessageSubscriptionQueryOperations;
import com.zorrodev.bpm.engine.service.query.ProcessInstanceQueryOperations;
import com.zorrodev.bpm.engine.service.query.ServiceTaskQueryOperations;
import com.zorrodev.bpm.engine.service.query.TimerJobQueryOperations;
import com.zorrodev.bpm.engine.service.query.UserTaskQueryOperations;
import com.zorrodev.bpm.engine.service.query.VariableQueryOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryServiceImplCharacterizationTest {

    @Mock private DBService dbService;
    @Mock private NamedParameterJdbcTemplate namedJdbc;
    @Mock private ServiceTaskMapper serviceTaskMapper;
    @Mock private UserTaskMapper userTaskMapper;
    @Mock private ProcessInstanceMapper processInstanceMapper;
    @Mock private IncidentMapper incidentMapper;
    @Mock private VariableMapper variableMapper;
    @Mock private ActivityInstanceMapper activityInstanceMapper;
    @Mock private TimerJobMapper timerJobMapper;
    @Mock private ActivityQueryOperations activityQueryOperations;
    @Mock private ServiceTaskQueryOperations serviceTaskQueryOperations;
    @Mock private UserTaskQueryOperations userTaskQueryOperations;
    @Mock private ProcessInstanceQueryOperations processInstanceQueryOperations;
    @Mock private IncidentQueryOperations incidentQueryOperations;
    @Mock private TimerJobQueryOperations timerJobQueryOperations;
    @Mock private MessageSubscriptionQueryOperations messageSubscriptionQueryOperations;
    @Mock private VariableQueryOperations variableQueryOperations;
    @Mock private MessageSubscriptionMapper messageSubscriptionMapper;
    @Mock private UserTaskRepository userTaskRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private VariableRepository variableRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private TimerJobRepository timerJobRepository;
    @Mock private MessageSubscriptionRepository messageSubscriptionRepository;
    @InjectMocks private QueryServiceImpl queryService;

    @Test
    void findTimerJobs_delegatesToTimerJobQueryOperations() {
        TimerJobQuery q = new TimerJobQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<UUID> allowed = List.of(UUID.randomUUID());
        PagedDataDTO<TimerJob> expected = new PagedDataDTO<>();
        expected.setData(List.of(new TimerJob()));
        expected.setTotalElements(1L);
        when(timerJobQueryOperations.findTimerJobs(q, allowed)).thenReturn(expected);
        PagedDataDTO<TimerJob> result = queryService.findTimerJobs(q, allowed);
        assertThat(result).isEqualTo(expected);
        verify(timerJobQueryOperations).findTimerJobs(q, allowed);
    }

    @Test
    void findTimerJobs_emptyAllowed_delegatesToTimerJobQueryOperations() {
        TimerJobQuery q = new TimerJobQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<TimerJob> expected = new PagedDataDTO<>();
        expected.setTotalElements(0L);
        expected.setData(List.of());
        when(timerJobQueryOperations.findTimerJobs(q, List.of())).thenReturn(expected);
        PagedDataDTO<TimerJob> result = queryService.findTimerJobs(q, List.of());
        assertThat(result).isEqualTo(expected);
        verify(timerJobQueryOperations).findTimerJobs(q, List.of());
    }

    @Test
    void findMessageSubscriptions_delegatesToMessageSubscriptionQueryOperations() {
        MessageSubscriptionQuery q = new MessageSubscriptionQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<UUID> allowed = List.of(UUID.randomUUID());
        PagedDataDTO<MessageSubscription> expected = new PagedDataDTO<>();
        expected.setData(List.of(new MessageSubscription()));
        expected.setTotalElements(1L);
        when(messageSubscriptionQueryOperations.findMessageSubscriptions(q, allowed)).thenReturn(expected);
        PagedDataDTO<MessageSubscription> result = queryService.findMessageSubscriptions(q, allowed);
        assertThat(result).isEqualTo(expected);
        verify(messageSubscriptionQueryOperations).findMessageSubscriptions(q, allowed);
    }

    @Test
    void findMessageSubscriptions_emptyAllowed_delegatesToMessageSubscriptionQueryOperations() {
        MessageSubscriptionQuery q = new MessageSubscriptionQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<MessageSubscription> expected = new PagedDataDTO<>();
        expected.setTotalElements(0L);
        expected.setData(List.of());
        when(messageSubscriptionQueryOperations.findMessageSubscriptions(q, List.of())).thenReturn(expected);
        PagedDataDTO<MessageSubscription> result = queryService.findMessageSubscriptions(q, List.of());
        assertThat(result).isEqualTo(expected);
        verify(messageSubscriptionQueryOperations).findMessageSubscriptions(q, List.of());
    }

    @Test
    void getActivities_delegatesToActivityQueryOperations() {
        UUID pi = UUID.randomUUID();
        List<ActivityInstance> expected = List.of(new ActivityInstance());
        when(activityQueryOperations.getActivities(pi)).thenReturn(expected);
        List<ActivityInstance> result = queryService.getActivities(pi);
        assertThat(result).isEqualTo(expected);
        verify(activityQueryOperations).getActivities(pi);
    }

    @Test
    void findServiceTasks_delegatesToServiceTaskQueryOperations() {
        ServiceTaskQuery q = new ServiceTaskQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<UUID> allowed = List.of(UUID.randomUUID());
        PagedDataDTO<ServiceTask> expected = new PagedDataDTO<>();
        expected.setData(List.of(new ServiceTask()));
        expected.setTotalElements(1L);
        when(serviceTaskQueryOperations.findServiceTasks(q, allowed)).thenReturn(expected);
        PagedDataDTO<ServiceTask> result = queryService.findServiceTasks(q, allowed);
        assertThat(result).isEqualTo(expected);
        verify(serviceTaskQueryOperations).findServiceTasks(q, allowed);
    }

    @Test
    void getServiceTask_delegatesToServiceTaskQueryOperations() {
        UUID id = UUID.randomUUID();
        ServiceTask expected = new ServiceTask(); expected.setId(id);
        when(serviceTaskQueryOperations.getServiceTask(id)).thenReturn(expected);
        assertThat(queryService.getServiceTask(id)).isEqualTo(expected);
        verify(serviceTaskQueryOperations).getServiceTask(id);
    }

    @Test
    void findUserTasks_delegatesToUserTaskQueryOperations() {
        UserTaskQuery q = new UserTaskQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<UUID> allowed = List.of(UUID.randomUUID());
        PagedDataDTO<UserTask> expected = new PagedDataDTO<>();
        expected.setData(List.of(new UserTask()));
        expected.setTotalElements(1L);
        when(userTaskQueryOperations.findUserTasks(q, allowed)).thenReturn(expected);
        PagedDataDTO<UserTask> result = queryService.findUserTasks(q, allowed);
        assertThat(result).isEqualTo(expected);
        verify(userTaskQueryOperations).findUserTasks(q, allowed);
    }

    @Test
    void getUserTask_delegatesToUserTaskQueryOperations() {
        UUID id = UUID.randomUUID();
        UserTask expected = new UserTask(); expected.setId(id);
        when(userTaskQueryOperations.getUserTask(id)).thenReturn(expected);
        assertThat(queryService.getUserTask(id)).isEqualTo(expected);
        verify(userTaskQueryOperations).getUserTask(id);
    }

    @Test
    void findUserTasks_emptyAllowed_delegatesToUserTaskQueryOperations() {
        UserTaskQuery q = new UserTaskQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<UserTask> expected = new PagedDataDTO<>();
        expected.setTotalElements(0L);
        expected.setData(List.of());
        when(userTaskQueryOperations.findUserTasks(q, List.of())).thenReturn(expected);
        PagedDataDTO<UserTask> result = queryService.findUserTasks(q, List.of());
        assertThat(result).isEqualTo(expected);
        verify(userTaskQueryOperations).findUserTasks(q, List.of());
    }

    @Test
    void getProcessInstance_delegatesToProcessInstanceQueryOperations() {
        UUID id = UUID.randomUUID();
        ProcessInstance expected = new ProcessInstance(); expected.setId(id);
        when(processInstanceQueryOperations.getProcessInstance(id)).thenReturn(expected);
        ProcessInstance result = queryService.getProcessInstance(id);
        assertThat(result).isEqualTo(expected);
        verify(processInstanceQueryOperations).getProcessInstance(id);
    }

    @Test
    void findProcessInstances_delegatesToProcessInstanceQueryOperations() {
        ProcessInstanceQuery q = new ProcessInstanceQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<UUID> allowed = List.of(UUID.randomUUID());
        PagedDataDTO<ProcessInstance> expected = new PagedDataDTO<>();
        expected.setData(List.of(new ProcessInstance()));
        expected.setTotalElements(1L);
        when(processInstanceQueryOperations.findProcessInstances(q, allowed)).thenReturn(expected);
        PagedDataDTO<ProcessInstance> result = queryService.findProcessInstances(q, allowed);
        assertThat(result).isEqualTo(expected);
        verify(processInstanceQueryOperations).findProcessInstances(q, allowed);
    }

    @Test
    void findProcessInstances_emptyAllowed_delegatesToProcessInstanceQueryOperations() {
        ProcessInstanceQuery q = new ProcessInstanceQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<ProcessInstance> expected = new PagedDataDTO<>();
        expected.setTotalElements(0L);
        expected.setData(List.of());
        when(processInstanceQueryOperations.findProcessInstances(q, List.of())).thenReturn(expected);
        PagedDataDTO<ProcessInstance> result = queryService.findProcessInstances(q, List.of());
        assertThat(result).isEqualTo(expected);
        verify(processInstanceQueryOperations).findProcessInstances(q, List.of());
    }

    @Test
    void getIncident_delegatesToIncidentQueryOperations() {
        UUID id = UUID.randomUUID();
        Incident expected = new Incident(); expected.setId(id);
        when(incidentQueryOperations.getIncident(id)).thenReturn(expected);
        Incident result = queryService.getIncident(id);
        assertThat(result).isEqualTo(expected);
        verify(incidentQueryOperations).getIncident(id);
    }

    @Test
    void findIncidents_delegatesToIncidentQueryOperations() {
        IncidentQuery q = new IncidentQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<UUID> allowed = List.of(UUID.randomUUID());
        PagedDataDTO<Incident> expected = new PagedDataDTO<>();
        expected.setData(List.of(new Incident()));
        expected.setTotalElements(1L);
        when(incidentQueryOperations.findIncidents(q, allowed)).thenReturn(expected);
        PagedDataDTO<Incident> result = queryService.findIncidents(q, allowed);
        assertThat(result).isEqualTo(expected);
        verify(incidentQueryOperations).findIncidents(q, allowed);
    }

    @Test
    void findIncidents_emptyAllowed_delegatesToIncidentQueryOperations() {
        IncidentQuery q = new IncidentQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<Incident> expected = new PagedDataDTO<>();
        expected.setTotalElements(0L);
        expected.setData(List.of());
        when(incidentQueryOperations.findIncidents(q, List.of())).thenReturn(expected);
        PagedDataDTO<Incident> result = queryService.findIncidents(q, List.of());
        assertThat(result).isEqualTo(expected);
        verify(incidentQueryOperations).findIncidents(q, List.of());
    }

    @Test
    void findVariables_delegatesToVariableQueryOperations() {
        VariableQuery q = new VariableQuery(); q.setPageIndex(0); q.setPageSize(10);
        List<UUID> allowed = List.of(UUID.randomUUID());
        PagedDataDTO<ProcessVariable> expected = new PagedDataDTO<>();
        expected.setData(List.of(new ProcessVariable()));
        expected.setTotalElements(1L);
        when(variableQueryOperations.findVariables(q, allowed)).thenReturn(expected);
        PagedDataDTO<ProcessVariable> result = queryService.findVariables(q, allowed);
        assertThat(result).isEqualTo(expected);
        verify(variableQueryOperations).findVariables(q, allowed);
    }

    @Test
    void findVariables_emptyAllowed_delegatesToVariableQueryOperations() {
        VariableQuery q = new VariableQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<ProcessVariable> expected = new PagedDataDTO<>();
        expected.setTotalElements(0L);
        expected.setData(List.of());
        when(variableQueryOperations.findVariables(q, List.of())).thenReturn(expected);
        PagedDataDTO<ProcessVariable> result = queryService.findVariables(q, List.of());
        assertThat(result).isEqualTo(expected);
        verify(variableQueryOperations).findVariables(q, List.of());
    }

    @Test
    void findServiceTasks_emptyAllowed_delegatesToServiceTaskQueryOperations() {
        ServiceTaskQuery q = new ServiceTaskQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<ServiceTask> expected = new PagedDataDTO<>();
        expected.setTotalElements(0L);
        expected.setData(List.of());
        when(serviceTaskQueryOperations.findServiceTasks(q, List.of())).thenReturn(expected);
        PagedDataDTO<ServiceTask> result = queryService.findServiceTasks(q, List.of());
        assertThat(result).isEqualTo(expected);
        verify(serviceTaskQueryOperations).findServiceTasks(q, List.of());
    }

    @Test
    void resolveIncidentProcessDefinitionId_delegatesToIncidentQueryOperations() {
        UUID incidentId = UUID.randomUUID();
        UUID expected = UUID.randomUUID();
        when(incidentQueryOperations.resolveIncidentProcessDefinitionId(incidentId)).thenReturn(expected);
        UUID result = queryService.resolveIncidentProcessDefinitionId(incidentId);
        assertThat(result).isEqualTo(expected);
        verify(incidentQueryOperations).resolveIncidentProcessDefinitionId(incidentId);
    }

    @Test
    void findTimerJobs_withAllowedPdIds_delegatesToTimerJobQueryOperations() {
        TimerJobQuery q = new TimerJobQuery(); q.setProcessInstanceId(UUID.randomUUID()); q.setPageIndex(0); q.setPageSize(10);
        List<UUID> allowed = List.of(UUID.randomUUID());
        PagedDataDTO<TimerJob> expected = new PagedDataDTO<>();
        expected.setData(List.of(new TimerJob()));
        expected.setTotalElements(1L);
        when(timerJobQueryOperations.findTimerJobs(q, allowed)).thenReturn(expected);
        PagedDataDTO<TimerJob> result = queryService.findTimerJobs(q, allowed);
        assertThat(result).isEqualTo(expected);
        verify(timerJobQueryOperations).findTimerJobs(q, allowed);
    }

}
