package com.zorrodev.bpm.client.resolver;

import com.zorrodev.bpm.contract.dto.query.BaseQuery;
import org.springframework.web.service.invoker.HttpRequestValues;

/**
 * Parameters every query inheriting {@link BaseQuery} shares, so that each resolver does not repeat
 * them.
 */
final class BaseQueryParameters {

    private BaseQueryParameters() {
    }

    static void add(HttpRequestValues.Builder requestValues, BaseQuery query) {
        requestValues.addRequestParameter("pageIndex", query.getPageIndex().toString());
        requestValues.addRequestParameter("pageSize", query.getPageSize().toString());
        if (query.getId() != null) {
            requestValues.addRequestParameter("id", query.getId().toString());
        }
        addSort(requestValues, query);
    }

    /**
     * Sort parameters only. Kept apart for resolvers that spell out paging themselves - the
     * process instance one sends {@code pageNumber} rather than {@code pageIndex}.
     */
    static void addSort(HttpRequestValues.Builder requestValues, BaseQuery query) {
        if (query.getSort() != null) {
            requestValues.addRequestParameter("sort", query.getSort());
        }
        if (query.getDirection() != null) {
            requestValues.addRequestParameter("direction", query.getDirection().name());
        }
    }
}
