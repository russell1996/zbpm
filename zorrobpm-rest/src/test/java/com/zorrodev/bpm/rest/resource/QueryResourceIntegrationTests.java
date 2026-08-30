package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.SortDirection;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskRelation;
import com.zorrodev.bpm.contract.exception.InvalidQueryException;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.service.QueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks how the new query parameters cross the HTTP boundary: binding of the repeated groups
 * parameter and of the enums, and the status a rejected query produces.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class QueryResourceIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryService queryService;

    @Test
    void bindsRepeatedGroupsAndTheNewParameters() throws Exception {
        when(queryService.findUserTasks(any())).thenReturn(new PagedDataDTO<UserTask>());
        UUID processInstanceId = UUID.randomUUID();

        mockMvc.perform(get("/user-tasks")
                .param("relatedToUser", "U1")
                .param("relatedToGroups", "G1")
                .param("relatedToGroups", "G2")
                .param("relation", "CANDIDATE")
                .param("sort", "createdAt")
                .param("direction", "DESC")
                .param("assigned", "false")
                .param("processInstanceId", processInstanceId.toString()))
            .andExpect(status().isOk());

        ArgumentCaptor<UserTaskQuery> captor = ArgumentCaptor.forClass(UserTaskQuery.class);
        verify(queryService).findUserTasks(captor.capture());
        UserTaskQuery bound = captor.getValue();

        assertThat(bound.getRelatedToUser()).isEqualTo("U1");
        assertThat(bound.getRelatedToGroups()).containsExactly("G1", "G2");
        assertThat(bound.getRelation()).isEqualTo(UserTaskRelation.CANDIDATE);
        assertThat(bound.getSort()).isEqualTo("createdAt");
        assertThat(bound.getDirection()).isEqualTo(SortDirection.DESC);
        assertThat(bound.getAssigned()).isFalse();
        assertThat(bound.getProcessInstanceId()).isEqualTo(processInstanceId);
    }

    @Test
    void legacyParametersStillBind() throws Exception {
        when(queryService.findUserTasks(any())).thenReturn(new PagedDataDTO<UserTask>());

        mockMvc.perform(get("/user-tasks")
                .param("assignee", "U2")
                .param("candidateGroup", "G1")
                .param("candidateUser", "U1")
                .param("completed", "false")
                .param("formKey", "approve-form")
                .param("bpmnElementId", "approveTask"))
            .andExpect(status().isOk());

        ArgumentCaptor<UserTaskQuery> captor = ArgumentCaptor.forClass(UserTaskQuery.class);
        verify(queryService).findUserTasks(captor.capture());
        UserTaskQuery bound = captor.getValue();

        assertThat(bound.getAssignee()).isEqualTo("U2");
        assertThat(bound.getCandidateGroup()).isEqualTo("G1");
        assertThat(bound.getCandidateUser()).isEqualTo("U1");
        assertThat(bound.getCompleted()).isFalse();
        assertThat(bound.getFormKey()).isEqualTo("approve-form");
        assertThat(bound.getBpmnElementId()).isEqualTo("approveTask");
        assertThat(bound.getRelation()).isNull();
        assertThat(bound.getRelatedToUser()).isNull();
        assertThat(bound.getRelatedToGroups()).isEmpty();
    }

    @Test
    void requestWithoutNewParametersReachesTheServiceUnchanged() throws Exception {
        when(queryService.findUserTasks(any())).thenReturn(new PagedDataDTO<UserTask>());

        mockMvc.perform(get("/user-tasks")).andExpect(status().isOk());

        ArgumentCaptor<UserTaskQuery> captor = ArgumentCaptor.forClass(UserTaskQuery.class);
        verify(queryService).findUserTasks(captor.capture());
        UserTaskQuery bound = captor.getValue();

        assertThat(bound.getSort()).isNull();
        assertThat(bound.getDirection()).isNull();
        assertThat(bound.getRelation()).isNull();
    }

    @Test
    void unknownRelationIsRejected() throws Exception {
        mockMvc.perform(get("/user-tasks").param("relatedToUser", "U1").param("relation", "OWNER"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknownDirectionIsRejected() throws Exception {
        mockMvc.perform(get("/user-tasks").param("sort", "createdAt").param("direction", "UPWARDS"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectedQueryIsReportedAsBadRequestNamingTheField() throws Exception {
        when(queryService.findUserTasks(any()))
            .thenThrow(new InvalidQueryException("Sorting by 'assignee' is not supported"));

        MvcResult result = mockMvc.perform(get("/user-tasks").param("sort", "assignee"))
            .andExpect(status().isBadRequest())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("assignee");
    }

    @Test
    void otherFailuresKeepTheirPreviousResponse() throws Exception {
        when(queryService.findUserTasks(any())).thenThrow(new IllegalStateException("boom"));

        // The advice handles InvalidQueryException only; anything else is left alone and still
        // surfaces as a server error rather than a 400 with a body.
        assertThat(catchStatus(get("/user-tasks"))).isNotEqualTo(400);
    }

    private int catchStatus(org.springframework.test.web.servlet.RequestBuilder request) {
        try {
            return mockMvc.perform(request).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return 500;
        }
    }
}
