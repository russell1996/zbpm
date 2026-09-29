package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Component
public class QueryPaginationSupport {

    /** WO-A-05: maximum allowed page size — prevents DoS via huge queries */
    public static final int MAX_PAGE_SIZE = 200;

    public <T, S> PagedDataDTO<T> toDTO(Page<S> page, Function<S, T> converter) {
        PagedDataDTO<T> result = new PagedDataDTO<>();
        result.setTotalElements(page.getTotalElements());
        result.setPageIndex(page.getNumber());
        result.setPageSize(page.getSize());
        List<T> data = new ArrayList<>();
        for (S entity : page.getContent()) {
            data.add(converter.apply(entity));
        }
        result.setData(data);
        return result;
    }

    /**
     * WO-PERF-2 (D-01): bulk variant — the mapper batch-loads its related rows (definitions/
     * activities) in ONE extra query for the whole page instead of one per entity.
     */
    public <T, S> PagedDataDTO<T> toDTOBulk(Page<S> page, Function<List<S>, List<T>> bulkConverter) {
        PagedDataDTO<T> result = new PagedDataDTO<>();
        result.setTotalElements(page.getTotalElements());
        result.setPageIndex(page.getNumber());
        result.setPageSize(page.getSize());
        result.setData(bulkConverter.apply(page.getContent()));
        return result;
    }

    /**
     * WO-PERF-2 (D-05): entities that only carry {@code processInstanceId} (TimerJob,
     * MessageSubscription, ProcessVariable) are scoped to the allowed process DEFINITIONS via a
     * single correlated subquery on {@code process_instances}, instead of the caller first
     * materializing every matching instance id into a JVM list (native SQL round-trip) and then
     * building an {@code IN (...)} list from it — two round-trips and, on a large tenant, a large
     * heap-resident id list, for what the database can do as one query with a semi-join. A
     * definition with zero instances yet naturally yields zero matching rows here too, so the
     * separate "piIds.isEmpty() -> emptyPage" short-circuit the old code needed is not required.
     */
    public <T> Specification<T> processInstanceInAllowedDefinitions(Collection<UUID> allowedPdIds) {
        return (root, query, cb) -> {
            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<ProcessInstanceEntity> piRoot = subquery.from(ProcessInstanceEntity.class);
            subquery.select(piRoot.get("id")).where(piRoot.get("processDefinitionId").in(allowedPdIds));
            return root.<UUID>get("processInstanceId").in(subquery);
        };
    }

    /** WO-A-05: server-side clamp — safety net even if validation annotations are bypassed */
    public PageRequest clampedPage(Integer pageIndex, Integer pageSize, Sort sort) {
        int page = Math.max(0, pageIndex != null ? pageIndex : 0);
        int size = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize != null ? pageSize : 10));
        return PageRequest.of(page, size, sort);
    }

    /** WO-ARCH-1a: default DENY — empty allowedPdIds → empty page (no memberships = see nothing). */
    public <T> PagedDataDTO<T> emptyPage(Object query) {
        PagedDataDTO<T> result = new PagedDataDTO<>();
        result.setPageIndex(0);
        result.setPageSize(10);
        result.setTotalElements(0L);
        result.setData(List.of());
        return result;
    }
}
