package com.zorrodev.bpm.client.resolver;

import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import org.springframework.core.MethodParameter;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

public class UserTaskQueryParametersArgumentResolver implements HttpServiceArgumentResolver {
    @Override
    public boolean resolve(Object argument, MethodParameter parameter, HttpRequestValues.Builder requestValues) {
        if (parameter.getParameterType().equals(UserTaskQuery.class)) {
            UserTaskQuery parameters = (UserTaskQuery) argument;
            requestValues.addRequestParameter("pageIndex", parameters.getPageIndex().toString());
            requestValues.addRequestParameter("pageSize", parameters.getPageSize().toString());
            if (parameters.getId() != null) {
                requestValues.addRequestParameter("id", parameters.getId().toString());
            }
            if (parameters.getAssignee() != null) {
                requestValues.addRequestParameter("assignee", parameters.getAssignee());
            }
            if (parameters.getAssigned() != null) {
                requestValues.addRequestParameter("assigned", parameters.getAssigned().toString());
            }
            if (parameters.getProcessInstanceId() != null) {
                requestValues.addRequestParameter("processInstanceId", parameters.getProcessInstanceId().toString());
            }
            // ─── WO-IN-2: the filters THIS work order added are sent from the SDK too, otherwise
            // they would be another "declared but never used" gap (P-11). The pre-existing
            // gaps of this resolver (candidateGroup / candidateUser / completed) are NOT
            // touched here — they belong to the WO-IN-1 inventory finding, not to this WO (V7).
            if (parameters.getBpmnElementId() != null) {
                requestValues.addRequestParameter("bpmnElementId", parameters.getBpmnElementId());
            }
            if (parameters.getFormKey() != null) {
                requestValues.addRequestParameter("formKey", parameters.getFormKey());
            }
            if (parameters.getRelatesTo() != null) {
                requestValues.addRequestParameter("relatesTo", parameters.getRelatesTo().toString());
            }
            if (parameters.getSortBy() != null) {
                requestValues.addRequestParameter("sortBy", parameters.getSortBy());
            }
            if (parameters.getSortOrder() != null) {
                requestValues.addRequestParameter("sortOrder", parameters.getSortOrder());
            }
            return true;
        }
        return false;
    }
}
