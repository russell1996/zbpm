package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.IncidentMapper;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.mapper.ServiceTaskMapper;
import com.zorrodev.bpm.engine.mapper.UserTaskMapper;
import com.zorrodev.bpm.engine.mapper.VariableMapper;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.query.ActivityQueryOperations;
import com.zorrodev.bpm.engine.service.query.IncidentQueryOperations;
import com.zorrodev.bpm.engine.service.query.MessageSubscriptionQueryOperations;
import com.zorrodev.bpm.engine.service.query.ProcessInstanceQueryOperations;
import com.zorrodev.bpm.engine.service.query.ServiceTaskQueryOperations;
import com.zorrodev.bpm.engine.service.query.UserTaskQueryOperations;
import com.zorrodev.bpm.engine.service.query.VariableQueryOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryServiceImplTest {

    @Mock private DBService dbService;
    @Mock private ActivityQueryOperations activityQueryOperations;
    @Mock private ServiceTaskQueryOperations serviceTaskQueryOperations;
    @Mock private UserTaskQueryOperations userTaskQueryOperations;
    @Mock private ProcessInstanceQueryOperations processInstanceQueryOperations;
    @Mock private IncidentQueryOperations incidentQueryOperations;
    @Mock private MessageSubscriptionQueryOperations messageSubscriptionQueryOperations;
    @Mock private VariableQueryOperations variableQueryOperations;
    @Mock private ServiceTaskMapper serviceTaskMapper;
    @Mock private UserTaskMapper userTaskMapper;
    @Mock private ProcessInstanceMapper processInstanceMapper;
    @Mock private IncidentMapper incidentMapper;
    @Mock private VariableMapper variableMapper;
    @Mock private UserTaskRepository userTaskRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private VariableRepository variableRepository;

    @InjectMocks
    private QueryServiceImpl queryService;

    @Test
    void findServiceTasks_withFilters_returnsPagedDTO() {
        ServiceTaskQuery query = new ServiceTaskQuery();
        query.setProcessInstanceId(UUID.randomUUID());
        query.setId(UUID.randomUUID());

        ServiceTask dto = new ServiceTask();
        PagedDataDTO<ServiceTask> expected = new PagedDataDTO<>();
        expected.setData(List.of(dto));
        expected.setTotalElements(1L);
        when(serviceTaskQueryOperations.findServiceTasks(query, null)).thenReturn(expected);

        PagedDataDTO<ServiceTask> result = queryService.findServiceTasks(query, null);

        assertThat(result).isEqualTo(expected);
        org.mockito.Mockito.verify(serviceTaskQueryOperations).findServiceTasks(query, null);
    }

    @Test
    void findServiceTasks_noFilters_stillWorks() {
        ServiceTaskQuery query = new ServiceTaskQuery();
        PagedDataDTO<ServiceTask> expected = new PagedDataDTO<>();
        expected.setData(List.of());
        expected.setTotalElements(0L);
        when(serviceTaskQueryOperations.findServiceTasks(query, null)).thenReturn(expected);

        PagedDataDTO<ServiceTask> result = queryService.findServiceTasks(query, null);

        assertThat(result).isEqualTo(expected);
        org.mockito.Mockito.verify(serviceTaskQueryOperations).findServiceTasks(query, null);
    }

    @Test
    void getServiceTask_returnsMapped() {
        UUID id = UUID.randomUUID();
        ServiceTask dto = new ServiceTask();
        dto.setId(id);
        when(serviceTaskQueryOperations.getServiceTask(id)).thenReturn(dto);

        assertThat(queryService.getServiceTask(id)).isSameAs(dto);
        org.mockito.Mockito.verify(serviceTaskQueryOperations).getServiceTask(id);
    }

    @Test
    void getServiceTask_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(serviceTaskQueryOperations.getServiceTask(id)).thenThrow(new NoSuchElementException());
        assertThatThrownBy(() -> queryService.getServiceTask(id)).isInstanceOf(NoSuchElementException.class);
        org.mockito.Mockito.verify(serviceTaskQueryOperations).getServiceTask(id);
    }

    @Test
    void findUserTasks_withFilters_returnsPagedDTO() {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(UUID.randomUUID());
        query.setId(UUID.randomUUID());

        UserTask dto = new UserTask();
        PagedDataDTO<UserTask> expected = new PagedDataDTO<>();
        expected.setData(List.of(dto));
        expected.setTotalElements(1L);
        when(userTaskQueryOperations.findUserTasks(query, null)).thenReturn(expected);

        PagedDataDTO<UserTask> result = queryService.findUserTasks(query, null);

        assertThat(result).isEqualTo(expected);
        org.mockito.Mockito.verify(userTaskQueryOperations).findUserTasks(query, null);
    }

    @Test
    void getUserTask_returnsMapped() {
        UUID id = UUID.randomUUID();
        UserTask dto = new UserTask();
        dto.setId(id);
        when(userTaskQueryOperations.getUserTask(id)).thenReturn(dto);

        assertThat(queryService.getUserTask(id)).isSameAs(dto);
        org.mockito.Mockito.verify(userTaskQueryOperations).getUserTask(id);
    }

    @Test
    void getUserTask_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(userTaskQueryOperations.getUserTask(id)).thenThrow(new NoSuchElementException());
        assertThatThrownBy(() -> queryService.getUserTask(id)).isInstanceOf(NoSuchElementException.class);
        org.mockito.Mockito.verify(userTaskQueryOperations).getUserTask(id);
    }

    @Test
    void getProcessInstance_delegatesToDbService() {
        UUID id = UUID.randomUUID();
        ProcessInstance pi = new ProcessInstance();
        pi.setId(id);
        when(processInstanceQueryOperations.getProcessInstance(id)).thenReturn(pi);

        assertThat(queryService.getProcessInstance(id)).isSameAs(pi);
        org.mockito.Mockito.verify(processInstanceQueryOperations).getProcessInstance(id);
    }

    @Test
    void findProcessInstances_withFilters_returnsPagedDTO() {
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        query.setId(UUID.randomUUID());
        query.setParentProcessInstanceId(UUID.randomUUID());

        ProcessInstance dto = new ProcessInstance();
        PagedDataDTO<ProcessInstance> expected = new PagedDataDTO<>();
        expected.setData(List.of(dto));
        expected.setTotalElements(1L);
        when(processInstanceQueryOperations.findProcessInstances(query, null)).thenReturn(expected);

        PagedDataDTO<ProcessInstance> result = queryService.findProcessInstances(query, null);

        assertThat(result).isEqualTo(expected);
        org.mockito.Mockito.verify(processInstanceQueryOperations).findProcessInstances(query, null);
    }

    @Test
    void getIncident_delegatesToDbService() {
        UUID id = UUID.randomUUID();
        Incident inc = new Incident();
        inc.setId(id);
        when(incidentQueryOperations.getIncident(id)).thenReturn(inc);

        assertThat(queryService.getIncident(id)).isSameAs(inc);
        org.mockito.Mockito.verify(incidentQueryOperations).getIncident(id);
    }

    @Test
    void findIncidents_returnsPagedDTO() {
        IncidentQuery query = new IncidentQuery();
        Incident dto = new Incident();
        PagedDataDTO<Incident> expected = new PagedDataDTO<>();
        expected.setData(List.of(dto));
        expected.setTotalElements(1L);
        when(incidentQueryOperations.findIncidents(query, null)).thenReturn(expected);

        PagedDataDTO<Incident> result = queryService.findIncidents(query, null);

        assertThat(result).isEqualTo(expected);
        org.mockito.Mockito.verify(incidentQueryOperations).findIncidents(query, null);
    }

    @Test
    void findVariables_withAllFilters_returnsPagedDTO() {
        VariableQuery query = new VariableQuery();
        query.setProcessInstanceId(UUID.randomUUID());
        query.setName("var1");
        query.setType(ProcessVariableType.LONG);
        query.setValue("1");

        ProcessVariable dto = new ProcessVariable();
        PagedDataDTO<ProcessVariable> expected = new PagedDataDTO<>();
        expected.setData(List.of(dto));
        expected.setTotalElements(1L);
        when(variableQueryOperations.findVariables(query, null)).thenReturn(expected);

        PagedDataDTO<ProcessVariable> result = queryService.findVariables(query, null);

        assertThat(result).isEqualTo(expected);
        org.mockito.Mockito.verify(variableQueryOperations).findVariables(query, null);
    }
}
