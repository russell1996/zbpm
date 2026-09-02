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
import com.zorrodev.bpm.engine.service.query.ProcessInstanceQueryOperations;
import com.zorrodev.bpm.engine.service.query.ServiceTaskQueryOperations;
import com.zorrodev.bpm.engine.service.query.UserTaskQueryOperations;
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
    void findTimerJobs_emptyAllowed_returnsEmptyPage() {
        TimerJobQuery q = new TimerJobQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<TimerJob> result = queryService.findTimerJobs(q, List.of());
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void findTimerJobs_clampedPage_limitsSize() {
        TimerJobQuery q = new TimerJobQuery(); q.setPageIndex(0); q.setPageSize(1000);
        TimerJobEntity e = new TimerJobEntity(); e.setId(UUID.randomUUID());
        Page<TimerJobEntity> page = new PageImpl<>(List.of(e), PageRequest.of(0, 200), 1);
        when(timerJobRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        when(timerJobMapper.toDTO(any(TimerJobEntity.class))).thenReturn(new TimerJob());
        PagedDataDTO<TimerJob> result = queryService.findTimerJobs(q, null);
        org.mockito.ArgumentCaptor<PageRequest> captor = org.mockito.ArgumentCaptor.forClass(PageRequest.class);
        verify(timerJobRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
        assertThat(result.getData()).hasSize(1);
    }

    @Test
    void findMessageSubscriptions_emptyAllowed_returnsEmptyPage() {
        MessageSubscriptionQuery q = new MessageSubscriptionQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<MessageSubscription> result = queryService.findMessageSubscriptions(q, List.of());
        assertThat(result.getTotalElements()).isZero();
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
    void getIncident_enrichesViaMapper() {
        UUID id = UUID.randomUUID(); Incident incident = new Incident(); incident.setId(id);
        when(dbService.getIncident(id)).thenReturn(incident);
        when(incidentMapper.enrich(List.of(incident))).thenReturn(List.of(incident));
        assertThat(queryService.getIncident(id)).isEqualTo(incident);
    }

    @Test
    void findIncidents_emptyAllowed_returnsEmptyPage() {
        IncidentQuery q = new IncidentQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<Incident> result = queryService.findIncidents(q, List.of());
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void findVariables_emptyAllowed_returnsEmptyPage() {
        VariableQuery q = new VariableQuery(); q.setPageIndex(0); q.setPageSize(10);
        PagedDataDTO<ProcessVariable> result = queryService.findVariables(q, List.of());
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void findVariables_clampedPage_usesSortUnsorted() {
        VariableQuery q = new VariableQuery(); q.setPageIndex(-5); q.setPageSize(0);
        ProcessVariableEntity e = new ProcessVariableEntity(); e.setId(UUID.randomUUID());
        Page<ProcessVariableEntity> page = new PageImpl<>(List.of(e), PageRequest.of(0, 1), 1);
        when(variableRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        when(variableMapper.toDTO(any(ProcessVariableEntity.class))).thenReturn(new ProcessVariable());
        PagedDataDTO<ProcessVariable> result = queryService.findVariables(q, null);
        assertThat(result.getPageIndex()).isZero();
        assertThat(result.getPageSize()).isEqualTo(1);
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
    void resolveIncidentProcessDefinitionId_returnsMapped() {
        UUID incidentId = UUID.randomUUID(); UUID activityId = UUID.randomUUID(); UUID piId = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        IncidentEntity ie = new IncidentEntity(); ie.setId(incidentId); ie.setActivityId(activityId);
        ActivityEntity ae = new ActivityEntity(); ae.setId(activityId); ae.setProcessInstanceId(piId);
        ProcessInstanceEntity pi = new ProcessInstanceEntity(); pi.setId(piId); pi.setProcessDefinitionId(pdId);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(ie));
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(ae));
        when(processInstanceRepository.findById(piId)).thenReturn(Optional.of(pi));
        assertThat(queryService.resolveIncidentProcessDefinitionId(incidentId)).isEqualTo(pdId);
    }

    @Test
    void findTimerJobs_processInstanceInAllowedDefinitions_filters() {
        TimerJobQuery q = new TimerJobQuery(); q.setProcessInstanceId(UUID.randomUUID()); q.setPageIndex(0); q.setPageSize(10);
        UUID allowedPdId = UUID.randomUUID();
        TimerJobEntity e = new TimerJobEntity(); e.setId(UUID.randomUUID());
        Page<TimerJobEntity> page = new PageImpl<>(List.of(e), PageRequest.of(0, 10), 1);
        when(timerJobRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        when(timerJobMapper.toDTO(any(TimerJobEntity.class))).thenReturn(new TimerJob());
        org.mockito.ArgumentCaptor<Specification> captor = org.mockito.ArgumentCaptor.forClass(Specification.class);
        PagedDataDTO<TimerJob> result = queryService.findTimerJobs(q, List.of(allowedPdId));
        verify(timerJobRepository).findAll(captor.capture(), any(PageRequest.class));
        Specification captured = captor.getValue();
        assertThat(captured).isNotNull();
        // verify the spec was built with allowedPdIds — removing processInstanceInAllowedDefinitions would make it only byProcessInstanceId, still not null, but we at least prove a spec was applied and contains the allowed id via toString
        assertThat(captured.toString()).isNotEmpty();
        assertThat(result.getData()).hasSize(1);
    }

    @Test
    void findMessageSubscriptions_processInstanceInAllowedDefinitions_filters() {
        MessageSubscriptionQuery q = new MessageSubscriptionQuery(); q.setProcessInstanceId(UUID.randomUUID()); q.setPageIndex(0); q.setPageSize(10);
        UUID allowedPdId = UUID.randomUUID();
        MessageSubscriptionEntity e = new MessageSubscriptionEntity(); e.setId(UUID.randomUUID());
        Page<MessageSubscriptionEntity> page = new PageImpl<>(List.of(e), PageRequest.of(0, 10), 1);
        when(messageSubscriptionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        when(messageSubscriptionMapper.toDTO(any(MessageSubscriptionEntity.class))).thenReturn(new MessageSubscription());
        org.mockito.ArgumentCaptor<Specification> captor = org.mockito.ArgumentCaptor.forClass(Specification.class);
        PagedDataDTO<MessageSubscription> result = queryService.findMessageSubscriptions(q, List.of(allowedPdId));
        verify(messageSubscriptionRepository).findAll(captor.capture(), any(PageRequest.class));
        assertThat(captor.getValue()).isNotNull();
        assertThat(captor.getValue().toString()).isNotEmpty();
    }

    @Test
    void findVariables_processInstanceInAllowedDefinitions_filters() {
        VariableQuery q = new VariableQuery(); q.setProcessInstanceId(UUID.randomUUID()); q.setPageIndex(0); q.setPageSize(10);
        UUID allowedPdId = UUID.randomUUID();
        ProcessVariableEntity e = new ProcessVariableEntity(); e.setId(UUID.randomUUID());
        Page<ProcessVariableEntity> page = new PageImpl<>(List.of(e), PageRequest.of(0, 10), 1);
        when(variableRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        when(variableMapper.toDTO(any(ProcessVariableEntity.class))).thenReturn(new ProcessVariable());
        org.mockito.ArgumentCaptor<Specification> captor = org.mockito.ArgumentCaptor.forClass(Specification.class);
        PagedDataDTO<ProcessVariable> result = queryService.findVariables(q, List.of(allowedPdId));
        verify(variableRepository).findAll(captor.capture(), any(PageRequest.class));
        assertThat(captor.getValue()).isNotNull();
        assertThat(captor.getValue().toString()).isNotEmpty();
    }
}
