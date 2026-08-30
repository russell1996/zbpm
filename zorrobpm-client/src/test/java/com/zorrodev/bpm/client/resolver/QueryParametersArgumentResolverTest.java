package com.zorrodev.bpm.client.resolver;

import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.SortDirection;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskRelation;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the query parameters the HTTP client actually puts on the wire.
 */
class QueryParametersArgumentResolverTest {

    @Test
    void userTaskResolver_sendsRelationBlockAndSort() {
        UserTaskQuery query = new UserTaskQuery();
        query.setRelatedToUser("U1");
        query.setRelatedToGroups(List.of("G1", "G2"));
        query.setRelation(UserTaskRelation.CANDIDATE);
        query.setSort("createdAt");
        query.setDirection(SortDirection.DESC);

        MultiValueMap<String, String> params = resolve(
            new UserTaskQueryParametersArgumentResolver(), query, "userTasks", UserTaskQuery.class);

        assertThat(params.get("relatedToUser")).containsExactly("U1");
        assertThat(params.get("relatedToGroups")).containsExactly("G1", "G2");
        assertThat(params.get("relation")).containsExactly("CANDIDATE");
        assertThat(params.get("sort")).containsExactly("createdAt");
        assertThat(params.get("direction")).containsExactly("DESC");
    }

    @Test
    void userTaskResolver_omitsTheBlockWhenNotRequested() {
        UserTaskQuery query = new UserTaskQuery();
        query.setAssignee("U2");

        MultiValueMap<String, String> params = resolve(
            new UserTaskQueryParametersArgumentResolver(), query, "userTasks", UserTaskQuery.class);

        assertThat(params).containsKeys("pageIndex", "pageSize", "assignee");
        assertThat(params).doesNotContainKeys("relatedToUser", "relatedToGroups", "relation", "sort", "direction");
    }

    @Test
    void serviceTaskResolver_sendsSort() {
        ServiceTaskQuery query = new ServiceTaskQuery();
        query.setSort("createdAt");

        MultiValueMap<String, String> params = resolve(
            new ServiceTaskQueryParametersArgumentResolver(), query, "serviceTasks", ServiceTaskQuery.class);

        assertThat(params.get("sort")).containsExactly("createdAt");
        assertThat(params).doesNotContainKey("direction");
    }

    @Test
    void processInstanceResolver_sendsSort() {
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        query.setSort("startedAt");
        query.setDirection(SortDirection.ASC);

        MultiValueMap<String, String> params = resolve(
            new ProcessInstanceQueryParameterArgumentResolver(), query, "processInstances", ProcessInstanceQuery.class);

        assertThat(params.get("sort")).containsExactly("startedAt");
        assertThat(params.get("direction")).containsExactly("ASC");
    }

    private MultiValueMap<String, String> resolve(Object resolver, Object query,
                                                  String methodName, Class<?> parameterType) {
        HttpRequestValues.Builder builder = HttpRequestValues.builder();
        MethodParameter parameter = new MethodParameter(method(methodName, parameterType), 0);
        boolean resolved = ((HttpServiceArgumentResolver) resolver).resolve(query, parameter, builder);
        assertThat(resolved).isTrue();

        MultiValueMap<String, String> captured = new LinkedMultiValueMap<>();
        builder.configureRequestParams(captured::putAll);
        return captured;
    }

    private static Method method(String name, Class<?> parameterType) {
        try {
            return Signatures.class.getDeclaredMethod(name, parameterType);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Only exists to hand the resolvers a {@link MethodParameter} of the right type. */
    @SuppressWarnings("unused")
    private static final class Signatures {
        void userTasks(UserTaskQuery query) { }

        void serviceTasks(ServiceTaskQuery query) { }

        void processInstances(ProcessInstanceQuery query) { }
    }
}
