package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QueryPaginationSupportTest {

    private final QueryPaginationSupport support = new QueryPaginationSupport();

    // --- MAX_PAGE_SIZE ---
    @Test
    void maxPageSize_is200() {
        assertThat(QueryPaginationSupport.MAX_PAGE_SIZE).isEqualTo(200);
    }

    // --- clampedPage ---
    @Test
    void clampedPage_defaults() {
        PageRequest pr = support.clampedPage(null, null, Sort.unsorted());
        assertThat(pr.getPageNumber()).isEqualTo(0);
        assertThat(pr.getPageSize()).isEqualTo(10);
        assertThat(pr.getSort()).isEqualTo(Sort.unsorted());
    }

    @Test
    void clampedPage_negativeClamped() {
        PageRequest pr = support.clampedPage(-5, -1, Sort.by("x"));
        assertThat(pr.getPageNumber()).isEqualTo(0);
        assertThat(pr.getPageSize()).isEqualTo(1);
    }

    @Test
    void clampedPage_capsAtMax() {
        PageRequest pr = support.clampedPage(0, 999, Sort.unsorted());
        assertThat(pr.getPageSize()).isEqualTo(200);
    }

    @Test
    void clampedPage_exactValues() {
        PageRequest pr = support.clampedPage(2, 50, Sort.by("dueAt").ascending());
        assertThat(pr.getPageNumber()).isEqualTo(2);
        assertThat(pr.getPageSize()).isEqualTo(50);
        assertThat(pr.getSort()).isEqualTo(Sort.by("dueAt").ascending());
    }

    @Test
    void clampedPage_preservesSort() {
        Sort sort = Sort.by("createdAt").descending();
        PageRequest pr = support.clampedPage(1, 10, sort);
        assertThat(pr.getSort()).isEqualTo(sort);
    }

    /**
     * WO-IN-2 red-team MEDIUM-2: {@code pageIndex} was only {@code Math.max(0, …)} while the size
     * was clamped, so a large index overflowed {@code pageIndex*pageSize} and Spring Data JPA's
     * {@code PageableUtils.getOffsetAsInteger} threw {@code InvalidDataAccessApiUsageException}
     * — a 500 straight out of caller input. Every endpoint using this method shares the guard, so
     * the assertion is on the OFFSET ITSELF, not on the returned page number.
     */
    @Test
    void clampedPage_offsetNeverOverflowsInt_whateverTheIndex() {
        for (int size : new int[]{1, 10, 200}) {
            for (int index : new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE / 2, 1_000_000_000}) {
                PageRequest pr = support.clampedPage(index, size, Sort.unsorted());
                assertThat(pr.getOffset())
                    .as("offset for index=%d size=%d", index, size)
                    .isLessThanOrEqualTo(Integer.MAX_VALUE);
            }
        }
    }

    /** The clamp must not swallow a legal page: index 1000 at size 10 stays where it was. */
    @Test
    void clampedPage_keepsEveryLegalPageIndex() {
        PageRequest pr = support.clampedPage(1000, 10, Sort.unsorted());
        assertThat(pr.getPageNumber()).isEqualTo(1000);
        assertThat(pr.getOffset()).isEqualTo(10000L);
    }

    /**
     * WO-QW-14, DoD «юнит на хелпер: {@code pageIndex*pageSize} никогда не выходит за int НИ ПРИ
     * КАКОМ индексе». The pre-existing {@link #clampedPage_offsetNeverOverflowsInt_whateverTheIndex}
     * samples three sizes × three indexes; this one sweeps <b>every</b> legal page size (1…200 — the
     * whole {@link #MAX_PAGE_SIZE} range) crossed with the indexes that matter: the int extremes,
     * negative values, and the exact {@code Integer.MAX_VALUE / size} boundary where the clamp
     * starts biting. The invariant asserted is the one Spring Data actually breaks on, its
     * {@code PageableUtils.getOffsetAsInteger} cast — i.e. the OFFSET, not the page number.
     *
     * <p>Mutation: deleting {@code page = Math.min(page, Integer.MAX_VALUE / size)} from the helper
     * makes this test RED for every size ≥ 2 (only size 1 survives, since 1×MAX_VALUE still fits),
     * which is why the sweep spans sizes rather than repeating one.
     */
    @Test
    void clampedPage_offsetNeverOverflowsInt_forEveryLegalSizeAndExtremeIndex() {
        int[] extremeIndexes = {
            Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1, 0, 1,
            Integer.MAX_VALUE / 200, Integer.MAX_VALUE / 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE
        };
        for (int size = 1; size <= QueryPaginationSupport.MAX_PAGE_SIZE; size++) {
            for (int index : extremeIndexes) {
                PageRequest pr = support.clampedPage(index, size, Sort.unsorted());
                assertThat(pr.getOffset())
                        .as("offset for index=%d size=%d must survive the PageableUtils int cast",
                                index, size)
                        .isBetween(0L, (long) Integer.MAX_VALUE);
                assertThat(pr.getPageNumber())
                        .as("page number must stay non-negative for index=%d size=%d", index, size)
                        .isGreaterThanOrEqualTo(0);
            }
        }
    }

    /**
     * The boundary itself, pinned from both sides: an index exactly at
     * {@code MAX_VALUE / size} is legal and must be kept verbatim, and the next one must be pulled
     * down to it. Without this the guard could pass the sweep above while silently degrading every
     * legal page (e.g. clamping everything to 0), which is the failure mode the
     * {@code processDefinitions_distantButLegalIndex_…} IT guards on the HTTP side.
     */
    @Test
    void clampedPage_indexBoundary_keptExactlyAndOneMoreIsPulledDown() {
        int size = 10;
        int boundary = Integer.MAX_VALUE / size;

        assertThat(support.clampedPage(boundary, size, Sort.unsorted()).getPageNumber())
                .as("the boundary index itself is legal and must not be moved")
                .isEqualTo(boundary);
        assertThat(support.clampedPage(boundary + 1, size, Sort.unsorted()).getPageNumber())
                .as("one past the boundary is pulled down to it, never above")
                .isEqualTo(boundary);
    }

    /**
     * Nulls are the caller's normal case on these two endpoints ({@code ?pageIndex=} binds to null),
     * and before WO-QW-14 {@code Math.max(0, parameters.getPageIndex())} would have NPE'd on them —
     * {@code ProcessDefinitionServiceImpl} still does that inline in neither branch, but the
     * guard is now the helper's job, so it is pinned here for both null and negative input.
     */
    @Test
    void clampedPage_nullAndNegativeCallerValuesNeverThrow() {
        assertThat(support.clampedPage(null, null, Sort.unsorted()).getPageNumber()).isZero();
        assertThat(support.clampedPage(-1, -1, Sort.unsorted()).getPageNumber()).isZero();
        assertThat(support.clampedPage(null, -1, Sort.unsorted()).getPageSize()).isEqualTo(1);
    }

    // --- emptyPage ---
    @Test
    void emptyPage_returnsEmpty() {
        PagedDataDTO<String> dto = support.emptyPage(new Object());
        assertThat(dto.getPageIndex()).isZero();
        assertThat(dto.getPageSize()).isEqualTo(10);
        assertThat(dto.getTotalElements()).isZero();
        assertThat(dto.getData()).isEmpty();
    }

    @Test
    void emptyPage_genericWorks() {
        PagedDataDTO<Integer> dto = support.emptyPage("query");
        assertThat(dto.getData()).isEmpty();
        assertThat(dto.getTotalElements()).isZero();
    }

    // --- toDTO ---
    @Test
    void toDTO_convertsPage() {
        Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 10), 2);
        PagedDataDTO<Integer> dto = support.toDTO(page, String::length);
        assertThat(dto.getTotalElements()).isEqualTo(2);
        assertThat(dto.getPageIndex()).isEqualTo(0);
        assertThat(dto.getPageSize()).isEqualTo(10);
        assertThat(dto.getData()).containsExactly(1, 1);
    }

    @Test
    void toDTO_emptyPage() {
        Page<String> page = Page.empty();
        PagedDataDTO<String> dto = support.toDTO(page, Function.identity());
        assertThat(dto.getData()).isEmpty();
        assertThat(dto.getTotalElements()).isZero();
    }

    // --- toDTOBulk ---
    @Test
    void toDTOBulk_convertsViaBulk() {
        Page<String> page = new PageImpl<>(List.of("a", "b", "c"), PageRequest.of(0, 10), 3);
        PagedDataDTO<String> dto = support.toDTOBulk(page, list -> list.stream().map(String::toUpperCase).toList());
        assertThat(dto.getData()).containsExactly("A", "B", "C");
        assertThat(dto.getTotalElements()).isEqualTo(3);
        assertThat(dto.getPageIndex()).isZero();
    }

    @Test
    void toDTOBulk_empty() {
        Page<String> page = Page.empty();
        PagedDataDTO<String> dto = support.toDTOBulk(page, list -> List.of());
        assertThat(dto.getData()).isEmpty();
    }

    // --- processInstanceInAllowedDefinitions ---
    @Test
    void processInstanceInAllowedDefinitions_createsSpec() {
        Collection<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        Specification<Object> spec = support.processInstanceInAllowedDefinitions(ids);
        assertThat(spec).isNotNull();
        Root<Object> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Subquery<UUID> subquery = mock(Subquery.class);
        Root<?> piRoot = mock(Root.class);
        Path<Object> piIdPath = mock(Path.class);
        Path<Object> processInstanceIdPath = mock(Path.class);
        lenient().when(query.subquery(UUID.class)).thenReturn((Subquery) subquery);
        lenient().when(subquery.from(any(Class.class))).thenReturn((Root) piRoot);
        lenient().when(subquery.select(any())).thenReturn(subquery);
        lenient().when(subquery.where(any(Predicate.class))).thenReturn(subquery);
        lenient().when(piRoot.get(anyString())).thenReturn((Path) piIdPath);
        lenient().when(piIdPath.in(anyCollection())).thenReturn(mock(Predicate.class));
        lenient().when(root.get(anyString())).thenReturn((Path) processInstanceIdPath);
        Predicate mockPredicate = mock(Predicate.class);
        doReturn(mockPredicate).when(processInstanceIdPath).in(any(Subquery.class));

        spec.toPredicate(root, query, cb);
        verify(query).subquery(UUID.class);
        verify(subquery).from(ProcessInstanceEntity.class);
        verify(subquery).select(any());
        verify(root).get("processInstanceId");
    }

    @Test
    void processInstanceInAllowedDefinitions_nullHandledByCaller() {
        // Direct call with empty collection should still create spec (emptyPage guard is in caller, not here)
        Specification<Object> spec = support.processInstanceInAllowedDefinitions(List.of());
        assertThat(spec).isNotNull();
    }
}
