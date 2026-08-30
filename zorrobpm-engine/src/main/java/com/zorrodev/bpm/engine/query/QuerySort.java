package com.zorrodev.bpm.engine.query;

import com.zorrodev.bpm.contract.dto.query.BaseQuery;
import com.zorrodev.bpm.contract.dto.query.SortDirection;
import com.zorrodev.bpm.contract.exception.InvalidQueryException;
import org.springframework.data.domain.Sort;

import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the sort parameters of a query into a {@link Sort}, accepting only fields a query type
 * declares as sortable. The requested name never reaches the data layer unchecked: otherwise a
 * caller could order by any property of the entity and learn about its shape.
 */
public final class QuerySort {

    /** Unique field appended to every order, so that pages do not overlap or lose rows. */
    private static final String TIE_BREAKER = "id";

    private QuerySort() {
    }

    /**
     * @param sortableFields fields this query type allows; an empty set means sorting is not
     *                       supported for it yet and any {@code sort} is rejected
     * @return the requested order, or {@link Sort#unsorted()} when no {@code sort} is given -
     *         in which case {@code direction} is ignored and the order stays as it was before
     *         sorting existed
     */
    public static Sort resolve(BaseQuery query, Set<String> sortableFields) {
        String field = query.getSort();
        if (field == null || field.isBlank()) {
            return Sort.unsorted();
        }
        if (!sortableFields.contains(field)) {
            throw new InvalidQueryException("Sorting by '" + field + "' is not supported"
                + (sortableFields.isEmpty()
                    ? " for this query"
                    : "; supported fields: " + String.join(", ", new TreeSet<>(sortableFields))));
        }
        Sort.Direction direction = query.getDirection() == SortDirection.DESC
            ? Sort.Direction.DESC
            : Sort.Direction.ASC;
        return Sort.by(direction, field, TIE_BREAKER);
    }
}
