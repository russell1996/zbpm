package com.zorrodev.bpm.client.resolver;

import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import org.springframework.core.MethodParameter;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

public class VariableQueryParametersArgumentResolver implements HttpServiceArgumentResolver {
    @Override
    public boolean resolve(Object argument, MethodParameter parameter, HttpRequestValues.Builder requestValues) {
        if (parameter.getParameterType().equals(VariableQuery.class)) {
            VariableQuery parameters = (VariableQuery) argument;
            requestValues.addRequestParameter("pageIndex", parameters.getPageIndex().toString());
            requestValues.addRequestParameter("pageSize", parameters.getPageSize().toString());
            if (parameters.getProcessInstanceId() != null) {
                requestValues.addRequestParameter("processInstanceId", parameters.getProcessInstanceId().toString());
            }
            if (parameters.getName() != null) {
                requestValues.addRequestParameter("name", parameters.getName());
            }
            if (parameters.getType() != null) {
                requestValues.addRequestParameter("type", parameters.getType().name());
            }
            if (parameters.getValue() != null) {
                requestValues.addRequestParameter("value", parameters.getValue());
            }
            return true;
        }
        return false;
    }
}
